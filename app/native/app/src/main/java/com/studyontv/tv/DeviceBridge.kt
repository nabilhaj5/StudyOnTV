package com.studyontv.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.Locale

open class DeviceBridge(protected val activity: Activity, private val connectionAllowed: Boolean = false, private val web: android.webkit.WebView? = null) {
    @JavascriptInterface
    fun answerInput(voice: Boolean) {
        activity.runOnUiThread { web?.let { InputSupport.open(activity, it, voice) } }
    }

    @JavascriptInterface
    fun answerText(draft: String, prompt: String) {
        activity.runOnUiThread { web?.let { InputSupport.openText(activity, it, draft, prompt) } }
    }

    @JavascriptInterface
    fun apiToken(): String = AdminAuth.deviceToken

    /** Botones de la página de conexión (padre ya verificado): ajustes de permisos / cambiar contraseña */
    @JavascriptInterface
    fun openPermissions() {
        if (!connectionAllowed) return
        activity.runOnUiThread { activity.startActivity(Intent(activity, OnboardingActivity::class.java)) }
    }

    /** La web en HTTP por LAN no tiene acceso a la API Clipboard; es más fiable que lo escriba el nativo */
    @JavascriptInterface
    fun copyText(value: String): Boolean {
        if (!connectionAllowed) return false
        return try {
            val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return false
            manager.setPrimaryClip(android.content.ClipData.newPlainText("题了个题", value))
            true
        } catch (e: Exception) {
            Diag.warn("UI", "copyText failed: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun changePassword() {
        if (!connectionAllowed) return
        activity.runOnUiThread { (activity as? ParentActivity)?.changePassword() }
    }

    @JavascriptInterface
    fun deviceInfo(): String = JSONObject().put("platform", if (isTv(activity)) "android-tv" else "android")
        .put("model", Build.MODEL).put("version", BuildConfig.VERSION_NAME).put("night", TvUi.systemNight(activity)).toString()

    @JavascriptInterface
    fun lanInfo(): String = if (connectionAllowed) DeviceRuntime.connection(activity).toString() else ""

    @JavascriptInterface
    fun openParent() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, ParentActivity::class.java))
        }
    }

    /**
     * Entrada de desbloqueo para padres en la página de bloqueo / preguntas (#201): el padre introduce
     * la contraseña directamente con el mando y se desbloquea temporalmente durante 30 minutos.
     * Desactivado por defecto (allowQuizUnlock) para no poner la entrada de contraseña delante del niño;
     * la contraseña solo se valida en el dispositivo y no se envía a ningún sitio.
     */
    @JavascriptInterface
    fun askParentPassword() {
        activity.runOnUiThread {
            if (!quizUnlockAllowed()) return@runOnUiThread
            web?.let { InputSupport.openPassword(activity, it, "Introduce la contraseña de padre; tras verificarla se desbloqueará temporalmente durante $QUIZ_UNLOCK_MINUTES minutos") }
        }
    }

    /** Valida la contraseña de padre y desbloquea temporalmente; devuelve {ok, status} o {ok:false, error:disabled|wrong|failed} */
    @JavascriptInterface
    fun quizUnlock(value: String): String {
        if (!quizUnlockAllowed()) return JSONObject().put("ok", false).put("error", "disabled").toString()
        if (!AdminAuth.verify(activity, value)) return JSONObject().put("ok", false).put("error", "wrong").toString()
        return try {
            AdminAuth.markVerified()
            val status = DeviceRuntime.learning.remote("unlock", QUIZ_UNLOCK_MINUTES, "quiz")
            Diag.info("Auth", "el padre desbloqueó temporalmente $QUIZ_UNLOCK_MINUTES minutos en la página de bloqueo")
            GateService.hideGates()
            GateService.refreshNow()
            JSONObject().put("ok", true).put("status", status).toString()
        } catch (e: Exception) {
            Diag.warn("Auth", "quiz unlock failed: ${e.message}")
            JSONObject().put("ok", false).put("error", "failed").toString()
        }
    }

    private fun quizUnlockAllowed(): Boolean = try {
        DeviceRuntime.store.config().optBoolean("allowQuizUnlock", false)
    } catch (_: Throwable) { false }

    /**
     * Entrada de verificación para padres en la página de bloqueo de ajustes del sistema / gestión de apps:
     * solo permite acceder temporalmente a los ajustes del sistema durante 2 minutos (no suma tiempo de ocio),
     * para que el padre pueda conectar WiFi / Bluetooth o gestionar el dispositivo; al volver a esta app
     * o al pasar 2 minutos se restaura el bloqueo de inmediato.
     */
    @JavascriptInterface
    fun askSettingsPassword() {
        activity.runOnUiThread {
            web?.let { InputSupport.openPassword(activity, it, "Introduce la contraseña de administración; tras verificarla podrás entrar temporalmente en los ajustes del sistema durante 2 minutos") }
        }
    }

    @JavascriptInterface
    fun settingsUnlock(value: String): String {
        if (!AdminAuth.verify(activity, value)) return JSONObject().put("ok", false).put("error", "wrong").toString()
        SystemSettingsGuard.grant()
        Diag.info("Auth", "el padre verificó la contraseña en la página de bloqueo de ajustes y se permite temporalmente el acceso a los ajustes del sistema durante 2 minutos")
        GateService.hideGates()
        activity.runOnUiThread {
            if (activity is GateActivity) activity.finish() else activity.moveTaskToBack(true)
        }
        GateService.refreshNow()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun navigationBlocked(): Boolean {
        LearningNavigation.notifyBlocked(activity)
        return true
    }

    @JavascriptInterface
    fun exitToLauncher() {
        val status = DeviceRuntime.learning.status()
        if (!status.optBoolean("canExit")) return
        activity.runOnUiThread {
            GateService.hideGates()
            // Al terminar las preguntas se vuelve a la «tarea anterior»; nunca se salta al escritorio por iniciativa propia,
            // porque el vídeo que se está reproduciendo se interrumpiría (#54 / #63).
            // GateActivity es una tarea independiente (singleInstance + taskAffinity propia); al hacer finish el sistema
            // devuelve automáticamente al frente la tarea inferior (云视听 / Tencent Video); la pantalla principal,
            // en cambio, manda toda la tarea al fondo.
            if (activity is GateActivity) activity.finish()
            else activity.moveTaskToBack(true)
        }
    }

    /**
     * Tras desbloquear con las preguntas se vuelve a la pantalla previa a la resolución.
     *
     * Primero se oculta la página de control y se manda al fondo su propia tarea; en condiciones normales
     * la app anterior vuelve al frente; si unos cientos de milisegundos después el frente sigue siendo
     * nosotros mismos o el escritorio (algunos modelos tratan la página de preguntas como tarea independiente
     * y tras el finish aparece el escritorio), se lanza explícitamente la app recordada.
     */
    @JavascriptInterface
    fun resumePrevious() {
        val status = DeviceRuntime.learning.status()
        if (!status.optBoolean("canExit")) return
        // No se comprueba connectionAllowed: comparte el mismo puente con la página de estudio / GateActivity,
        // y en la página de control connectionAllowed es false; comprobarlo impediría restaurar la pantalla tras desbloquear.
        // Esta llamada solo oculta la página de control y restaura la app controlada que ella misma recordó, sin exponer datos del dispositivo.
        activity.runOnUiThread {
            GateService.hideGates()
            val target = GateService.rememberedActive()
            if (activity is GateActivity) activity.finish() else activity.moveTaskToBack(true)
            if (target == null || target == activity.packageName || Foreground.isLauncher(target)) return@runOnUiThread
            val appContext = activity.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val now = try { Foreground.current(appContext) } catch (_: Exception) { null }
                if (now == null || now == appContext.packageName || Foreground.isLauncher(now)) launchPackage(appContext, target)
            }, 700)
        }
    }

    private fun launchPackage(pkg: String): Boolean = launchPackage(activity, pkg)

    @JavascriptInterface
    fun backToQuiz() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (activity is ParentActivity) activity.finish()
        }
    }

    @JavascriptInterface
    fun updateInfo(): String {
        val info = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity)) ?: return ""
        return Updater.toJson(info).put("current", Updater.currentVersion(activity)).toString()
    }

    @JavascriptInterface
    fun installUpdate(url: String, latest: String, notes: String) {
        val cached = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity))
        val payload = if (cached != null && cached.url == url) Updater.toJson(cached) else JSONObject()
            .put("latest", latest).put("notes", notes).put("url", url)
        payload.put("current", BuildConfig.VERSION_NAME)
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, UpdateActivity::class.java).putExtra("payload", payload.toString()))
        }
    }

    @JavascriptInterface
    fun readAloud(text: String) = SpeechReader.read(activity, text, "", ::speechNotice)

    @JavascriptInterface
    fun readInLanguage(text: String, language: String) = SpeechReader.read(activity, text, language, ::speechNotice)

    /** Preguntas de comprensión auditiva: leer con la velocidad indicada por pregunta (listenRate); rate = -1 significa seguir la velocidad global de los ajustes de lectura */
    @JavascriptInterface
    fun readWithRate(text: String, language: String, rate: Double) = SpeechReader.read(activity, text, language, ::speechNotice, rate)

    @JavascriptInterface
    fun stopReading() = SpeechReader.stop()

    @JavascriptInterface
    fun playAudio(url: String) = SpeechReader.playAudio(activity, url, ::speechNotice)

    /** Resultado de la última lectura / reproducción de audio; en caso de fallo tiene valor reason/message/degraded (#87 ya no es silencioso) */
    @JavascriptInterface
    fun speechStatus(): String = SpeechReader.status().toString()

    /** La información de fallo se envía a la web: si la página tiene instalado window.SOTV_SPEECH_NOTICE puede mostrar el aviso (misma convención que SOTV_INPUT/SOTV_UPDATE) */
    private fun speechNotice(payload: JSONObject) {
        val view = web ?: return
        view.post { try { view.evaluateJavascript("window.SOTV_SPEECH_NOTICE&&SOTV_SPEECH_NOTICE($payload)", null) } catch (_: Throwable) { } }
    }

    @JavascriptInterface
    fun watchLesson() { activity.runOnUiThread { VideoLessons.open(activity) } }

    @JavascriptInterface
    fun answerVoice(draft: String, prompt: String, language: String) {
        activity.runOnUiThread { web?.let { InputSupport.openVoice(activity, it, draft, prompt, SpeechReader.recognitionLanguage(language)) } }
    }

    companion object {
        /** Minutos de desbloqueo temporal tras la verificación del padre en la página de bloqueo */
        const val QUIZ_UNLOCK_MINUTES = 30

        /**
         * Abrir los ajustes del sistema (#130). Muchas TV / proyectores / tablets recortan el menú de ajustes nativo,
         * y el padre no encuentra entradas como «Gestión de apps → Permisos»; la app necesita un botón que abra
         * los ajustes completos. Se intenta en orden: ajustes del sistema → página de detalles de esta app;
         * si no se abre ninguno, devuelve false y quien llama muestra el aviso.
         */
        fun openSystemSettings(context: Context): Boolean {
            try { GateService.openingSystemPage(parentAuthorized = true) } catch (_: Exception) { }
            val options = listOf(
                Intent(android.provider.Settings.ACTION_SETTINGS),
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
            )
            for (intent in options) try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Diag.info("UI", "opened system settings ${intent.action}")
                return true
            } catch (_: Exception) { }
            Diag.warn("UI", "no system settings activity on this device")
            return false
        }
        /** Traer al frente la app controlada recordada; si falla solo se registra en el log, sin afectar al control */
        fun launchPackage(context: android.content.Context, pkg: String): Boolean = try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) false else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                Diag.info("UI", "resumed previous app $pkg")
                true
            }
        } catch (e: Exception) {
            Diag.warn("UI", "resume $pkg failed: ${e.message}")
            false
        }

        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"
    }
}
