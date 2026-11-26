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
 * Activity 与悬浮层共用同一朗读通路；云端失败静默回落到系统。
 *
 * 失败不再静默（#87 / #106）：
 * - `setLanguage` 返回 LANG_MISSING_DATA / LANG_NOT_SUPPORTED 时先回落设备默认语言，再不行记诊断并回报；
 * - `UtteranceProgressListener` 的 onError 记诊断、用默认语言补读一次，onDone 清理状态；
 * - `playAudio` 的 MediaPlayer 报错会记诊断并回报（Toast + 桥），不再只是 release。
 * 回报有两条路：`status()`（桥方法 `speechStatus` 拉取）与 `notice` 回调（桥推给网页的 SOTV_SPEECH_NOTICE）。
 */
object SpeechReader {
    /** 语音识别白名单：白名单外的题目语言不静默当中文，而是降级到设备默认语言并记诊断（#81） */
    private val RECOGNITION_LANGUAGES = listOf("zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** 题目可以标注的朗读语言；"" 表示跟随设备系统语言（#126 粤语系统被读成国语的根因是写死 zh-CN） */
    val LANGUAGES = listOf("", "zh-CN", "zh-HK", "zh-TW", "en-US", "en-GB")
    /** 语音识别支持的语种（白名单外再降级，供回归测试断言） */
    val whitelist: List<String> get() = RECOGNITION_LANGUAGES
    /** 设备级朗读语言覆盖："" 跟随题目、"system" 跟随系统，其余为固定语言标签 */
    private val OVERRIDES = LANGUAGES + listOf("system")

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    /** 下一分段的预合成放到独立线程，绝不堵塞当前分段在 worker 上的 PCM 播放。 */
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    /**
     * 云端合成结果缓存：题目重绘、回填草稿都会再次触发自动朗读，同一段文字没必要再合成一次
     * （DeepSeek 按字符计费，用户反馈「读不出来还在扣钱」）。只留最近几段。
     */
    private val synthCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 6
    }
    /** 云端 WAV 走 AudioTrack 播放（华为等设备的 MediaPlayer 播不了，用户反馈），stopNow 暂停打断、playPcm finally 唯一负责释放 */
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
    /** 最近一次朗读 / 播放的结果，供 speechStatus 桥与回归测试断言 */
    @Volatile private var last: JSONObject? = null
    @Volatile private var speaking = false
    /** 最近一次朗读活跃时间（elapsedRealtime），用于 MediaPause.enforce 避免把自家朗读误判成后台视频而静音（#168） */
    @Volatile private var lastSpokeAt = 0L

