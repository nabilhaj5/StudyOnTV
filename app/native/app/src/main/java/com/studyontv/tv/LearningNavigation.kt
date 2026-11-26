package com.studyontv.tv

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/** En la ruta de teclas de accesibilidad solo se lee estado inmutable y ya calculado. */
data class LearningExitSnapshot(val canExit: Boolean, val publishedAt: Long)

object LearningNavigation {
    private const val REFRESH_MS = 1000L
    private const val ALLOW_LEASE_MS = 2000L
    @Volatile private var engine: LearningEngine? = null
    private var worker: Handler? = null
    private val refresh = object : Runnable {
        override fun run() {
            try { engine?.status() } catch (e: Exception) {
                android.util.Log.w("StudyOnTV", "Navigation state refresh failed", e)
            } finally { worker?.postDelayed(this, REFRESH_MS) }
        }
    }

    private val noticeHandler = Handler(Looper.getMainLooper())
    private val noticePending = AtomicBoolean(false)
    private var notice: Toast? = null

    /** Compartido por el Atrás del WebView y la ruta de teclas de accesibilidad; el trabajo de UI nunca se ejecuta en onKeyEvent. */
    fun notifyBlocked(context: Context) {
        if (!noticePending.compareAndSet(false, true)) return
        val app = context.applicationContext
        noticeHandler.post {
            noticePending.set(false)
            // Un aviso en cola no debe aparecer tras un desbloqueo ni en una pantalla no relacionada.
            if (!shouldBlockHome()) return@post
            notice?.cancel()
            // Las ventanas Toast del sistema pueden quedar por debajo de nuestras capas a pantalla completa. Se dibuja en la página propietaria.
            if (WatchAccessibilityService.showNavigationBlocked() || GateService.showNavigationBlocked() ||
                (Foreground.resumedActivity() as? MainActivity)?.showNavigationBlocked() == true) return@post
            notice = Toast.makeText(app, R.string.navigation_unavailable, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    /** Dibujado en el hilo principal compartido por el activity y los WebView de las capas; las páginas de entrada nativas usan Toast. */
    fun showBlockedNotice(web: WebView?): Boolean {
        if (web == null || !web.isAttachedToWindow || !web.isShown) return false
        val message = org.json.JSONObject.quote(web.context.getString(R.string.navigation_unavailable))
        return try {
            web.evaluateJavascript("window.SOTV_NAVIGATION_BLOCKED&&window.SOTV_NAVIGATION_BLOCKED($message)", null)
            true
        } catch (_: RuntimeException) { false }
    }

    /** Se llama cuando el runtime pasa a ser dueño de este motor; los motores de prueba aislados nunca publican globalmente. */
    fun start(value: LearningEngine) {
        engine = value
        if (worker == null) {
            val thread = HandlerThread("LearningNavigation").apply { start() }
            worker = Handler(thread.looper)
        }
        worker?.removeCallbacks(refresh)
        worker?.post(refresh)
    }

    val snapshot: LearningExitSnapshot? get() = engine?.exitSnapshot
    fun policyAllowsExit(now: Long = SystemClock.elapsedRealtime()): Boolean = allows(snapshot, now)

    internal fun allows(current: LearningExitSnapshot?, now: Long): Boolean {
        current ?: return false
        // Un refrescador bloqueado no debe dejar abierto indefinidamente un desbloqueo temporal ya caducado.
        return current.canExit && now - current.publishedAt in 0..ALLOW_LEASE_MS
    }

    fun shouldBlockHome(): Boolean {
        val activity = Foreground.resumedActivity()
        val learningSurface = when (activity) {
            is MainActivity -> true
            is ParentActivity -> activity.requiresParentVerification
            null -> GateService.presentedOverlay() || WatchAccessibilityService.presentedGate()
            else -> AnswerInput.owns(activity)
        }
        return learningSurface && !policyAllowsExit()
    }
}

/** Mantener la misma decisión para DOWN, repeticiones y UP, aunque la política cambie a mitad de la pulsación. */
class NavigationKeyFilter(private val blocked: () -> Boolean) {
    private data class Key(val deviceId: Int, val keyCode: Int)
    private val presses = mutableMapOf<Key, Boolean>()

    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME && event.keyCode != KeyEvent.KEYCODE_APP_SWITCH) return false
        val key = Key(event.deviceId, event.keyCode)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Pulsar cualquier tecla puede cambiar downTime, incluidas Atrás/volumen que dejamos pasar.
                // Solo un DOWN nuevo inicia una nueva decisión; las repeticiones la mantienen hasta el UP de esta tecla.
                if (event.repeatCount == 0) blocked().also { presses[key] = it }
                else presses[key] == true
            }
            KeyEvent.ACTION_UP -> presses.remove(key) == true
            else -> false
        }
    }

    fun reset() = presses.clear()
}
