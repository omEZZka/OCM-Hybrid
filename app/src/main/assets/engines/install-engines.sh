#!/bin/sh
# OCM Hybrid — installs the external agent engines into the user's HOME.
#
# Everything lands under /root/.local, which the app never wipes (app updates keep the data
# dir, core updates only replace the OS image). Runs inside proot, so it is plain POSIX sh.
#
# Progress is reported as machine-readable lines the app parses:
#   @@STEP <text>
#   @@PCT <0..100>
#   @@ERR <text>
#   @@DONE
set -u

STEP() { printf '@@STEP %s\n' "$1"; }
PCT() { printf '@@PCT %s\n' "$1"; }
ERR() { printf '@@ERR %s\n' "$1"; }
fail() { ERR "$1"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
export PATH="/root/.local/bin:/root/.local/node/bin:/root/.local/titan/bin:/root/.local/titancli/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

mkdir -p /root/.local/bin /root/.local/titan /root/.local/titancli /root/.local/hermes

apt_install() {
    # The shipped image ships without apt lists, so a bare `apt-get install` always fails with
    # "Unable to locate package". Refresh the index first, and tolerate a stale index when the
    # phone is offline (the packages may already be present).
    if ! apt-get update -qq >/dev/null 2>&1; then
        printf 'apt index unreachable, continuing with what is installed\n'
    fi
    apt-get install -y --no-install-recommends "$@" >/dev/null 2>&1
}

PCT 5
STEP "Базовые пакеты"
# python3-venv is what provides ensurepip; without it `python3 -m venv` creates a broken env.
apt_install python3 python3-venv python3-pip ca-certificates curl xz-utils || true

if ! command -v python3 >/dev/null 2>&1; then
    fail "python3 отсутствует в окружении и не установился. Проверьте интернет и повторите."
fi

# ---------------------------------------------------------------- Hermes Agent (Python)
PCT 15
STEP "Hermes Agent: виртуальное окружение"
VENV=/root/.local/hermes/venv
rm -rf "$VENV"

# 1st choice: a real venv. Needs python3-venv (ensurepip).
if python3 -m venv "$VENV" >/tmp/venv.log 2>&1 && [ -x "$VENV/bin/python3" ]; then
    PY="$VENV/bin/python3"
else
    # 2nd choice: virtualenv (needs no ensurepip) via a user install on the system python.
    STEP "Hermes Agent: virtualenv (без ensurepip)"
    python3 -m pip install --user --no-cache-dir --quiet virtualenv >>/tmp/venv.log 2>&1 || true
    if "$HOME/.local/bin/virtualenv" "$VENV" >>/tmp/venv.log 2>&1 && [ -x "$VENV/bin/python3" ]; then
        PY="$VENV/bin/python3"
    else
        # Last resort: install Hermes into the user site and run it from there.
        STEP "Hermes Agent: установка без venv"
        if ! python3 -m pip install --user --no-cache-dir --upgrade pip >>/tmp/venv.log 2>&1; then
            fail "pip недоступен. Установка не удалась: $(tail -n 3 /tmp/venv.log | tr '\n' ' ')"
        fi
        PY=""
    fi
fi

PCT 25
STEP "Hermes Agent: пакет"
if [ -n "$PY" ]; then
    "$PY" -m pip install --no-cache-dir --upgrade pip >>/tmp/venv.log 2>&1 || true
    if ! "$PY" -m pip install --no-cache-dir hermes-agent >>/tmp/venv.log 2>&1; then
        fail "Hermes Agent не установился: $(tail -n 3 /tmp/venv.log | tr '\n' ' ')"
    fi
    HERMES_BIN="$VENV/bin/hermes"
else
    if ! python3 -m pip install --user --no-cache-dir hermes-agent >>/tmp/venv.log 2>&1; then
        fail "Hermes Agent не установился: $(tail -n 3 /tmp/venv.log | tr '\n' ' ')"
    fi
    HERMES_BIN="$HOME/.local/bin/hermes"
fi

[ -x "$HERMES_BIN" ] || HERMES_BIN="$(command -v hermes 2>/dev/null || true)"
[ -n "$HERMES_BIN" ] && [ -x "$HERMES_BIN" ] || fail "Hermes установлен, но исполняемый файл не найден"

printf '#!/bin/sh\nexec %s "$@"\n' "$HERMES_BIN" > /root/.local/bin/hermes
chmod +x /root/.local/bin/hermes

# ---------------------------------------------------------------- Node 22 for TITAN
PCT 45
STEP "Node.js 22"
NODE_VER="22.14.0"
node_ok() {
    command -v node >/dev/null 2>&1 || return 1
    v="$(node -v 2>/dev/null | sed 's/^v//')"
    [ -n "$v" ] || return 1
    [ "$(echo "$v" | cut -d. -f1)" -ge 22 ] 2>/dev/null
}
# TITAN's package.json requires node >= 22. Distro nodejs (18) installs fine but the gateway
# refuses to start, so bring a private Node into HOME instead of fighting the distro one.
if node_ok; then
    printf 'system node is new enough: %s\n' "$(node -v)"
else
    if ! apt_install nodejs npm; then :; fi
    if ! node_ok; then
        ARCH="$(uname -m)"
        case "$ARCH" in
            aarch64|arm64) NARCH="linux-arm64" ;;
            armv7l|armv8l) NARCH="linux-armv7l" ;;
            x86_64) NARCH="linux-x64" ;;
            *) fail "неизвестная архитектура: $ARCH" ;;
        esac
        mkdir -p /root/.local/node
        URL="https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-${NARCH}.tar.xz"
        if ! curl -fsSL --retry 3 --connect-timeout 20 "$URL" -o /tmp/node.tar.xz; then
            fail "не удалось скачать Node.js 22 ($URL)"
        fi
        if ! tar -xJf /tmp/node.tar.xz -C /root/.local/node --strip-components=1; then
            fail "не удалось распаковать Node.js 22"
        fi
        rm -f /tmp/node.tar.xz
        export PATH="/root/.local/node/bin:$PATH"
        node_ok || fail "Node.js 22 установлен, но не запускается"
    fi
fi
printf '%s\n' "$(node -v)" > /root/.local/node.version

PCT 60
STEP "TITAN"
NPM="$(command -v npm || true)"
[ -n "$NPM" ] || fail "npm не найден после установки Node.js"
if ! "$NPM" install -g --prefix /root/.local/titan --no-audit --no-fund titan-agent@latest >/tmp/titan.log 2>&1; then
    fail "TITAN не установился: $(tail -n 3 /tmp/titan.log | tr '\n' ' ')"
fi
[ -x /root/.local/titan/bin/titan ] || fail "TITAN установлен, но bin/titan отсутствует"

PCT 85
STEP "TITAN CLI"
# Separate prefix on purpose: both packages ship a binary named `titan`.
if ! "$NPM" install -g --prefix /root/.local/titancli --no-audit --no-fund titan-agent-cli@latest >/tmp/titancli.log 2>&1; then
    fail "TITAN CLI не установился: $(tail -n 3 /tmp/titancli.log | tr '\n' ' ')"
fi

PCT 95
STEP "Проверка"
# Smoke test: a working install answers on its CLI. Output is ignored, only the exit path matters.
/root/.local/bin/hermes --version >/dev/null 2>&1 || printf 'warning: hermes --version did not answer\n'

PCT 100
printf '@@DONE\n'