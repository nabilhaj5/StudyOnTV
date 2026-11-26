package com.studyontv.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Activity y capa flotante comparten la misma vía de lectura; si la nube falla, se recurre silenciosamente al sistema.
 *
 * Los fallos ya no son silenciosos (#87 / #106):
 * - `setLanguage` devuelve LANG_MISSING_DATA / LANG_NOT_SUPPORTED: primero se recurre al idioma predeterminado del dispositivo y, si no, se registra diagnóstico y se informa;
 * - onError de `UtteranceProgressListener`: registra diagnóstico, reintenta una vez con el idioma predeterminado; onDone limpia el estado;
 * - El error de MediaPlayer en `playAudio` registra diagnóstico e informa (Toast + puente), no solo release.
 * Se informa por dos vías: `status()` (método puente `speechStatus`) y el callback `notice` (el puente envía SOTV_SPEECH_NOTICE a la web).
 */
object SpeechReader {
    /** Lista blanca de reconocimiento de voz: los idiomas de pregunta fuera de la lista no se tratan silenciosamente como chino, sino que degradan al idioma predeterminado del dispositivo y registran diagnóstico (#81) */
    private val RECOGNITION_LANGUAGES = listOf("zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** Idiomas de lectura que se pueden etiquetar en la pregunta; "" significa seguir el idioma del sistema del dispositivo (la causa raíz de #126: un sistema cantonés leído como mandarín por tener zh-CN fijo) */
    val LANGUAGES = listOf("", "zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** Idiomas admitidos por el reconocimiento de voz (degradación adicional fuera de la lista blanca, para aserciones de pruebas de regresión) */
    val whitelist: List<String> get() = RECOGNITION_LANGUAGES
    /** Sobrescritura de idioma de lectura a nivel de dispositivo: "" sigue la pregunta, "system" sigue el sistema, el resto son etiquetas de idioma fijas */
    private val OVERRIDES = LANGUAGES + listOf("system")

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadExecutor()
    /** La presíntesis del siguiente segmento va a un hilo independiente y nunca bloquea la reproducción PCM del segmento actual en worker. */
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    /**
     * Caché de resultados de síntesis en la nube: el redibujado de la pregunta y el relleno de borradores vuelven a disparar la lectura automática; no hace falta sintetizar dos veces el mismo texto
     * (DeepSeek cobra por carácter, y los usuarios informan «no se oye y sigue cobrando»). Solo se guardan los últimos segmentos.
     */
    private val synthCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 6
    }
    /** El WAV de la nube se reproduce con AudioTrack (MediaPlayer no puede reproducirlo en dispositivos Huawei, según usuarios); stopNow pausa e interrumpe, playPcm finally es el único responsable de liberar */
    private val trackLock = Any()
    @Volatile private var track: AudioTrack? = null
    private var audioFile: File? = null
    private var system: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private val tokenLock = Any()
    @Volatile private var token = ""
    @Volatile private var tokenExpires = 0L
    @Volatile private var tokenKey = ""
    /** Resultado de la última lectura / reproducción, para aserciones del puente speechStatus y pruebas de regresión */
    @Volatile private var last: JSONObject? = null
    @Volatile private var speaking = false
    /** Última vez que la lectura estuvo activa (elapsedRealtime), para que MediaPause.enforce no confunda nuestra propia lectura con vídeo en segundo plano y la silencie (#168) */
    @Volatile private var lastSpokeAt = 0L

    private fun markSpeaking(active: Boolean) {
        speaking = active
        lastSpokeAt = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
    }
    /** Callback de información del puente (solo se mantiene durante una lectura / reproducción; stopNow lo libera para no retener el WebView mucho tiempo) */
    private var notice: ((JSONObject) -> Unit)? = null
    private var cachedContext: Context? = null
    @Volatile private var currentText = ""
    @Volatile private var currentLanguage = ""
    /** Sobrescritura de velocidad para una sola lectura: las preguntas de comprensión auditiva pueden especificarla por pregunta; -1 significa seguir speechRate global */
    @Volatile private var currentRate = -1.0
    @Volatile private var lastUtteranceId = ""
    @Volatile private var retriedDefault = false
    @Volatile private var retriedSpeakError = false
    // Se usa el flujo de medios en lugar del flujo de accesibilidad: en televisiones como Huawei Smart Screen y TCL el flujo de accesibilidad a menudo no tiene salida,
    // y el síntoma es «pulso leer y no hay sonido»; el flujo de medios siempre pasa por los altavoces / HDMI de la TV.
    private val attributes get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** Dos timbres de la voz DeepSeek (síntesis + transcripción), ver https://api-docs.deepseek.com/zh-cn/guides/tts */
    val DEEPSEEK_VOICES = listOf("stella", "tide")
    /** Vía de transcripción de voz: system usa el reconocedor del sistema, deepseek sube a DeepSeek, auto usa DeepSeek si hay Key */
    val RECOGNIZE_MODES = listOf("system", "deepseek", "auto")

    /** Alcance del contenido leído automáticamente: solo enunciado, enunciado y opciones, enunciado opciones y respuesta (la respuesta se lee tras responder, no antes para no destripar) */
    val READ_SCOPES = listOf("question", "questionOptions", "questionOptionsAnswer")

    fun settings(store: LocalStore): JSONObject {
        val raw = JSONObject(store.get("speech_settings", "{}"))
        val ownDeepseek = raw.optString("deepseekKey").trim()
        val effectiveDeepseek = deepseekKey(raw, store)
        return raw.apply {
            if (!has("mode")) put("mode", "auto")
            if (!has("readLanguage")) put("readLanguage", "")
            if (!has("edgeVoice")) put("edgeVoice", "")
            if (!has("speechRate")) put("speechRate", 1.0)
            if (!has("deepseekVoice")) put("deepseekVoice", "stella")
            if (!has("recognizeMode")) put("recognizeMode", "system")
            if (optString("readScope") !in READ_SCOPES) put("readScope", "question")
            for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) { put(key + "Set", optString(key).isNotEmpty()); remove(key) }
            put("deepseekInherited", ownDeepseek.isEmpty() && effectiveDeepseek.isNotEmpty())
            put("deepseekReady", effectiveDeepseek.isNotEmpty())
        }
    }
    fun save(store: LocalStore, input: JSONObject): JSONObject {
        val next = JSONObject(store.get("speech_settings", "{}"))
        val mode = input.optString("mode", "system")
        require(mode in listOf("auto", "system", "edge", "deepseek", "azure", "aliyun")) { "Vía de lectura desconocida" }
        next.put("mode", mode).put("autoRead", input.optBoolean("autoRead"))
        if (input.has("readScope")) {
            val scope = input.optString("readScope", "question")
            require(scope in READ_SCOPES) { "Contenido de lectura desconocido" }
            next.put("readScope", scope)
        }
        if (input.has("recognizeMode")) {
            val recognize = input.optString("recognizeMode", "system")
            require(recognize in RECOGNIZE_MODES) { "Vía de reconocimiento de voz desconocida" }
            next.put("recognizeMode", recognize)
        }
        if (input.has("deepseekVoice")) {
            val voice = input.optString("deepseekVoice").trim().ifEmpty { "stella" }
            require(voice in DEEPSEEK_VOICES) { "Timbrado DeepSeek no admitido" }
            next.put("deepseekVoice", voice)
        }
        // #126: idioma de lectura a nivel de dispositivo. Vacío = seguir la configuración de la pregunta, "system" = seguir el idioma del sistema del dispositivo (un sistema cantonés lee cantonés)
        if (input.has("readLanguage")) {
            val readLanguage = input.optString("readLanguage").trim()
            require(readLanguage in OVERRIDES) { "Idioma de lectura no admitido" }
            next.put("readLanguage", readLanguage)
        }
        if (input.has("edgeVoice")) {
            val edgeVoice = input.optString("edgeVoice").trim()
            require(edgeVoice.isEmpty() || edgeVoice in EdgeTts.VOICES) { "Timbrado Edge no admitido" }
            next.put("edgeVoice", edgeVoice)
        }
        if (input.has("speechRate")) {
            val rate = input.optDouble("speechRate", 1.0)
            require(rate.isFinite() && rate in 0.7..1.5) { "La velocidad de lectura debe estar entre 0.7 y 1.5" }
            next.put("speechRate", rate)
        }
        for (key in listOf("azureRegion", "azureVoice", "aliyunAppKey", "aliyunAccessKeyId", "aliyunVoice")) if (input.has(key)) {
            require(input.getString(key).length <= 200); next.put(key, input.getString(key).trim())
        }
        require(next.optString("azureRegion").matches(Regex("[a-z0-9-]*"))) { "Formato de región de Azure incorrecto" }
        applySpeechKeyMutations(next, input)
        // Al rellenar la Key de DeepSeek en lectura, si en «API del modelo» está seleccionado DeepSeek y aún no hay Key, se sincroniza automáticamente para que el padre no la rellene dos veces
        val newDeepseekKey = input.optString("deepseekKey").trim()
        if (newDeepseekKey.isNotEmpty()) {
            try {
                val grading = JSONObject(store.get("grading_settings", "{}"))
                val provider = grading.optString("provider", "deepseek")
                if (provider == "deepseek" && grading.optString("apiKey").trim().isEmpty()) {
                    grading.put("provider", "deepseek").put("apiKey", newDeepseekKey)
                    store.set("grading_settings", grading.toString())
                }
            } catch (_: Exception) { }
        }
        store.set("speech_settings", next.toString())
        synchronized(tokenLock) { token = ""; tokenExpires = 0L; tokenKey = "" }
        synchronized(synthCache) { synthCache.clear() }
        return settings(store)
    }

    /**
     * Lectura. `language` es el idioma de la pregunta; si el motor no lo admite, primero se recurre al idioma predeterminado del dispositivo y, si tampoco, se informa del fallo.
     * `notice` lo pasa el puente y se invoca en caso de fallo (si en la web existe window.SOTV_SPEECH_NOTICE se puede mostrar el aviso).
     * `rate` es la velocidad de esta lectura (listenRate de comprensión auditiva); -1 significa usar la velocidad global de la configuración de lectura.
     */
    fun read(context: Context, text: String, language: String = "", notice: ((JSONObject) -> Unit)? = null, rate: Double = -1.0) {
        if (text.isBlank()) return
        val app = context.applicationContext
        main.post {
            val config = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Throwable) { JSONObject() }
            // Las preguntas de comprensión auditiva especifican velocidad por pregunta: se escribe en la copia de configuración de esta lectura; la síntesis en la nube y la clave de caché usarán esta velocidad
            currentRate = if (rate.isFinite() && rate in 0.7..1.5) rate else -1.0
            if (currentRate > 0) config.put("speechRate", currentRate)
            val mode = config.optString("mode", "auto")
            val chosen = when {
                LocalOnlyGuard.enabled -> "system"
                mode == "auto" -> when {
                    deepseekKey(config).isNotEmpty() -> "deepseek"
                    config.optString("azureKey").isNotEmpty() -> "azure"
                    config.optString("aliyunSecret").isNotEmpty() -> "aliyun"
                    TtsProbe.defaultEngine(app).isNotEmpty() -> "system"
                    else -> "edge"
                }
                // Si la TV no tiene motor de voz integrado y el padre ya configuró una Key de DeepSeek en «API del modelo», se usa DeepSeek como respaldo automático para la lectura
                mode == "system" && TtsProbe.defaultEngine(app).isEmpty() && deepseekKey(config).isNotEmpty() -> "deepseek"
                else -> mode
            }
            // El TTS del sistema usa QUEUE_FLUSH para cubrir la locución anterior, evitando que en algunos firmwares (como OPPO) llamadas consecutivas a system.stop() + speak() hagan que las preguntas siguientes se descarten silenciosamente
            stopNow(stopSystem = chosen != "system")
            val request = generation
            this.notice = notice
            cachedContext = app
            val clean = safeTruncate(text, 10000)
            // #126: el padre puede especificar el idioma de lectura del dispositivo en la configuración de lectura; si se deja vacío se sigue la etiqueta de idioma de cada pregunta
            val spoken = when (val override = config.optString("readLanguage").trim()) {
                "system" -> ""          // Seguir el idioma del sistema del dispositivo
                "" -> language          // Seguir la pregunta
                else -> override
            }
            if (chosen == "system") systemRead(app, clean, spoken, request)
            else cloudPart(app, cloudChunks(clean, chosen), 0, spoken, config, chosen, request)
        }
    }

    /** Velocidad real usada en esta lectura: la especificada por la pregunta de comprensión auditiva tiene prioridad; si no, el valor global de la configuración de lectura */
    private fun rateOf(config: JSONObject): Double {
        val raw = if (currentRate in 0.7..1.5) currentRate else config.optDouble("speechRate", 1.0)
        return raw.takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
    }

    /** Audio de la pregunta. Cuando la compatibilidad https / mp3 de televisiones antiguas es mala ya no «pulso y no pasa nada»: registra diagnóstico + Toast + informa al frontend */
    fun playAudio(context: Context, url: String, notice: ((JSONObject) -> Unit)? = null) {
        val app = context.applicationContext
        // La dirección no es válida: no se encola al hilo principal, se informa sincrónicamente (las pruebas de regresión pueden asertar status())
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            recordFailure("audio", "unsupported-url", "", "", false, "Dirección de audio incorrecta, no se puede reproducir", notice)
            return
        }
        if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(url)) {
            recordFailure("audio", "local-only-blocked", "", "", false, "La versión de privacidad solo local solo permite reproducir audio del equipo o de la red local", notice)
            return
        }
        main.post {
            stopNow(); val request = generation
            this.notice = notice
            cachedContext = app
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes); setDataSource(url)
                    setOnPreparedListener { if (request == generation) { markSpeaking(true); start() } else release() }
                    setOnCompletionListener {
                        release(); if (request == generation) { markSpeaking(false); player = null; notify(true, "audio", "done", "", "", false, "") }
                    }
                    setOnErrorListener { _, what, extra ->
                        release(); if (request == generation) { markSpeaking(false); player = null }
                        audioFailed(app, request, "player-error", "No se puede reproducir el audio (what=$what extra=$extra), comprueba la red o usa la lectura")
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                player?.release(); player = null
                audioFailed(app, request, e.javaClass.simpleName, "No se puede reproducir el audio (${e.javaClass.simpleName} ${e.message?.take(80).orEmpty()}), comprueba la red o usa la lectura")
            }
        }
    }

    fun stop() { main.post { stopNow() } }

    /** Fallos que deben mostrarse directamente en la pantalla de la TV (no solo escribir diagnóstico): en televisiones antiguas «pulso leer y no hay sonido» suele ser uno de estos */
    private val TOAST_REASONS = setOf("engine-unavailable", "language-unavailable", "language-check-failed", "utterance-error", "cloud-unavailable")

    /** Resultado de la lectura actual/última: {ok,mode,requested,language,degraded,reason,message,at}; el método puente speechStatus lo devuelve directamente */
    fun status(): JSONObject = last?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("ok", true).put("mode", "").put("requested", "").put("language", "").put("degraded", false)
        .put("reason", "idle").put("message", "").put("at", 0L)

    fun speaking(): Boolean = speaking

    /** Está leyendo o acaba de terminar hace poco (Android AudioManager.isMusicActive aún persiste 1–2 segundos tras parar) */
    fun speakingRecently(graceMs: Long = 2500L): Boolean {
        if (speaking) return true
        val at = lastSpokeAt
        if (at <= 0L) return false
        val now = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { return false }
        return now - at < graceMs
    }

    /** Estructura de información de fallo (función pura, las pruebas de regresión pueden asertar la estructura directamente; no contiene texto plano de pregunta / respuesta) */
    fun failureNotice(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String): JSONObject =
        JSONObject().put("ok", false).put("mode", mode).put("requested", requested).put("language", language)
            .put("degraded", degraded).put("reason", reason).put("message", message).put("at", System.currentTimeMillis())

    /**
     * Normalización de etiquetas BCP47: etiquetas con extensión de escritura como `zh-Hans-CN` / `zh-Hant-TW`, el `setLanguage` de algunos motores TTS
     * devuelve directamente LANG_MISSING_DATA, y el síntoma es «tiene el paquete de voz chino instalado pero no lee» (#128).
     */
    fun normalizeLanguageTag(raw: String): String {
        val tag = raw.trim()
        return when {
            tag.isEmpty() -> "zh-CN"
            tag.startsWith("zh-Hans") || tag == "zh" || tag == "zh-CN" -> "zh-CN"
            tag.startsWith("zh-Hant-TW") || tag == "zh-TW" -> "zh-TW"
            tag.startsWith("zh-Hant-HK") || tag == "zh-HK" || tag.startsWith("zh-Hant-MO") -> "zh-HK"
            tag.startsWith("en-US") || tag == "en" -> "en-US"
            tag.startsWith("en-GB") || tag.startsWith("en-") -> "en-GB"
            else -> tag
        }
    }

    /** Idioma predeterminado del dispositivo (objetivo de recurso cuando falta el idioma TTS), ya normalizado a BCP47 */
    fun defaultLanguage(): String = normalizeLanguageTag(Locale.getDefault().toLanguageTag())

    /** Resultado de recurso de idioma: degraded=true indica que el idioma solicitado no está disponible y se ha cambiado por otro (o fallo total, accepted=false) */
    data class LanguagePlan(val requested: String, val language: String, val accepted: Boolean, val degraded: Boolean, val reason: String)

    /**
     * Recurso de idioma de voz del sistema (función pura, no toca el motor, instrumentation puede asertar directamente).
     *
     * `available(tag)` lo proporciona el llamador: en escenario real es el valor devuelto por `system.setLanguage(Locale.forLanguageTag(tag))`
     * (constantes TextToSpeech.LANG_*); devolver null significa «no se puede determinar» (motor no listo / implementación antigua) y se trata como disponible.
     */
    fun resolveLanguage(requested: String, fallback: String = defaultLanguage(), available: (String) -> Int?): LanguagePlan {
        val want = requested.trim()
        val primary = want.ifEmpty { fallback }
        val status = ask(available, primary)
        if (status == null || status >= TextToSpeech.LANG_AVAILABLE) return LanguagePlan(want, primary, true, false, "available")
        if (primary == fallback) return LanguagePlan(want, primary, false, true, "unavailable")
        val fallbackStatus = ask(available, fallback)
        if (fallbackStatus == null || fallbackStatus >= TextToSpeech.LANG_AVAILABLE) return LanguagePlan(want, fallback, true, true, "fallback")
        return LanguagePlan(want, primary, false, true, "unavailable")
    }

    /** Si available lanza excepción (motor reciclado / aún no listo) se trata como «no se puede determinar» y no se propaga la excepción al llamador */
    private fun ask(available: (String) -> Int?, tag: String): Int? = try { available(tag) } catch (_: Exception) { null }

    /** Idioma de reconocimiento de voz: dentro de la lista blanca se usa tal cual; fuera de ella se degrada al idioma predeterminado del dispositivo y se registra un diagnóstico (ya no se cambia silenciosamente a chino) */
    fun recognitionLanguage(requested: String): String {
        val want = requested.trim()
        // Pregunta sin idioma etiquetado: seguir el idioma del sistema del dispositivo (un niño con sistema cantonés / tradicional reconoce en cantonés), y si no se admite usar chino
        if (want.isEmpty()) return defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        if (want in RECOGNITION_LANGUAGES) return want
        val fallback = defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        Diag.warn("Speech", "recognition language $want not supported, degraded to $fallback")
        return fallback
    }

    internal fun currentGeneration(): Int = generation

    internal fun bumpGeneration(): Int {
        pending = null
        notice = null
        return ++generation
    }

    internal fun notify(
        ok: Boolean,
        mode: String,
        reason: String,
        requested: String,
        language: String,
        degraded: Boolean,
        message: String,
        request: Int? = null
    ) {
        if (request != null && request != generation) return
        val payload = failureNotice(mode, reason, requested, language, degraded, message).put("ok", ok)
        last = payload
        // Éxito solo actualiza estado; solo los fallos escriben diagnóstico + envían a la web (evitar que una lectura normal se trate como aviso)
        if (ok) return
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        // Si en la página no existe SOTV_SPEECH_NOTICE (web antigua), al menos que aparezca un aviso en la pantalla de la TV, no «pulso y no pasa nada»
        if (reason in TOAST_REASONS) main.post {
            if (request != null && request != generation) return@post
            try { cachedContext?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } } catch (_: Exception) { }
        }
        val callback = notice ?: return
        val copy = JSONObject(payload.toString())
        main.post {
            if (request != null && request != generation) return@post
            try { callback(copy) } catch (_: Exception) { }
        }
    }

    /** Informe de fallo: escribe estado + diagnóstico e invoca el callback del puente (se puede llamar desde cualquier hilo) */
    private fun recordFailure(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String, callback: ((JSONObject) -> Unit)?) {
        val payload = failureNotice(mode, reason, requested, language, degraded, message)
        last = payload
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        try { callback?.invoke(JSONObject(payload.toString())) } catch (_: Exception) { }
    }

    private fun audioFailed(context: Context, request: Int, reason: String, message: String) {
        if (request != generation) return
        markSpeaking(false)
        notify(false, "audio", reason, "", "", false, message)
        try { Toast.makeText(context, "No se puede reproducir el audio, comprueba la red o usa la lectura", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
    }

    private fun stopNow(stopSystem: Boolean = true) {
        generation++; pending = null
        if (stopSystem) system?.stop()
        synchronized(trackLock) {
            track?.let {
                try { it.pause(); it.flush() } catch (_: Exception) { }
            }
        }
        player?.release(); player = null; audioFile?.delete(); audioFile = null
        markSpeaking(false); currentText = ""; currentLanguage = ""; lastUtteranceId = ""; retriedDefault = false; retriedSpeakError = false
        notice = null
    }

    /** UtteranceProgressListener: al terminar la lectura limpia el estado; si falla la síntesis registra diagnóstico y reintenta una vez con el idioma predeterminado */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { markSpeaking(true) }
        override fun onDone(utteranceId: String?) {
            if (utteranceId != lastUtteranceId) return
            markSpeaking(false); currentText = ""; currentLanguage = ""; lastUtteranceId = ""
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String?, errorCode: Int) {
            Diag.warn("Speech", "utterance error id=$utteranceId code=$errorCode language=${currentLanguage.ifEmpty { "-" }}")
            val request = utteranceId?.split("-")?.getOrNull(1)?.toIntOrNull() ?: return
            if (request != generation) return
            markSpeaking(false)
            val text = currentText; val context = cachedContext
            val fallback = defaultLanguage()
            if (!retriedDefault && context != null && text.isNotEmpty() && currentLanguage != fallback) {
                retriedDefault = true
                Diag.warn("Speech", "retry reading with default language $fallback (was ${currentLanguage.ifEmpty { "-" }})")
                main.post { speakSystem(context, text, fallback, request, "system") }
                return
            }
            notify(false, "system", "utterance-error", currentLanguage, currentLanguage, true,
                "Fallo al reproducir la voz del sistema (code=$errorCode), puedes cambiar de idioma o usar voz en la nube")
        }
    }

    private fun systemRead(context: Context, text: String, language: String, request: Int, mode: String = "system", cloudError: String = "") {
        if (request != generation) return
        cachedContext = context.applicationContext
        val speak = { if (request == generation) speakSystem(context, text, language, request, mode, cloudError) }
        if (ready) speak()
        else {
            pending = speak
            if (system == null) system = TextToSpeech(context) { status ->
                main.post {
                    ready = status == TextToSpeech.SUCCESS
                    val action = pending; pending = null
                    if (ready) system?.setOnUtteranceProgressListener(progress)
                    else { system?.shutdown(); system = null; Diag.warn("Speech", "tts init failed status=$status") }
                    // Si falla la inicialización, speakSystem verá system==null e informará «voz del sistema no disponible»
                    action?.invoke()
                }
            }
        }
    }

    private fun speakSystem(context: Context, text: String, language: String, request: Int, mode: String, cloudError: String = "") {
        val engine = system
        if (engine == null) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError (y el equipo no tiene voz del sistema integrada)"
            } else {
                "Esta TV no tiene voz del sistema integrada y no puede leer; el padre puede rellenar la Key de DeepSeek en el panel «API del modelo» para usar automáticamente la voz en la nube"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "engine-unavailable", language, "", cloudError.isNotEmpty(), msg)
            return
        }
        val plan = try {
            resolveLanguage(language) { tag -> try { engine.setLanguage(Locale.forLanguageTag(tag)) } catch (_: Exception) { null } }
        } catch (e: Exception) {
            Diag.warn("Speech", "setLanguage failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            notify(false, mode, "language-check-failed", language, "", false, cloudError.ifEmpty { "Voz del sistema no disponible (${e.javaClass.simpleName})" })
            return
        }
        if (plan.reason == "fallback") Diag.info("Speech", "language ${plan.requested} unavailable, fell back to ${plan.language}")
        if (!plan.accepted) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError (y la voz del sistema no admite ${plan.requested})"
            } else {
                "La voz del sistema de este dispositivo no admite ${plan.requested}; instala un paquete de voz o configura la voz DeepSeek e inténtalo de nuevo"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "language-unavailable", plan.requested, plan.language, true, msg)
            return
        }
        try { engine.setAudioAttributes(attributes) } catch (_: Exception) { }
        try {
            val rate = rateOf(currentSettings())
            engine.setSpeechRate(rate.toFloat())
        } catch (_: Exception) { }
        currentText = text; currentLanguage = plan.language
        if (plan.degraded) retriedDefault = true
        // Se acumulan por frases hasta 180 caracteres antes de encolar la lectura: el motor TTS integrado de la TV suele cortarse a mitad con frases largas de miles de caracteres (usuarios informan «lectura incompleta»),
        // dividir en frases cortas encadenadas con QUEUE_ADD es más estable y las pausas entre frases suenan más naturales.
        val max = minOf(180, TextToSpeech.getMaxSpeechInputLength())
        val parts = packedSentences(text, max).ifEmpty { listOf(text) }
        lastUtteranceId = "sotv-$request-${parts.size - 1}"
        parts.forEachIndexed { index, part ->
            val rc = engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "sotv-$request-$index")
            if (rc == TextToSpeech.ERROR) {
                Diag.warn("Speech", "engine.speak returned ERROR on part $index, reinitializing TTS")
                try { engine.shutdown() } catch (_: Exception) { }
                system = null; ready = false
                if (!retriedSpeakError) {
                    retriedSpeakError = true
                    systemRead(context, text, language, request, mode, cloudError)
                } else {
                    notify(false, mode, "speak-error", plan.requested, plan.language, plan.degraded,
                        "El motor de voz del sistema no responde, inténtalo de nuevo o usa voz en la nube")
                }
                return
            }
        }
        markSpeaking(true)
        notify(true, mode, if (plan.degraded) "language-fallback" else "ok", plan.requested.ifEmpty { plan.language }, plan.language, plan.degraded, "")
    }

    private fun currentSettings(): JSONObject = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }

    internal fun safeTruncate(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    internal fun chunkCodePointSafe(text: String, limit: Int): List<String> {
        if (text.isEmpty() || limit <= 0) return if (text.isEmpty()) emptyList() else listOf(text)
        val out = ArrayList<String>()
        var pos = 0
        while (pos < text.length) {
            var end = minOf(pos + limit, text.length)
            if (end < text.length && end > pos &&
                Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])
            ) {
                if (end - 1 > pos) end-- else end++
            }
            out.add(text.substring(pos, end))
            pos = end
        }
        return out
    }

    /**
     * Segmentación de síntesis en la nube. DeepSeek lee como máximo 10 000 caracteres de una vez; se acumulan por frases hasta 400 caracteres antes de solicitar, evitando dividir una pregunta larga en más de una decena de segmentos;
     * otras vías dividen de forma segura por pares sustitutos cada 150 unidades de código, sin partir nunca emojis ni símbolos matemáticos Unicode.
     */
    fun cloudChunks(text: String, mode: String = "azure"): List<String> =
        if (mode == "deepseek") packedSentences(text, 400) else chunkCodePointSafe(text, 150)

    private val SENTENCE_ENDS = "。！？!?；;\n"

    private fun packedSentences(text: String, limit: Int): List<String> {
        val pieces = ArrayList<String>(); val current = StringBuilder()
        fun flush() { val piece = current.toString().trim(); if (piece.isNotEmpty()) pieces.add(piece); current.setLength(0) }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                current.append(ch).append(text[i + 1])
                i += 2
                if (current.length >= limit) flush()
            } else {
                current.append(ch)
                i++
                if (ch in SENTENCE_ENDS || current.length >= limit) flush()
            }
        }
        flush()
        val packed = ArrayList<String>(); val buffer = StringBuilder()
        for (piece in pieces) {
            if (buffer.isNotEmpty() && buffer.length + piece.length > limit) { packed.add(buffer.toString()); buffer.setLength(0) }
            buffer.append(piece)
        }
        if (buffer.isNotEmpty()) packed.add(buffer.toString())
        return packed.ifEmpty { listOf(text) }
    }

    internal fun resolveDeepseekKey(config: JSONObject, gradingJsonProvider: () -> String): String {
        val own = config.optString("deepseekKey").trim()
        if (own.isNotEmpty()) return own
        if (!config.optBoolean("reuseGradingKey", true)) return ""
        return try {
            val grading = JSONObject(gradingJsonProvider())
            val provider = grading.optString("provider", "deepseek").trim()
            val endpoint = grading.optString("endpoint", "https://api.deepseek.com/v1").trim()
            if (provider == "deepseek" || endpoint.contains("api.deepseek.com", ignoreCase = true)) {
                grading.optString("apiKey").trim()
            } else ""
        } catch (_: Exception) { "" }
    }

    /**
     * Key de DeepSeek: si está rellenada en la configuración de lectura se usa; si no, se reutiliza automáticamente la Key del mismo proveedor (DeepSeek) en «API del modelo»,
     * para que el padre no tenga que rellenarla otra vez solo para la lectura.
     */
    fun deepseekKey(config: JSONObject = currentSettings(), store: LocalStore? = null): String =
        resolveDeepseekKey(config) { (store ?: DeviceRuntime.store).get("grading_settings", "{}") }

    internal fun resolveRecognizeKey(localOnly: Boolean, speechJson: String, gradingJsonProvider: () -> String): String {
        if (localOnly) return ""
        val config = try { JSONObject(speechJson) } catch (_: Exception) { JSONObject() }
        return if (config.optString("recognizeMode", "system") == "system") "" else resolveDeepseekKey(config, gradingJsonProvider)
    }

    /** Key de DeepSeek para la transcripción de voz; cadena vacía significa seguir usando el reconocedor del sistema (a menudo inexistente en TV) */
    fun recognizeKey(store: LocalStore): String =
        resolveRecognizeKey(LocalOnlyGuard.enabled, store.get("speech_settings", "{}")) { store.get("grading_settings", "{}") }

    /** Síntesis de voz DeepSeek: devuelve bytes wav (métricas en https://api-docs.deepseek.com/zh-cn/guides/tts) */
    private fun deepseekBytes(config: JSONObject, text: String, language: String): ByteArray {
        val key = deepseekKey(config)
        require(key.isNotEmpty()) { "Aún no se ha rellenado la API Key de DeepSeek" }
        // La interfaz no tiene parámetro de idioma; el idioma lo determina automáticamente el texto; solo se pasan timbre y formato
        val voice = config.optString("deepseekVoice").trim().takeIf { it in DEEPSEEK_VOICES } ?: "stella"
        val body = JSONObject().put("text", text).put("voice_id", voice).put("format", "wav").toString()
        return postBytes("https://api.deepseek.com/v1/tools/tts", key, body, "síntesis de voz")
    }

    /** Primero se limpian las claves antiguas y luego se escriben las nuevas enviadas explícitamente en esta operación, asegurando que clearKeys y la nueva clave enviadas juntas sean idempotentes y consistentes. */
    internal fun applySpeechKeyMutations(next: JSONObject, input: JSONObject) {
        if (input.optBoolean("clearKeys")) {
            next.remove("azureKey")
            next.remove("aliyunSecret")
            next.remove("deepseekKey")
        }
        for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) {
            if (input.has(key) && input.optString(key).isNotBlank()) {
                val raw = input.getString(key)
                require(raw.length <= 500)
                next.put(key, raw.trim())
            }
        }
    }

    internal fun requireCloudHttpAllowed(what: String, localOnly: Boolean = LocalOnlyGuard.enabled) {
        if (localOnly) throw IllegalStateException("La versión de privacidad solo local (EU Local-Only) ha desactivado la $what en la nube")
    }

    internal fun openCloudConnection(url: String, what: String, localOnly: Boolean = LocalOnlyGuard.enabled): HttpURLConnection {
        requireCloudHttpAllowed(what, localOnly)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
        }
    }

    private fun postBytes(url: String, key: String, body: String, what: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = openCloudConnection(url, what)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = try { connection.errorStream?.use { input -> String(input.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null }
                throw IllegalStateException(formatDeepSeekError(what, code, detail))
            }
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    out.write(buffer, 0, size)
                    require(out.size() <= 4_000_000) { "Los datos devueltos por DeepSeek $what son demasiado grandes" }
                }
                out.toByteArray().also { require(it.size > 44) { "Los datos devueltos por DeepSeek $what están vacíos" } }
            }
        } finally { connection.disconnect() }
    }

    internal fun formatDeepSeekError(what: String, code: Int, rawDetail: String?): String {
        val parsed = try {
            val j = JSONObject(rawDetail.orEmpty())
            j.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { j.optString("message").orEmpty() }
                .ifBlank { j.optString("error").orEmpty() }
        } catch (_: Exception) { rawDetail.orEmpty().trim() }
        val lower = parsed.lowercase(Locale.US)
        val friendly = when {
            code == 402 || lower.contains("insufficient balance") || lower.contains("insufficient_quota") || lower.contains("quota") ->
                "Saldo insuficiente en la cuenta DeepSeek; recarga primero en la plataforma abierta de DeepSeek"
            code == 401 || code == 403 || lower.contains("invalid_api_key") || lower.contains("authentication") ->
                "API Key de DeepSeek no válida o sin permiso (se requiere una Key oficial de DeepSeek)"
            code == 404 ->
                "La interfaz actual no admite $what de DeepSeek (se requiere API Key oficial; no se admiten estaciones de retransmisión de terceros)"
            code == 413 ->
                "Contenido demasiado largo, supera el límite por solicitud de $what de DeepSeek"
            code == 429 || lower.contains("rate limit") || lower.contains("too many requests") ->
                "Solicitudes a DeepSeek demasiado frecuentes, inténtalo más tarde"
            code in listOf(500, 502, 503, 504) || lower.contains("overloaded") || lower.contains("unavailable") ->
                "El servicio de DeepSeek está temporalmente ocupado, inténtalo más tarde"
            else -> null
        }
        if (friendly != null) return friendly
        val extra = parsed.take(120).let { if (it.isNotEmpty()) "：$it" else "" }
        return "Fallo de $what de DeepSeek (HTTP $code)$extra"
    }

    /**
     * Reconocimiento de voz DeepSeek: sube WAV PCM de 16 bits y devuelve el texto reconocido.
     * La interfaz limita el audio a ≤8 MiB y ≤120 segundos, y reconoce el segmento completo de una vez (https://api-docs.deepseek.com/zh-cn/guides/asr).
     * Bloquea; el llamador debe ponerlo en un hilo en segundo plano.
     */
    fun transcribe(key: String, wav: ByteArray): String {
        requireCloudHttpAllowed("reconocimiento de voz")
        require(key.isNotEmpty()) { "Aún no se ha rellenado la API Key de DeepSeek" }
        require(wav.size > 44) { "No se ha grabado sonido" }
        require(wav.size <= 8 * 1024 * 1024) { "Grabación demasiado larga, habla por segmentos" }
        val boundary = "----sotv" + UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"answer.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val connection = openCloudConnection("https://api.deepseek.com/v1/tools/asr", "reconocimiento de voz")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(head.size + wav.size + tail.size)
            connection.outputStream.use { it.write(head); it.write(wav); it.write(tail) }
            val code = connection.responseCode
            val body = try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            } catch (_: Exception) { "" }
            if (code !in 200..299) throw IllegalStateException(formatDeepSeekError("reconocimiento de voz", code, body))
            return JSONObject(body).optString("text").trim().also { require(it.isNotEmpty()) { "No se ha oído bien, repítelo" } }
        } finally { connection.disconnect() }
    }

    private val diskLock = Any()

    internal fun isValidCachedAudio(mode: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return when (mode) {
            "deepseek" -> bytes.size > 44 && WavAudio.isWave(bytes)
            else -> {
                if (bytes.size <= 128) return false
                val head = firstNonWhitespaceByte(bytes) ?: return false
                if (head == '{'.code || head == '<'.code || head == '['.code) return false
                hasMp3Signature(bytes)
            }
        }
    }

    private fun firstNonWhitespaceByte(bytes: ByteArray): Int? {
        val limit = minOf(bytes.size, 64)
        for (i in 0 until limit) {
            val b = bytes[i].toInt() and 0xFF
            if (b != ' '.code && b != '\t'.code && b != '\r'.code && b != '\n'.code) return b
        }
        return null
    }

    private fun hasMp3Signature(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return true
        }
        val scanLimit = minOf(bytes.size - 1, 64)
        for (i in 0 until scanLimit) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                return true
            }
        }
        return false
    }

    private fun diskFileInDir(dir: File, cacheKey: String): File? {
        if (cacheKey.isEmpty()) return null
        if (!dir.exists()) dir.mkdirs()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.bin")
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "tts_cache")

    internal fun readDiskCacheFromDir(dir: File, mode: String, cacheKey: String): ByteArray? = synchronized(diskLock) {
        val f = diskFileInDir(dir, cacheKey) ?: return null
        if (!f.exists()) return null
        val data = try { f.readBytes() } catch (_: Exception) { null }
        if (data == null || !isValidCachedAudio(mode, data)) {
            try { f.delete() } catch (_: Exception) { }
            return null
        }
        f.setLastModified(System.currentTimeMillis())
        return data
    }

    internal fun writeDiskCacheToDir(dir: File, cacheKey: String, bytes: ByteArray) {
        synchronized(diskLock) {
            if (cacheKey.isEmpty() || bytes.size > 512 * 1024) return
            val f = diskFileInDir(dir, cacheKey) ?: return
            val tmp = File(f.parentFile, "${f.name}.tmp")
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    if (f.exists()) f.delete()
                    if (!tmp.renameTo(f)) {
                        tmp.delete()
                        return
                    }
                }
                val parent = f.parentFile ?: return
                val files = parent.listFiles { file -> file.isFile && file.name.endsWith(".bin") } ?: return
                var total = files.sumOf { it.length() }
                if (total > 20L * 1024 * 1024) {
                    for (old in files.sortedBy { it.lastModified() }) {
                        if (total <= 16L * 1024 * 1024) break
                        val len = old.length()
                        if (old.delete()) total -= len
                    }
                }
            } catch (_: Exception) {
                try { tmp.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun readDiskCache(context: Context, mode: String, cacheKey: String): ByteArray? =
        readDiskCacheFromDir(cacheDir(context), mode, cacheKey)

    private fun writeDiskCache(context: Context, cacheKey: String, bytes: ByteArray) =
        writeDiskCacheToDir(cacheDir(context), cacheKey, bytes)

    private fun cacheKeyFor(mode: String, config: JSONObject, language: String, part: String): String {
        val rate = config.optDouble("speechRate", 1.0).takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
        return when (mode) {
            "deepseek" -> "${deepseekKey(config).hashCode()}|" + config.optString("deepseekVoice") + "|" + part
            "edge" -> "edge|${config.optString("edgeVoice")}|$language|$rate|$part"
            else -> ""
        }
    }
    internal fun synthWithCacheCore(
        cacheDir: File,
        mode: String,
        cacheKey: String,
        synthesize: () -> ByteArray
    ): ByteArray {
        if (cacheKey.isNotEmpty()) {
            synchronized(synthCache) { synthCache[cacheKey] }?.let { cached ->
                if (isValidCachedAudio(mode, cached)) return cached
                synchronized(synthCache) { synthCache.remove(cacheKey) }
            }
            readDiskCacheFromDir(cacheDir, mode, cacheKey)?.let {
                synchronized(synthCache) { synthCache[cacheKey] = it }
                return it
            }
        }
        val bytes = synthesize()
        if (!isValidCachedAudio(mode, bytes)) {
            throw IllegalStateException("La lectura en la nube no ha devuelto audio: ${bodySnippet(bytes)}")
        }
        if (cacheKey.isNotEmpty() && bytes.size <= 512 * 1024) {
            synchronized(synthCache) { synthCache[cacheKey] = bytes }
            writeDiskCacheToDir(cacheDir, cacheKey, bytes)
        }
        return bytes
    }

    private fun synthWithCache(context: Context, mode: String, config: JSONObject, language: String, part: String): ByteArray {
        val cacheKey = cacheKeyFor(mode, config, language, part)
        val rate = config.optDouble("speechRate", 1.0).takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
        return synthWithCacheCore(cacheDir(context), mode, cacheKey) {
            when (mode) {
                "deepseek" -> deepseekBytes(config, part, language)
                "edge" -> EdgeTts.synthesize(part, config.optString("edgeVoice"), language, rate)
                "azure" -> azure(config, part, language)
                else -> aliyun(config, part)
            }
        }
    }

    private fun cloudPart(context: Context, parts: List<String>, index: Int, language: String, config: JSONObject, mode: String, request: Int) {
        if (index >= parts.size || generation != request) return
        worker.execute {
            if (generation != request) return@execute
            try {
                val bytes = synthWithCache(context, mode, config, language, parts[index])
                if (index + 1 < parts.size && mode in listOf("edge", "deepseek")) {
                    prefetchWorker.execute {
                        if (generation == request) try { synthWithCache(context, mode, config, language, parts[index + 1]) } catch (_: Exception) { }
                    }
                }
                // DeepSeek devuelve WAV: se analiza el PCM y se reproduce directamente en memoria con AudioTrack, sin escribir archivos temporales,
                // evitando el "Prepare failed.: status=0x1" de MediaPlayer en dispositivos Huawei y similares
                val pcm = if (mode == "deepseek") WavAudio.parse(bytes) else null
                if (mode == "deepseek" && pcm == null && !WavAudio.isWave(bytes)) {
                    throw IllegalStateException("La lectura en la nube no ha devuelto audio: ${bodySnippet(bytes)}")
                }
                if (pcm != null) {
                    main.post {
                        if (generation != request) return@post
                        playPcm(pcm, request,
                            onStarted = if (index == 0) {
                                { notify(true, mode, "ok", language, language, false, "", request) }
                            } else null,
                            onDone = { cloudPart(context, parts, index + 1, language, config, mode, request) },
                            onError = {
                                systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                            })
                    }
                    return@execute
                }
                val file = File.createTempFile("reading-", if (mode == "deepseek") ".wav" else ".mp3", context.cacheDir)
                file.writeBytes(bytes)
                main.post {
                    if (generation != request) { file.delete(); return@post }
                    try {
                        audioFile = file
                        player = MediaPlayer().apply {
                            setAudioAttributes(attributes); setDataSource(file.absolutePath)
                            setOnCompletionListener { mp ->
                                mp.release()
                                file.delete()
                                if (generation == request) {
                                    markSpeaking(false)
                                    if (player === mp) player = null
                                    if (audioFile == file) audioFile = null
                                    cloudPart(context, parts, index + 1, language, config, mode, request)
                                }
                            }
                            setOnErrorListener { mp, what, extra ->
                                mp.release()
                                file.delete()
                                if (generation == request) {
                                    markSpeaking(false)
                                    if (player === mp) player = null
                                    if (audioFile == file) audioFile = null
                                    Diag.warn("Speech", "cloud audio error mode=$mode what=$what extra=$extra, fallback to system")
                                    systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                                }
                                true
                            }
                            prepare(); markSpeaking(true); start()
                            if (index == 0) notify(true, mode, "ok", language, language, false, "", request)
                        }
                    } catch (e: Exception) {
                        Diag.warn("Speech", "cloud playback failed mode=$mode ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}, fallback to system")
                        player?.release(); player = null; file.delete(); audioFile = null
                        if (generation == request) {
                            systemRead(context, parts.drop(index).joinToString(""), language, request, mode)
                        }
                    }
                }
            } catch (e: Exception) {
                if (generation != request) return@execute
                // Fallo de síntesis en la nube: registra diagnóstico e indica el motivo en pantalla, luego recurre a la voz del sistema (si la TV no tiene voz del sistema, conserva el error chino original de la nube)
                val errMsg = (e.message ?: e.javaClass.simpleName).take(120)
                Diag.warn("Speech", "cloud synth failed mode=$mode ${e.javaClass.simpleName}: $errMsg, fallback to system")
                val cloudNotice = if (errMsg.startsWith("DeepSeek") || errMsg.startsWith("La lectura en la nube") || errMsg.startsWith("云端")) errMsg else "Fallo de lectura en la nube：$errMsg"
                notify(false, mode, "cloud-unavailable", language, "", true, cloudNotice, request)
                main.post {
                    if (generation != request) return@post
                    systemRead(context, parts.drop(index).joinToString(""), language, request, mode, cloudNotice)
                }
            }
        }
    }

    /** Cuando el cuerpo devuelto por la nube no es audio, se incluye un pequeño fragmento inicial en el mensaje de error para que el padre al menos vea qué dijo la interfaz */
    private fun bodySnippet(bytes: ByteArray): String =
        try {
            String(bytes, 0, minOf(bytes.size, 200), Charsets.UTF_8).replace(Regex("\\s+"), " ").trim().take(160)
        } catch (_: Exception) { "" }

    internal fun validatePcmBufferSize(minBytes: Int): Int {
        require(minBytes > 0) { "AudioTrack minBufferSize invalid ($minBytes)" }
        return maxOf(minBytes, 16 * 1024)
    }

    internal fun validateAudioTrackState(state: Int) {
        if (state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack not initialized (state=$state)")
        }
    }

    internal fun validatePcmWrite(written: Int, isCurrent: Boolean): Int {
        if (written > 0) return written
        if (!isCurrent) return 0
        throw IllegalStateException("AudioTrack.write failed (code=$written)")
    }

    /**
     * Reproduce directamente WAV PCM de 16 bits (lectura DeepSeek).
     *
     * No pasa por MediaPlayer / decodificador del sistema: en Huawei y tablets EMUI, MediaPlayer con este tipo de WAV directamente
     * `Prepare failed.: status=0x1`. La reproducción sigue siendo secuencial en [worker]; el siguiente segmento se sintetiza cuando termina el anterior.
     */
    private fun playPcm(
        pcm: WavAudio.Pcm,
        request: Int,
        onStarted: (() -> Unit)? = null,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        worker.execute {
            var audioTrack: AudioTrack? = null
            try {
                if (request != generation) return@execute
                val mask = if (pcm.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(mask).build()
                val minBytes = AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                val bufferBytes = validatePcmBufferSize(minBytes)
                val created = AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE)
                try {
                    validateAudioTrackState(created.state)
                } catch (e: Exception) {
                    try { created.release() } catch (_: Exception) { }
                    throw e
                }
                synchronized(trackLock) {
                    if (request != generation) {
                        try { created.release() } catch (_: Exception) { }
                        return@execute
                    }
                    audioTrack = created
                    track = created
                }
                markSpeaking(true)
                created.play()
                if (request == generation) onStarted?.invoke()
                var offset = 0
                while (offset < pcm.data.size && request == generation) {
                    val written = created.write(pcm.data, offset, minOf(16 * 1024, pcm.data.size - offset))
                    val advance = validatePcmWrite(written, request == generation)
                    if (advance == 0) break
                    offset += advance
                }
                // Esperar a que termine el último segmento del búfer antes de pasar a la siguiente frase; playbackHeadPosition es un contador de frames de 32 bits sin signo
                val durationMs = pcm.frames * 1000L / pcm.sampleRate.coerceAtLeast(1)
                val deadline = System.currentTimeMillis() + durationMs + 3000L
                while (request == generation && (created.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < pcm.frames &&
                    System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                if (request == generation) main.post { if (request == generation) onDone() }
            } catch (e: Exception) {
                val message = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                Diag.warn("Speech", "pcm playback failed $message")
                main.post { if (request == generation) onError(message) }
            } finally {
                if (request == generation) markSpeaking(false)
                synchronized(trackLock) {
                    if (track === audioTrack) track = null
                    try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) { }
                    try { audioTrack?.release() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun request(url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): ByteArray {
        val connection = openCloudConnection(url, "síntesis de voz")
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 10000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body) } }
            require(connection.responseCode in 200..299)
            return connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; bytes.write(buffer, 0, size); require(bytes.size() <= 2_000_000) }
                bytes.toByteArray().also { require(it.isNotEmpty()) }
            }
        } finally { connection.disconnect() }
    }
    private fun xml(text: String) = EdgeTts.escapeXml(text)
    private fun azure(config: JSONObject, text: String, language: String): ByteArray {
        val region = config.getString("azureRegion")
        require(region.matches(Regex("[a-z0-9-]+")))
        // xml:lang de SSML no puede estar vacío; si la pregunta no tiene idioma etiquetado se lee con el idioma del sistema del dispositivo; otros sistemas lingüísticos se tratan como chino
        val tag = language.ifBlank { defaultLanguage() }.let { if (it.startsWith("zh") || it.startsWith("en")) it else "zh-CN" }
        val voice = config.optString("azureVoice").ifBlank { when {
            tag.startsWith("en") -> "en-US-JennyNeural"
            tag == "zh-HK" -> "zh-HK-HiuMaanNeural"
            tag == "zh-TW" -> "zh-TW-HsiaoChenNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        } }
        val ssml = "<speak version='1.0' xml:lang='${xml(tag)}'><voice name='${xml(voice)}'>${xml(text)}</voice></speak>"
        return request("https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
            mapOf("Ocp-Apim-Subscription-Key" to config.getString("azureKey"), "Content-Type" to "application/ssml+xml",
                "X-Microsoft-OutputFormat" to "audio-16khz-32kbitrate-mono-mp3"), ssml.toByteArray())
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    private fun aliyun(config: JSONObject, text: String): ByteArray {
        val identity = config.getString("aliyunAccessKeyId") + config.getString("aliyunSecret")
        val currentToken = synchronized(tokenLock) {
            if (token.isEmpty() || identity != tokenKey || System.currentTimeMillis() / 1000 >= tokenExpires - 120) {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
                val parameters = sortedMapOf("AccessKeyId" to config.getString("aliyunAccessKeyId"), "Action" to "CreateToken", "Format" to "JSON",
                    "Version" to "2019-02-28", "SignatureMethod" to "HMAC-SHA1", "SignatureVersion" to "1.0", "SignatureNonce" to UUID.randomUUID().toString(), "Timestamp" to timestamp)
                val query = parameters.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
                val mac = javax.crypto.Mac.getInstance("HmacSHA1")
                mac.init(javax.crypto.spec.SecretKeySpec((config.getString("aliyunSecret") + "&").toByteArray(), "HmacSHA1"))
                val signature = MediaStore.encodeBase64(mac.doFinal(("GET&%2F&" + encode(query)).toByteArray()))
                val result = JSONObject(String(request("https://nls-meta.cn-shanghai.aliyuncs.com/?$query&Signature=${encode(signature)}"), Charsets.UTF_8)).getJSONObject("Token")
                token = result.getString("Id"); tokenExpires = result.getLong("ExpireTime"); tokenKey = identity
            }
            token
        }
        val voice = config.optString("aliyunVoice").ifBlank { "xiaoyun" }
        return request("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts?appkey=${encode(config.getString("aliyunAppKey"))}&token=${encode(currentToken)}&text=${encode(text)}&format=mp3&sample_rate=16000&voice=${encode(voice)}")
    }
}
