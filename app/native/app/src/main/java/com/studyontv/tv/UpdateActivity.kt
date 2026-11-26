package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject




class UpdateActivity : Activity() {
    private var force = false
    private var sha256 = ""
    private lateinit var status: TextView
    private var goButton: TextView? = null
    @Volatile private var installHandedOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        val data = try {
            JSONObject(intent.getStringExtra("payload") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val latest = data.optString("latest", "")
        val current = data.optString("current", Updater.currentVersion(this))
        val notes = data.optString("notes", "")
            .lineSequence()
            .filter { !it.contains("[force]", true) && it.isNotBlank() }
            .joinToString("\n")
        val url = data.optString("url", "")
        force = data.optBoolean("force", false)
        sha256 = data.optString("sha256", "")

        val palette = TvUi.palette(this)
        val tv = DeviceBridge.isTv(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(if (tv) 32 else 20), dp(if (tv) 44 else 28), dp(if (tv) 32 else 20), dp(30))
        }
        content.addView(TvUi.text(this, if (tv) "题了个题 · TV" else "题了个题", 14f, palette.text3))
        content.addView(TvUi.text(this, "Nueva versión disponible", 15f, palette.text2).apply {
            setPadding(0, dp(18), 0, 0)
        })
        content.addView(TvUi.text(this, "v$latest", if (tv) 46f else 38f, palette.text, true).apply {
            setPadding(0, dp(2), 0, 0)
        })
        content.addView(TvUi.text(this, "Actual v$current" + if (force) " · Debes actualizar para continuar" else "", 14f, palette.text3).apply {
            setPadding(0, dp(6), 0, dp(22))
        })

        if (notes.isNotEmpty()) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(18))
                background = solid(palette.surface, 20)
                addView(TvUi.text(context, "Novedades", 12f, palette.text3))
                val note = TvUi.text(context, notes, if (tv) 17f else 15f, palette.text).apply {
                    setLineSpacing(dp(7).toFloat(), 1.12f)
                    setPadding(0, dp(8), 0, 0)
                }
                val scroll = ScrollView(context).apply { addView(note) }
                addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (tv) 200 else 150)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10); bottomMargin = dp(24)
            })
        }

        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(palette.text)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.surface2)
            visibility = View.GONE
        }
        content.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply { bottomMargin = dp(14) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var go: android.widget.Button
        go = TvUi.button(this, "Actualizar ahora", true) {
            startUpdate(url, go, progress)
        }
        go.textSize = if (tv) 21f else 18f
        goButton = go
        refreshPrimaryButtonLabel(go)
        buttons.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (tv) 62 else 54)))

        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!force) {
            secondary.addView(TvUi.button(this, "Más tarde") { finish() },
                LinearLayout.LayoutParams(0, dp(if (tv) 52 else 48), 1f))
        }
        secondary.addView(TvUi.button(this, "Descarga manual") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Pgyer.pageUrl)))
            } catch (e: Exception) {
                status.text = "Este dispositivo no tiene navegador disponible, visita " + Pgyer.pageUrl + " desde un móvil u ordenador"
                Diag.error("Update", "cannot open manual download page", e)
            }
        }, LinearLayout.LayoutParams(0, dp(if (tv) 52 else 48), 1f).apply {
            leftMargin = if (force) 0 else dp(12)
        })
        buttons.addView(secondary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        content.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        status = TvUi.text(this, "Se instala encima y no se pierden el banco de preguntas ni los ajustes", 12f, palette.text3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(status)

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(palette.bg) }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(
            if (tv) dp(720) else android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        setContentView(scroll)
        if (url.isEmpty()) go.isEnabled = false
        go.requestFocus()
        if (intent.getBooleanExtra("auto", false) && url.isNotEmpty()) {
            go.post { startUpdate(url, go, progress) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        val error = Prefs.updateInstallError(this)
        if (error.isNotEmpty()) {
            installHandedOff = false
            Prefs.setUpdateInProgress(this, false)
            status.text = "$error. Puedes pulsar «Actualizar ahora» para reintentar, o usar «Descarga manual» para instalarlo."
            goButton?.isEnabled = true
            goButton?.text = "Reintentar instalación"
        } else if (!ApkInstaller.canInstall(this)) {
            goButton?.text = "Permitir instalar apps"
            status.text = "Este sistema de TV bloquea por defecto que la app se instale actualizaciones; primero debes permitir que «题了个题» instale aplicaciones"
        } else if (goButton?.text?.toString() == "Permitir instalar apps") {
            goButton?.let { refreshPrimaryButtonLabel(it) }
            status.text = "Se instala encima y no se pierden el banco de preguntas ni los ajustes"
        }
    }

    override fun onDestroy() {
        if (!installHandedOff) {
            Prefs.setUpdateInProgress(this, false)
        }
        super.onDestroy()
    }

    private fun refreshPrimaryButtonLabel(button: TextView) {
        Thread {
            val downloaded = try { ApkInstaller.alreadyDownloaded(this, 0) } catch (_: Exception) { false }
            runOnUiThread {
                if (!isFinishing && button.isEnabled && (button.text == "Actualizar ahora" || button.text == "Permitir instalar apps")) {
                    button.text = if (downloaded) "Iniciar instalación" else "Actualizar ahora"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun label(text: String, size: Float, color: Int, bottomMarginDp: Int,
                      gravity: Int = Gravity.CENTER_HORIZONTAL): TextView {
        val v = TextView(this)
        v.text = text
        v.setTextColor(color)
        v.textSize = size
        v.gravity = gravity
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(bottomMarginDp)
        v.layoutParams = lp
        return v
    }

    
    private fun startUpdate(url: String, button: TextView, progress: android.widget.ProgressBar? = null) {
        if (!button.isEnabled) return
        if (url.isEmpty()) { button.text = "No hay dirección de descarga"; return }

        if (!ApkInstaller.canInstall(this)) {
            // Android 8+ autoriza por app: si no está activado, lanzar el instalador solo fallará; hay que abrirlo primero en ajustes
            status.text = "Primero permite que «题了个题» instale aplicaciones; cuando lo permitas, vuelve aquí y pulsa otra vez"
            try {
                startActivity(ApkInstaller.permissionIntent(this))
            } catch (e: Exception) {
                status.text = "Esta TV no encuentra la página de ajustes «Instalar apps desconocidas»; ve a Ajustes → Aplicaciones y permite que «题了个题» instale aplicaciones"
                Diag.warn("Install", "cannot open unknown-sources settings: ${e.javaClass.simpleName}")
            }
            return
        }

        installHandedOff = false
        Prefs.setUpdateInProgress(this, true)
        button.isEnabled = false
        button.text = "Preparando instalación…"
        Thread {
            try {
                val ready = ApkInstaller.apkFile(this)
                if (ApkInstaller.alreadyDownloaded(this, 0)) {
                    val installed = ApkInstaller.install(this, ready)
                    if (installed) {
                        installHandedOff = true
                    } else {
                        Prefs.setUpdateInProgress(this, false)
                    }
                    runOnUiThread {
                        if (installed) {
                            button.text = "Instalando…"
                        } else {
                            button.isEnabled = true
                            button.text = "Instalación fallida, reintentar"
                            status.text = ApkInstaller.lastError.ifEmpty { "El sistema no aceptó esta instalación" }
                            button.requestFocus()
                        }
                    }
                    return@Thread
                }

                runOnUiThread {
                    button.text = "Descargando 0%"
                    progress?.visibility = View.VISIBLE
                }
                val file = ApkInstaller.download(this, url, sha256, { source -> runOnUiThread { status.text = "Fuente de descarga: $source" } }) { percent ->
                    runOnUiThread {
                        button.text = if (percent < 0) "Descargando…" else if (percent < 100) "Descargando $percent%" else "Preparando instalación…"
                        if (percent >= 0) progress?.progress = percent.coerceIn(0, 100)
                    }
                }
                val installed = if (file != null) ApkInstaller.install(this, file) else false
                if (installed) {
                    installHandedOff = true
                } else {
                    Prefs.setUpdateInProgress(this, false)
                }
                runOnUiThread {
                    if (installed) {
                        button.text = "Instalando…"
                    } else {
                        button.isEnabled = true
                        button.text = if (file == null) "Descarga fallida, reintentar" else "Instalación fallida, reintentar"
                        status.text = (ApkInstaller.lastError.ifEmpty { if (file == null) "Descarga fallida, comprueba la red" else "El sistema no aceptó esta instalación" }) +
                            ". También puedes usar «Descarga manual» e instalarlo desde Pgyer."
                        progress?.visibility = View.GONE
                    }
                    button.requestFocus()
                }
            } catch (e: Exception) {
                Prefs.setUpdateInProgress(this, false)
                Diag.error("Update", "update task failed", e)
                runOnUiThread {
                    button.isEnabled = true
                    button.text = "Instalación fallida, reintentar"
                    status.text = "Error al preparar la actualización: ${e.message ?: e.javaClass.simpleName}"
                    progress?.visibility = View.GONE
                    button.requestFocus()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!force) finish() }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun solid(color: Int, radiusDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun gradient(from: Int, to: Int, radiusDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(from, to)
        ).apply { cornerRadius = dp(radiusDp).toFloat() }
}
