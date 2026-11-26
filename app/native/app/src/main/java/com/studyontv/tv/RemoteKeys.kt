package com.studyontv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Reenvía las teclas de dirección / OK / Atrás del mando a distancia a window.TV de la página de estudio.
 *
 * Entrada oculta de padres: cuando el foco ya está en la parte superior (↑ no puede moverse más), pulsar ↑ brevemente 5 veces seguidas.
 * Antes bastaba con pulsar ↑ 5 veces seguidas para activarla, y era fácil que el niño abriera la entrada de padres por error al subir entre opciones.
 * Cuando la página no ha cargado (JS devuelve null) también se cuenta, para garantizar que siempre se pueda entrar en la entrada de padres.
 */
class RemoteKeys(private val openParent: () -> Unit) {
    private var upCount = 0
    private var lastUp = 0L

    /** @return true indica que la tecla ha sido procesada */
    fun handle(web: WebView?, event: KeyEvent): Boolean {
        val code = event.keyCode
        val js = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "select()"
            KeyEvent.KEYCODE_BACK -> "back()"
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // Mantener pulsado OK / Atrás no dispara repetidamente (en opción múltiple, mantener pulsado marcaría y desmarcaría; el botón de enviar se enviaría repetidamente)
        if (event.repeatCount > 0 && (js == "select()" || js == "back()")) return true
        if (code != KeyEvent.KEYCODE_DPAD_UP && event.repeatCount == 0) upCount = 0
        if (web == null) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) countUp(event)
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            web.evaluateJavascript("window.TV?TV.move('up'):null") { result -> if (result != "true") countUp(event) }
        } else {
            web.evaluateJavascript("window.TV&&TV.$js", null)
        }
        return true
    }

    private fun countUp(event: KeyEvent) {
        if (event.repeatCount > 0) return
        val now = SystemClock.elapsedRealtime()
        upCount = if (now - lastUp < 900) upCount + 1 else 1
        lastUp = now
        if (upCount >= 5) { upCount = 0; openParent() }
    }
}
