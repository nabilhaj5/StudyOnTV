package com.studyontv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView

/**
 * Reenvía las teclas de dirección / OK / Atrás del mando a distancia a window.TV en la página de estudio.
 *
 * Entrada oculta para padres: cuando el foco ya está arriba del todo (↑ no puede moverse más)
 * se pulsa ↑ brevemente 5 veces seguidas.
 * Antes bastaba con pulsar ↑ 5 veces seguidas para activarla, y el niño la abría por error
 * fácilmente al subir entre opciones. También se cuenta cuando la página no ha cargado
 * (JS devuelve null), para garantizar que siempre se pueda entrar a la entrada para padres.
 */
class TeclasRemotas(private val abrirPadres: () -> Unit) {
    private var contadorArriba = 0
    private var ultimoArriba = 0L

    /** @return true indica que la tecla ya fue procesada */
    fun manejar(web: WebView?, evento: KeyEvent): Boolean {
        val codigo = evento.keyCode
        val js = when (codigo) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "move('left')"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "move('right')"
            KeyEvent.KEYCODE_DPAD_UP -> "move('up')"
            KeyEvent.KEYCODE_DPAD_DOWN -> "move('down')"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "select()"
            KeyEvent.KEYCODE_BACK -> "back()"
            else -> return false
        }
        if (evento.action != KeyEvent.ACTION_DOWN) return true
        // Mantener pulsado OK / Atrás no dispara repetidamente (en preguntas de opción múltiple
        // se marcaría y desmarcaría, y el botón de enviar se enviaría varias veces)
        if (evento.repeatCount > 0 && (js == "select()" || js == "back()")) return true
        if (codigo != KeyEvent.KEYCODE_DPAD_UP && evento.repeatCount == 0) contadorArriba = 0
        if (web == null) {
            if (codigo == KeyEvent.KEYCODE_DPAD_UP) contarArriba(evento)
            return true
        }
        if (codigo == KeyEvent.KEYCODE_DPAD_UP) {
            web.evaluateJavascript("window.TV?TV.move('up'):null") { resultado -> if (resultado != "true") contarArriba(evento) }
        } else {
            web.evaluateJavascript("window.TV&&TV.$js", null)
        }
        return true
    }

    private fun contarArriba(evento: KeyEvent) {
        if (evento.repeatCount > 0) return
        val ahora = SystemClock.elapsedRealtime()
        contadorArriba = if (ahora - ultimoArriba < 900) contadorArriba + 1 else 1
        ultimoArriba = ahora
        if (contadorArriba >= 5) { contadorArriba = 0; abrirPadres() }
    }
}
