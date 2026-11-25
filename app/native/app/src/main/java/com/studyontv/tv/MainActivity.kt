package com.studyontv.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView

open class MainActivity : Activity() {
    protected lateinit var web: WebView
    private var webReleased = false
    private var page = "quiz"
    private val remote = RemoteKeys { startActivity(Intent(this, ParentActivity::class.java)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程被杀 / 升级后先恢复上次遗留的媒体流静音，避免电视一直无声（issue #116）。
        // 放在配置检查之前：即使还没完成设置，也不该让整机保持静音。
        MediaPause.recover(this)
        if (!AdminAuth.configured(this)) {
            startActivity(Intent(this, ParentActivity::class.java)); finish(); return
        }
        if (!Prefs.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        DeviceRuntime.ensure(this)
        checkCrashReport()   // 检测上次崩溃标记，有则弹窗
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (DeviceBridge.isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val reason = intent.getStringExtra("reason")
        page = if (this is GateActivity && reason != null) gateLearningPage(reason) else "quiz"
        createWeb()
        GateService.ensureRunning(this, "app")
        // #166：2GB 电视进程重建时若首次拉起前台服务被系统延迟/拒绝，3 秒后补试一次
        web.postDelayed({ if (!isFinishing && !isDestroyed && Prefs.onboarded(this)) GateService.ensureRunning(this, "app-retry") }, 3000)
    }

    /**
     * 检测上次崩溃标记，弹窗征求用户同意后自动上传。
     * 已同意且配置了 endpoint 的用户不再弹窗——DeviceRuntime.ensure() 里已自动触发上传。
     */
    private var crashDialog: AlertDialog? = null

    private fun checkCrashReport() {
        if (!CrashDetector.hasPending(this)) return

        // 已同意：DeviceRuntime.ensure() 已自动触发上传，无需再操作
        if (Prefs.crashUploadConsent(this)) return

        // 未同意：弹窗征求同意
        val summary = CrashDetector.readAndClear(this)
        crashDialog?.dismiss()
        crashDialog = AlertDialog.Builder(this)
            .setTitle("🚨 La app se cerró inesperadamente la última vez")
            .setMessage(
                "¿Permites subir el registro de errores para ayudar a solucionarlo?\n\n" +
                "Incluye: seguimiento de la pila, modelo del dispositivo, versión de Android y de la app\n" +
                "No incluye: contenido del banco de preguntas, contraseña de padres, historial de respuestas ni IP local\n\n" +
                "El registro se enviará al servidor del desarrollador. Puedes desactivarlo en «Ajustes → Informes de errores»."
            )
            .setPositiveButton("Permitir y subir") { _, _ ->
                Prefs.setCrashUploadConsent(this, true)
                Prefs.setCrashUploadConsentAsked(this, true)
                val endpoint = Prefs.crashEndpoint(this)
                val token = Prefs.crashToken(this)
                // 写回标记：endpoint 已配置时由上传线程读取，未配置时留到下次启动补传
                CrashDetector.writePending(this, summary)
                if (endpoint.isNotBlank()) {
                    Diag.uploadPendingCrash(this, endpoint, token)
                    android.widget.Toast.makeText(this, "Subiendo registro de errores. ¡Gracias por tu ayuda!", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Consentimiento guardado. Se aplicará cuando configures una dirección de subida en Ajustes.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("No, gracias") { _, _ ->
                Prefs.setCrashUploadConsentAsked(this, true)
            }
            .setCancelable(false)
            .show()
    }

    private fun checkUpdate() {
        if (this is GateActivity) return
        Updater.check(this) { info ->
            val newer = Updater.newerThanCurrent(this, info) ?: return@check
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val raw = DeviceBridge(this).updateInfo()
                if (newer.force) startActivity(Intent(this, UpdateActivity::class.java).putExtra("payload", raw))
                else if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_UPDATE&&SOTV_UPDATE($raw)", null)
            }
        }
    }

    private fun createWeb() {
        web = WebShell(this).create(Prefs.localBase(this) + "#/$page")
        web.addJavascriptInterface(DeviceBridge(this, false, web), "SOTVNative")
        setContentView(web)
        web.requestFocus()
        webReleased = false
    }

    override fun onStop() {
        super.onStop()
        MediaPause.release(this)
        SpeechReader.stop()
        // 正在等原生输入页（填空题）回传答案时不能销毁 WebView：
        // TvTextInputActivity / InputSupport 会把结果写回当前 WebView，destroy() 后 JS 上下文消失，
        // 重投全部落在死实例上，表现为「点确定回到题目还是空的」（#64 / #77 / #80 / #84）。
        val awaitingInput = InputSupport.active || AnswerInput.active
        if (DeviceBridge.isTv(this) && ::web.isInitialized && !webReleased && !awaitingInput) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
            webReleased = true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val reason = intent?.getStringExtra("reason")
        page = if (this is GateActivity && reason != null) gateLearningPage(reason) else "quiz"
        if (::web.isInitialized && !webReleased) {
            web.loadUrl(Prefs.localBase(this) + "#/$page")
            web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized && !webReleased) web.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized && !webReleased) web.onResume()
        // 前台重试一次遗留静音恢复（非前台时系统可能拦截音量写入）。
        MediaPause.recover(this)
        if (Prefs.onboarded(this)) GateService.ensureRunning(this, "app")
        if (this is GateActivity && !DeviceRuntime.learning.status().optBoolean("free")) MediaPause.pause(this)
        if (webReleased) createWeb()
        checkUpdate()
        if (::web.isInitialized && !webReleased) web.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
    }

    fun showNavigationBlocked(): Boolean =
        if (::web.isInitialized && !webReleased) LearningNavigation.showBlockedNotice(web) else false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // #143：创维 A5D Pro / 酷开开启「强制使用全屏学习页」后，遥控器关机键被全屏页吞掉。
        // 电源 / 休眠键任何页面都不处理，原样交还系统；全屏学习页自己先退出，给固件让出正常关机路径。
        if (isPowerKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && this is GateActivity) {
                Diag.info("Gate", "遥控器电源键：退出全屏学习页，交给系统处理")
                finish()
            }
            return super.dispatchKeyEvent(event)
        }
        // 手机 / 平板把方向键和回车交给网页输入框，仅拦截返回键给 window.TV.back()
        if (!DeviceBridge.isTv(this) && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val view = if (::web.isInitialized && !webReleased) web else null
        return remote.handle(view, event) || super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        crashDialog?.dismiss()
        crashDialog = null
        if (::web.isInitialized && !webReleased) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
            webReleased = true
        }
        super.onDestroy()
    }

    companion object {
        /** 电源 / 休眠 / 待机键：任何页面都不能拦截，否则电视关不了机（#143） */
        fun isPowerKey(code: Int): Boolean = code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_SLEEP ||
            code == KeyEvent.KEYCODE_SOFT_SLEEP
    }
}
