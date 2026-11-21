package app.opencodemob

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/**
 * Runs one external engine (Hermes / TITAN) in the background and publishes its state.
 *
 * The built-in core has its own service ([OpenCodeService]); engines are started on demand
 * from Settings → Interfaces and stopped when another engine is chosen or the user asks.
 */
class EngineService : Service() {
    private var proc: Process? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("engine") ?: ""
        val spec = Engines.byId(id)
        if (spec == null || !spec.web) { stopSelf(); return START_NOT_STICKY }
        val uid = intent?.getStringExtra("uid") ?: Prefs.get(this, "uid")
        if (uid.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(
            this, 2, Notifier.runtime(this, "Запуск ${spec.title}"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (worker?.isAlive != true) worker = thread { runLoop(uid, spec) }
        return START_STICKY
    }

    private fun runLoop(uid: String, spec: Engines.Spec) {
        stopping = false
        try {
            val p = Engines.start(this, uid, spec)
            proc = p
            val ok = Engines.waitReady(p, spec, EngineState.port, 180_000)
            if (ok) {
                EngineState.ready = true
                EngineState.phase = "ready"
                Notifier.updateRuntime(this, "${spec.title}: работает")
                p.waitFor()
                EngineState.ready = false
                EngineState.phase = "down"
            } else {
                val alive = p.isAlive
                if (alive) p.destroy()
                EngineState.ready = false
                EngineState.phase = "down"
                EngineState.error = "${spec.title} не запустился" +
                    Engines.tailLog(this, spec.id).let { if (it.isNotEmpty()) ":\n$it" else "" }
            }
        } catch (e: Throwable) {
            EngineState.error = "${spec.title}: ${e.message ?: e}"
            EngineState.phase = "down"
        } finally {
            proc = null
            if (!stopping) {
                Notifier.updateRuntime(this, "${spec.title}: остановлен")
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        super.onDestroy()
    }
}