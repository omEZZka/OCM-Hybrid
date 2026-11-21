package app.opencodemob

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL

/**
 * The four agent runtimes this app can drive.
 *
 * `opencode` is the built-in one: it ships inside the APK and its UI is the bundled
 * `assets/ui` page. The other three are installed into the user's HOME inside the proot
 * environment (so they survive app and core updates) and each serves its own web dashboard
 * on loopback, which [EngineActivity] hosts in a sandboxed WebView. `titan-cli` has no server — it is a terminal tool, so picking it switches to
 * the terminal tab instead of opening a web view.
 */
object Engines {
    data class Spec(
        val id: String,
        val title: String,
        val subtitle: String,
        val portKey: String,
        val healthPath: String,
        val web: Boolean,
        val cmd: (Int) -> List<String>
    )

    val all = listOf(
        Spec("opencode", "OpenCode", "Встроенный агент: чат, файлы, терминал", "", "", false) { emptyList() },
        Spec("hermes", "Hermes Agent", "Nous Research: обучение на опыте, навыки", "port.hermes", "/", true) { p ->
            listOf("/root/.local/bin/hermes", "dashboard", "--no-open", "--skip-build", "--host", "127.0.0.1", "--port", p.toString())
        },
        Spec("titan", "TITAN", "Mission Control: команда агентов, память", "port.titan", "/api/health", true) { p ->
            listOf("/root/.local/titan/bin/titan", "gateway", "--host", "127.0.0.1", "--port", p.toString())
        },
        Spec("titan-cli", "TITAN CLI", "Сжатие токенов: filter, compress, benchmark", "", "", false) { _ ->
            listOf("/root/.local/titancli/bin/titan", "help")
        }
    )

    fun byId(id: String): Spec? = all.firstOrNull { it.id == id }

    fun current(c: Context): String {
        val id = Prefs.get(c, "engine", "opencode")
        return if (byId(id) != null) id else "opencode"
    }

    fun setCurrent(c: Context, id: String) {
        if (byId(id) != null) Prefs.put(c, "engine", id)
    }

    /**
     * A per-engine loopback port that is saved once and re-verified on each start, so the
     * WebView origin (and the dashboard's localStorage) survives restarts.
     */
    fun port(c: Context, spec: Spec): Int {
        val saved = Prefs.get(c, spec.portKey).toIntOrNull()
        if (saved != null && saved in 20000..60999 && bindable(saved)) return saved
        val p = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        Prefs.put(c, spec.portKey, p.toString())
        return p
    }

    private fun bindable(port: Int): Boolean = try {
        ServerSocket().use { ss ->
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
        true
    } catch (_: Exception) { false }

    fun start(c: Context, uid: String, spec: Spec): Process {
        val p = port(c, spec)
        EngineState.port = p
        EngineState.id = spec.id
        EngineState.phase = "starting"
        EngineState.error = null
        EngineState.ready = false
        val log = File(c.filesDir, "engine-${spec.id}.log")
        val proc = Runtime.prootRun(c, uid, spec.cmd(p))
        kotlin.concurrent.thread(isDaemon = true) {
            try { proc.inputStream.copyTo(java.io.FileOutputStream(log, false)) } catch (_: Exception) {}
        }
        return proc
    }

    /** Ready when the engine answers 2xx on its health path (any status < 500 for a SPA shell). */
    fun waitReady(proc: Process?, spec: Spec, port: Int, timeoutMs: Long): Boolean {
        if (!spec.web) return true
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (proc != null && !proc.isAlive) return false
            try {
                val con = URL("http://127.0.0.1:$port${spec.healthPath}").openConnection() as HttpURLConnection
                con.connectTimeout = 800; con.readTimeout = 1500
                val code = con.responseCode
                con.disconnect()
                if (code in 200..399) return true
            } catch (_: Exception) {}
            Thread.sleep(400)
        }
        return false
    }

    fun tailLog(c: Context, id: String, lines: Int = 8): String = try {
        val f = File(c.filesDir, "engine-$id.log")
        if (f.exists()) f.readLines().takeLast(lines).joinToString("\n") else ""
    } catch (_: Exception) { "" }

    /**
     * True when every external engine still has its files. Engines live in HOME, which survives
     * app and core updates, but a user can wipe app data — so the UI asks before claiming they
     * are installed.
     */
    fun installed(c: Context): Boolean {
        val home = Runtime.home(c, Prefs.get(c, "uid"))
        val probes = listOf(
            File(home, ".local/bin/hermes"),
            File(home, ".local/titan/bin/titan"),
            File(home, ".local/titancli/bin/titan")
        )
        return probes.all { it.exists() }
    }

    /** The catalog plus live state, consumed by the UI. */
    fun describe(c: Context): String {
        val arr = JSONArray()
        for (s in all) {
            arr.put(JSONObject()
                .put("id", s.id)
                .put("title", s.title)
                .put("subtitle", s.subtitle)
                .put("web", s.web)
                .put("port", if (s.web) port(c, s) else 0))
        }
        return JSONObject()
            .put("engines", arr)
            .put("current", current(c))
            .put("state", EngineState.json())
            .put("installed", installed(c))
            .toString()
    }
}

/** Live status of the engine process, shared between [EngineService] and the UI. */
object EngineState {
    @Volatile var id = ""
    @Volatile var port = 0
    @Volatile var ready = false
    @Volatile var phase = "idle"
    @Volatile var error: String? = null

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("port", port).put("ready", ready)
        .put("phase", phase).put("error", error ?: "")
}