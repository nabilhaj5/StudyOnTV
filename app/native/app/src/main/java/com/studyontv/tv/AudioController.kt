package com.studyontv.tv

import android.content.Context
import android.os.SystemClock

/**
 * Extremo de ejecución que comparte origen con «silenciar» y «ventana emergente» (issue #110).
 *
 * La decisión en sí es una función pura en [GatePresentation]; aquí solo se ejecuta: solo se permite seguir silenciando
 * si el Gate es realmente visible, y si la ventana emergente falla o se retira se libera inmediatamente, dejando en el
 * registro de diagnóstico «cuánto tiempo estuvo silenciado y por qué no se mostró».
 * Las marcas de tiempo entre ticks se guardan aquí; GateService solo coordina.
 */
object AudioController {
    private var muteMismatchSince = -1L
    private var lastMuteMismatchAt = -1L
    private var lastMuteMismatchLogAt = 0L

    val active: Boolean get() = MediaPause.active
    val mismatchSince: Long get() = muteMismatchSince
    val lastMismatchAt: Long get() = lastMuteMismatchAt

    /** Limpia las marcas de tiempo de la ronda anterior al reconstruirse el servicio (coherente con el ciclo de vida de los campos de instancia originales) */
    fun reset() {
        muteMismatchSince = -1L
        lastMuteMismatchAt = -1L
        lastMuteMismatchLogAt = 0L
    }

    /** Si se permite silenciar: el Gate es visible, o la página de entrada de respuesta está en primer plano */
    fun allowed(gateVisible: Boolean, free: Boolean): Boolean =
        GatePresentation.muteAllowed(gateVisible = gateVisible, inputActive = InputSupport.active, free = free)

    /** La página de estudio es la única página propia que necesita pausar audio y vídeo; otras aplicaciones se cubren con MediaPause.enforce */
    fun syncLearningActivity(free: Boolean, gateExpected: Boolean) {
        if (free) return
        Foreground.learningActivity()?.let { if (gateExpected) MediaPause.pause(it) else MediaPause.release(it) }
    }

    /** El silencio y la ventana emergente no comparten origen: se libera el silencio de inmediato y se deja un registro legible (issue #110) */
    fun releaseWithoutGate(service: Context, source: String, gate: Boolean, visible: Boolean,
                           gateFailures: Int, lastGateError: String) {
        val now = SystemClock.elapsedRealtime()
        if (muteMismatchSince < 0) muteMismatchSince = now
        lastMuteMismatchAt = now
        if (now - lastMuteMismatchLogAt > 5000) {
            lastMuteMismatchLogAt = now
            val age = if (MediaPause.lastPauseAt > 0) (now - MediaPause.lastPauseAt) / 1000 else 0
            Diag.warn("Audio", "mute-without-gate($source): silenciado ${age}s owners=${MediaPause.ownerCount} gate=$gate " +
                "visible=$visible failures=$gateFailures reason=$lastGateError → release")
        }
        MediaPause.releaseAll(service)
        muteMismatchSince = -1L
    }
}