    private fun markSpeaking(active: Boolean) {
        speaking = active
        lastSpokeAt = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L }
    }
    /** 桥的回报回调（只在一次朗读 / 播放期间持有，stopNow 释放，避免长期抓住 WebView） */
    private var notice: ((JSONObject) -> Unit)? = null
    private var cachedContext: Context? = null
    @Volatile private var currentText = ""
    @Volatile private var currentLanguage = ""
    /** 单次朗读的语速覆盖：听力题可以按题指定，-1 表示跟随全局 speechRate */
    @Volatile private var currentRate = -1.0
    @Volatile private var lastUtteranceId = ""
    @Volatile private var retriedDefault = false
    @Volatile private var retriedSpeakError = false
    // 走媒体流而不是无障碍流：华为智慧屏、TCL 这类电视上无障碍流常常没有输出，
    // 表现就是「点了朗读没声音」；媒体流一定会经过电视的扬声器 / HDMI。
    private val attributes get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** DeepSeek 语音（合成 + 转写）的两种音色，见 https://api-docs.deepseek.com/zh-cn/guides/tts */
    val DEEPSEEK_VOICES = listOf("stella", "tide")
    /** 语音转写通路：system 用系统识别器、deepseek 上传给 DeepSeek、auto 填了 Key 就走 DeepSeek */
    val RECOGNIZE_MODES = listOf("system", "deepseek", "auto")

    /** 自动朗读的内容范围：只读题干、题干和选项、题干选项再加答案（答案在作答后读，作答前不剧透） */
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
        require(mode in listOf("auto", "system", "edge", "deepseek", "azure", "aliyun")) { "Canal de lectura desconocido" }
        next.put("mode", mode).put("autoRead", input.optBoolean("autoRead"))
        if (input.has("readScope")) {
            val scope = input.optString("readScope", "question")
            require(scope in READ_SCOPES) { "Contenido de lectura desconocido" }
            next.put("readScope", scope)
        }
        if (input.has("recognizeMode")) {
            val recognize = input.optString("recognizeMode", "system")
            require(recognize in RECOGNIZE_MODES) { "Canal de reconocimiento de voz desconocido" }
            next.put("recognizeMode", recognize)
        }
        if (input.has("deepseekVoice")) {
            val voice = input.optString("deepseekVoice").trim().ifEmpty { "stella" }
            require(voice in DEEPSEEK_VOICES) { "Voz de DeepSeek no compatible" }
            next.put("deepseekVoice", voice)
        }
        // #126：设备级朗读语言。空 = 跟随题目设置，"system" = 跟随设备系统语言（粤语系统读粤语）
        if (input.has("readLanguage")) {
            val readLanguage = input.optString("readLanguage").trim()
            require(readLanguage in OVERRIDES) { "Idioma de lectura no compatible" }
            next.put("readLanguage", readLanguage)
        }
        if (input.has("edgeVoice")) {
            val edgeVoice = input.optString("edgeVoice").trim()
            require(edgeVoice.isEmpty() || edgeVoice in EdgeTts.VOICES) { "Voz de Edge no compatible" }
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
        for (key in listOf("azureKey", "aliyunSecret", "deepseekKey")) if (input.has(key) && input.optString(key).isNotBlank()) {
            require(input.getString(key).length <= 500); next.put(key, input.getString(key).trim())
        }
        // 在朗读里填写 DeepSeek Key 时，若「模型 API」选的是 DeepSeek 且还没填 Key，自动同步过去，避免家长填两遍
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
        if (input.optBoolean("clearKeys")) { next.remove("azureKey"); next.remove("aliyunSecret"); next.remove("deepseekKey") }
        store.set("speech_settings", next.toString())
        synchronized(tokenLock) { token = ""; tokenExpires = 0L; tokenKey = "" }
        synchronized(synthCache) { synthCache.clear() }
        return settings(store)
    }

    /**
     * 朗读。`language` 是题目语言；引擎不支持时先回落设备默认语言，再不行回报失败。
     * `notice` 由桥传入，失败时回调（网页里有 window.SOTV_SPEECH_NOTICE 就能显示提示）。
     * `rate` 是本次朗读的语速（听力题的 listenRate），-1 表示用朗读设置里的全局语速。
     */
    fun read(context: Context, text: String, language: String = "", notice: ((JSONObject) -> Unit)? = null, rate: Double = -1.0) {
        if (text.isBlank()) return
        val app = context.applicationContext
        main.post {
            val config = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Throwable) { JSONObject() }
            // 听力题按题指定语速：写进本次配置副本，云端合成与缓存键都会跟着用这个语速
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
                // 电视没有内置语音引擎时，如果家长已在「模型 API」配过 DeepSeek Key，自动兜底使用 DeepSeek 朗读
                mode == "system" && TtsProbe.defaultEngine(app).isEmpty() && deepseekKey(config).isNotEmpty() -> "deepseek"
                else -> mode
            }
            // 系统 TTS 使用 QUEUE_FLUSH 覆盖旧播报，避免在部分固件（如 OPPO）上连续调用 system.stop() + speak() 导致后续题目静默丢弃
            stopNow(stopSystem = chosen != "system")
            val request = generation
            this.notice = notice
            cachedContext = app
            val clean = text.take(10000)
            // #126：家长可以在朗读设置里指定设备朗读语言；留空就跟随每道题的语言标注
            val spoken = when (val override = config.optString("readLanguage").trim()) {
                "system" -> ""          // 跟随设备系统语言
                "" -> language          // 跟随题目
                else -> override
            }
            if (chosen == "system") systemRead(app, clean, spoken, request)
            else cloudPart(app, cloudChunks(clean, chosen), 0, spoken, config, chosen, request)
        }
    }

    /** 本次朗读实际使用的语速：听力题按题指定优先，否则取朗读设置里的全局值 */
    private fun rateOf(config: JSONObject): Double {
        val raw = if (currentRate in 0.7..1.5) currentRate else config.optDouble("speechRate", 1.0)
        return raw.takeIf { it.isFinite() }?.coerceIn(0.7, 1.5) ?: 1.0
    }

    /** 题目音频。老电视的 https / mp3 兼容性差时不再“点了没反应”：记录诊断 + Toast + 回报给前端 */
    fun playAudio(context: Context, url: String, notice: ((JSONObject) -> Unit)? = null) {
        val app = context.applicationContext
        // 地址本身不合法：不排队到主线程，直接同步回报（回归测试可断言 status()）
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            recordFailure("audio", "unsupported-url", "", "", false, "La dirección del audio no es correcta; no se puede reproducir", notice)
            return
        }
        if (LocalOnlyGuard.enabled && !LocalOnlyGuard.isLocalUrl(url)) {
            recordFailure("audio", "local-only-blocked", "", "", false, "La versión de privacidad solo local solo permite reproducir audio de este equipo o de la red local", notice)
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
                        audioFailed(app, request, "player-error", "No se puede reproducir el audio (what=$what extra=$extra); comprueba la red o usa la lectura")
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                player?.release(); player = null
                audioFailed(app, request, e.javaClass.simpleName, "No se puede reproducir el audio (${e.javaClass.simpleName} ${e.message?.take(80).orEmpty()}); comprueba la red o usa la lectura")
            }
        }
    }

    fun stop() { main.post { stopNow() } }

    /** 需要直接弹在电视屏幕上（而不是只写诊断）的失败：老电视上「点了朗读没声音」最常见的就是这几类 */
    private val TOAST_REASONS = setOf("engine-unavailable", "language-unavailable", "language-check-failed", "utterance-error", "cloud-unavailable")

    /** 当前/最近一次朗读结果：{ok,mode,requested,language,degraded,reason,message,at}，桥方法 speechStatus 直接返回它 */
    fun status(): JSONObject = last?.let { JSONObject(it.toString()) } ?: JSONObject()
        .put("ok", true).put("mode", "").put("requested", "").put("language", "").put("degraded", false)
        .put("reason", "idle").put("message", "").put("at", 0L)

    fun speaking(): Boolean = speaking

    /** 正在朗读或刚结束朗读不久（Android AudioManager.isMusicActive 在停播后仍会残留 1–2 秒） */
    fun speakingRecently(graceMs: Long = 2500L): Boolean {
        if (speaking) return true
        val at = lastSpokeAt
        if (at <= 0L) return false
        val now = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { return false }
        return now - at < graceMs
    }

    /** 失败信息结构（纯函数，回归测试可直接断言结构；不含题目 / 答案明文） */
    fun failureNotice(mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String): JSONObject =
        JSONObject().put("ok", false).put("mode", mode).put("requested", requested).put("language", language)
            .put("degraded", degraded).put("reason", reason).put("message", message).put("at", System.currentTimeMillis())

    /**
     * BCP47 标签标准化：`zh-Hans-CN` / `zh-Hant-TW` 这类带文字扩展的标签，部分 TTS 引擎的
     * `setLanguage` 会直接返回 LANG_MISSING_DATA，表现就是「明明装了中文语音包却读不出来」(#128)。
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

    /** 设备默认语言（TTS 语言缺失时的回落目标），已做 BCP47 标准化 */
    fun defaultLanguage(): String = normalizeLanguageTag(Locale.getDefault().toLanguageTag())

    /** 语言回落结果：degraded=true 表示请求的语言用不了，已经换成别的（或彻底失败，accepted=false） */
    data class LanguagePlan(val requested: String, val language: String, val accepted: Boolean, val degraded: Boolean, val reason: String)

    /**
     * 系统语音语言回落（纯函数，不碰引擎，instrumentation 可直接断言）。
     *
     * `available(tag)` 由调用方提供：真实场景就是 `system.setLanguage(Locale.forLanguageTag(tag))` 的返回值
     * （TextToSpeech.LANG_* 常量）；返回 null 表示“无法判断”（引擎未就绪 / 旧实现），按可用处理。
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

    /** available 抛异常（引擎被回收 / 还没就绪）时按“无法判断”处理，不把异常抛给调用方 */
    private fun ask(available: (String) -> Int?, tag: String): Int? = try { available(tag) } catch (_: Exception) { null }

    /** 语音识别语言：白名单内原样用；白名单外降级到设备默认语言并记一条诊断（不再静默变中文） */
    fun recognitionLanguage(requested: String): String {
        val want = requested.trim()
        // 题目没标语言：跟随设备系统语言（粤语 / 繁体系统的孩子用粤语识别），不支持时才用中文
        if (want.isEmpty()) return defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        if (want in RECOGNITION_LANGUAGES) return want
        val fallback = defaultLanguage().takeIf { it in RECOGNITION_LANGUAGES } ?: "zh-CN"
        Diag.warn("Speech", "recognition language $want not supported, degraded to $fallback")
        return fallback
    }

    private fun notify(ok: Boolean, mode: String, reason: String, requested: String, language: String, degraded: Boolean, message: String) {
        val payload = failureNotice(mode, reason, requested, language, degraded, message).put("ok", ok)
        last = payload
        // 成功只更新状态；只有失败才写诊断 + 推给网页（避免正常朗读也被当成提示）
        if (ok) return
        Diag.warn("Speech", "failed mode=$mode reason=$reason requested=${requested.ifEmpty { "-" }} used=${language.ifEmpty { "-" }} message=$message")
        // 页面上没有 SOTV_SPEECH_NOTICE 时（老版本网页）至少让电视屏幕上有个提示，不能点了没反应
        if (reason in TOAST_REASONS) main.post {
            try { cachedContext?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } } catch (_: Exception) { }
        }
        val callback = notice ?: return
        val copy = JSONObject(payload.toString())
        main.post { try { callback(copy) } catch (_: Exception) { } }
    }

    /** 失败回报：写状态 + 诊断，并回调桥（可在任意线程调用） */
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
        try { Toast.makeText(context, "No se puede reproducir el audio; comprueba la red o usa la lectura", Toast.LENGTH_SHORT).show() } catch (_: Exception) { }
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

    /** UtteranceProgressListener：朗读结束清理状态，合成失败记诊断并用默认语言补读一次 */
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
                "Error al reproducir la voz del sistema (code=$errorCode); puedes cambiar de idioma o usar la voz en la nube")
        }
    }

    private fun systemRead(context: Context, text: String, language: String, request: Int, mode: String = "system", cloudError: String = "") {
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
                    // 初始化失败时 speakSystem 会看到 system==null 并回报“系统语音不可用”
                    action?.invoke()
                }
            }
        }
    }

    private fun speakSystem(context: Context, text: String, language: String, request: Int, mode: String, cloudError: String = "") {
        val engine = system
        if (engine == null) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError (y este equipo no tiene voz del sistema integrada)"
            } else {
                "Este televisor no tiene voz del sistema integrada, no se puede leer; el padre puede rellenar la clave de DeepSeek en el panel «API del modelo» para usar la voz en la nube automáticamente"
            }
            notify(false, mode, if (cloudError.isNotEmpty()) "cloud-unavailable" else "engine-unavailable", language, "", cloudError.isNotEmpty(), msg)
            return
        }
        val plan = try {
            resolveLanguage(language) { tag -> try { engine.setLanguage(Locale.forLanguageTag(tag)) } catch (_: Exception) { null } }
        } catch (e: Exception) {
            Diag.warn("Speech", "setLanguage failed ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            notify(false, mode, "language-check-failed", language, "", false, cloudError.ifEmpty { "La voz del sistema no está disponible (${e.javaClass.simpleName})" })
            return
        }
        if (plan.reason == "fallback") Diag.info("Speech", "language ${plan.requested} unavailable, fell back to ${plan.language}")
        if (!plan.accepted) {
            val msg = if (cloudError.isNotEmpty()) {
                "$cloudError (y la voz del sistema no admite ${plan.requested})"
            } else {
                "La voz del sistema de este dispositivo no admite ${plan.requested}; instala un paquete de idioma o configura la voz de DeepSeek y vuelve a intentarlo"
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
        // 按句子攒到 180 字再排队朗读：电视内置 TTS 引擎读几千字的长句常常中途断掉（用户反馈「朗读不完整」），
        // 拆成短句用 QUEUE_ADD 串起来更稳，句间停顿也更自然。
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
                        "El motor de voz del sistema no responde; vuelve a intentarlo o usa la voz en la nube")
                }
                return
            }
        }
        markSpeaking(true)
        notify(true, mode, if (plan.degraded) "language-fallback" else "ok", plan.requested.ifEmpty { plan.language }, plan.language, plan.degraded, "")
    }

    private fun currentSettings(): JSONObject = try { JSONObject(DeviceRuntime.store.get("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }

    /**
     * 云端合成的分段。DeepSeek 一次最多读 1 万字，按句子攒到 400 字再请求，避免长题被切成十几段；
     * 其他通路保持原来的 150 字。
     */
    fun cloudChunks(text: String, mode: String = "azure"): List<String> =
        if (mode == "deepseek") packedSentences(text, 400) else text.chunked(150)

    private val SENTENCE_ENDS = "。！？!?；;\n"

    private fun packedSentences(text: String, limit: Int): List<String> {
        val pieces = ArrayList<String>(); val current = StringBuilder()
        fun flush() { val piece = current.toString().trim(); if (piece.isNotEmpty()) pieces.add(piece); current.setLength(0) }
        for (ch in text) {
            current.append(ch)
            if (ch in SENTENCE_ENDS || current.length >= limit) flush()
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

    /**
     * DeepSeek 的 Key：朗读设置里填了就用它；没填时自动复用「模型 API」里同一家服务商（DeepSeek）的 Key，
     * 家长不用为了朗读再填一遍。
     */
    fun deepseekKey(config: JSONObject = currentSettings(), store: LocalStore? = null): String {
        val own = config.optString("deepseekKey").trim()
        if (own.isNotEmpty()) return own
        if (!config.optBoolean("reuseGradingKey", true)) return ""
        return try {
            val s = store ?: DeviceRuntime.store
            val grading = JSONObject(s.get("grading_settings", "{}"))
            val provider = grading.optString("provider", "deepseek").trim()
            val endpoint = grading.optString("endpoint", "https://api.deepseek.com/v1").trim()
            if (provider == "deepseek" || endpoint.contains("api.deepseek.com", ignoreCase = true)) {
                grading.optString("apiKey").trim()
            } else ""
        } catch (_: Exception) { "" }
    }

    /** 语音转写要用的 DeepSeek Key；空串表示继续用系统识别器（电视上常常没有） */
    fun recognizeKey(store: LocalStore): String {
        val config = try { JSONObject(store.get("speech_settings", "{}")) } catch (_: Exception) { JSONObject() }
        return if (config.optString("recognizeMode", "
