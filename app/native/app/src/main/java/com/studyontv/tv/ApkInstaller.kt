package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL




object ApkInstaller {

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun apkFile(ctx: Context): File = File(dir(ctx), "update.apk")

    
    @Volatile var lastError = ""
        private set

    @Suppress("DEPRECATION")
    private fun signatures(ctx: Context, info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else info.signatures
        return sigs?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet() ?: emptySet()
    }

    /** 包名一致、版本更高、签名与当前安装的版本一致（镜像下载也不会被替换成别人的包） */
    @Suppress("DEPRECATION")
    private fun validUpdate(ctx: Context, file: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: run { lastError = "El paquete de instalación está dañado o incompleto"; return false }
        archive.applicationInfo?.let { it.sourceDir = file.absolutePath; it.publicSourceDir = file.absolutePath }
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        if (archive.packageName != ctx.packageName) { lastError = "El paquete de instalación no corresponde a esta aplicación"; return false }
        if (archive.versionCode <= current.versionCode) { lastError = "La versión del paquete no es más reciente que la actual"; return false }
        val mine = signatures(ctx, current)
        val theirs = signatures(ctx, archive)
        if (mine.isEmpty() || theirs.isEmpty() || mine.intersect(theirs).isEmpty()) { lastError = "La firma del paquete no coincide con la versión actual, se ha rechazado"; return false }
        return true
    }

    fun alreadyDownloaded(ctx: Context, sizeHint: Long): Boolean {
        val f = apkFile(ctx)
        return f.exists() && (sizeHint <= 0 || f.length() == sizeHint) && validUpdate(ctx, f)
    }

    /** 依次尝试直连和各个加速镜像，任一线路成功即返回；若蒲公英签名链接过期返回 HTML，自动刷新一次下载地址重试 */
    fun download(ctx: Context, url: String, sha256: String, onSource: (String) -> Unit, onProgress: (Int) -> Unit): File? {
        if (LocalOnlyGuard.enabled) {
            lastError = "La versión de privacidad local (EU Local-Only) ha desactivado la descarga e instalación desde la app"
            return null
        }
        lastError = ""
        for ((name, candidate) in Updater.downloadCandidates(ctx, url)) {
            onSource(name)
            val file = downloadFrom(ctx, candidate, sha256, onProgress)
            if (file != null) { Prefs.setUpdateLastSource(ctx, name); return file }
            if (lastError.contains("firma") || lastError.contains("verificación")) android.util.Log.w("StudyOnTV", "Rejected update from $name: $lastError")
        }
        // #183：蒲公英 downloadURL 带有时效签名（?sig=...），缓存过期后会返回 HTML 网页；自动重新检查一次拿到新直链再试
        if (url.contains("pgyer.com/", ignoreCase = true) && !lastError.contains("firma no coincide con la versión actual")) {
            val refreshed = try { Updater.checkNow(ctx).first } catch (_: Exception) { null }
            val freshUrl = refreshed?.url.orEmpty()
            if (freshUrl.isNotEmpty() && freshUrl != url) {
                Diag.info("Update", "Pgyer download url refreshed after failure ($lastError), retrying")
                lastError = ""
                onSource("Pgyer (actualizado)")
                val retryFile = downloadFrom(ctx, freshUrl, refreshed?.sha256.orEmpty().ifEmpty { sha256 }, onProgress)
                if (retryFile != null) { Prefs.setUpdateLastSource(ctx, "Pgyer"); return retryFile }
            }
        }
        return null
    }

    private fun downloadFrom(ctx: Context, url: String, sha256: String, onProgress: (Int) -> Unit): File? {
        val target = apkFile(ctx)
        val partial = File(dir(ctx), "update.pending.apk")
        var conn: HttpURLConnection? = null
        try {
            var currentUrl = URL(url)
            val cookies = LinkedHashMap<String, String>()
            var redirectCode = 0
            var redirectCount = 0
            while (true) {
                conn?.disconnect()
                conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 60000   // 蒲公英 APK 文件大，慢网需要更长时间
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", Pgyer.USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    if (cookies.isNotEmpty() && currentUrl.host.endsWith("pgyer.com", ignoreCase = true)) {
                        setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }
                }
                redirectCode = conn!!.responseCode
                // 记录同域跳转的 Cookie（如蒲公英 WAF / 会话 Cookie）
                conn!!.headerFields["Set-Cookie"]?.forEach { raw ->
                    val pair = raw.substringBefore(';').trim()
                    val eq = pair.indexOf('=')
                    if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                }
                if (redirectCode in listOf(301, 302, 303, 307, 308) && redirectCount++ < 6) {
                    val loc = conn!!.getHeaderField("Location") ?: break
                    val nextUrl = URL(currentUrl, loc)
                    if (nextUrl.protocol !in listOf("http", "https")) {
                        lastError = "Protocolo de redirección no soportado: ${nextUrl.protocol}"
                        return null
                    }
                    currentUrl = nextUrl
                    continue
                }
                break
            }
            if (redirectCode !in 200..299) { lastError = "Error de descarga (HTTP $redirectCode)"; return null }
            val contentType = conn!!.contentType.orEmpty().lowercase(java.util.Locale.US)
            if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                lastError = "El enlace de descarga ha caducado o ha devuelto una página web ($contentType). Inténtalo de nuevo"
                Diag.warn("Update", "download rejected non-apk content-type=$contentType url=${currentUrl.host}")
                return null
            }
            val total = conn!!.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            conn!!.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var shown = -1
                    var checkedMagic = false
                    while (input.read(buf).also { read = it } > 0) {
                        if (!checkedMagic) {
                            checkedMagic = true
                            // APK 必然以 ZIP 魔数 PK (0x50 0x4B) 开头，若返回 HTML 立即中止
                            if (read < 2 || buf[0] != 0x50.toByte() || buf[1] != 0x4B.toByte()) {
                                lastError = "El contenido descargado no es un paquete APK válido (puede que el enlace haya caducado)"
                                Diag.warn("Update", "download rejected non-zip magic from ${currentUrl.host}")
                                return null
                            }
                        }
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (percent != shown) { shown = percent; onProgress(percent) }
                    }
                    output.flush()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && !actual.equals(sha256, true)) { lastError = "Error de verificación del paquete (SHA-256 no coincide)"; return null }
            if (!validUpdate(ctx, partial)) return null
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) { lastError = "No se puede guardar el paquete de instalación"; return null }
            onProgress(100)
            return target
        } catch (e: Exception) {
            lastError = "Descarga interrumpida: " + (e.message ?: e.javaClass.simpleName).take(80)
            return null
        } finally {
            partial.delete()
            conn?.disconnect()
        }
    }

    fun install(ctx: Context, file: File): Boolean {
        lastError = ""
        Prefs.setUpdateInstallError(ctx, "")
        if (!canInstall(ctx)) {
            // Android 8 起「安装未知应用」是按应用授权的，没开的话调起安装器只会失败，先把话说清楚
            lastError = "El sistema aún no permite que «StudyOnTV» instale aplicaciones"
            Diag.warn("Install", "canRequestPackageInstalls=false, install not started")
            return false
        }
        GateService.allowInstallPage()
        if (installSession(ctx, file)) return true
        return installView(ctx, file)
    }

    /** Android 8+ 需要「允许安装未知应用」；更老的版本是全局的「未知来源」，这里不做判断 */
    fun canInstall(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) { true }

    /** 「允许安装未知应用」的系统设置页；各家电视入口不同，失败就退到本应用详情页 */
    fun permissionIntent(ctx: Context): Intent {
        val direct = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:" + ctx.packageName))
        if (ctx.packageManager.resolveActivity(direct, 0) != null) return direct
        return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + ctx.packageName))
    }

    /**
     * 优先走 PackageInstaller 会话：安装结果会回调到 [UpdateInstallReceiver]，失败原因能落到日志和面板里。
     * 少数定制电视的 PackageInstaller 有问题（创建会话抛异常），这时退回 ACTION_VIEW 交给系统安装器。
     */
    private fun installSession(ctx: Context, file: File): Boolean {
        var sessionId = -1
        val installer = ctx.packageManager.packageInstaller
        try {
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                val flags = if (Build.VERSION.SDK_INT >= 31)
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                val pending = android.app.PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            Diag.info("Install", "install session committed size=${file.length()} api=${Build.VERSION.SDK_INT}")
            return true
        } catch (e: Exception) {
            if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) { }
            Diag.warn("Install", "session install failed, falling back to ACTION_VIEW: ${e.javaClass.simpleName} ${e.message}")
            lastError = "Fallo en la sesión de instalación: " + (e.message ?: e.javaClass.simpleName).take(80)
            return false
        }
    }

    /** 会话走不通时（例如电视限制了后台拉起确认页）退回系统安装器 */
    fun fallbackToView(ctx: Context): Boolean {
        val file = apkFile(ctx)
        if (!file.exists()) { lastError = "El paquete de instalación se ha perdido, vuelve a descargarlo"; return false }
        return installView(ctx, file)
    }

    /** 老路径：把安装包交给系统安装器。没有安装结果的回调，只能保证「已调起」 */
    private fun installView(ctx: Context, file: File): Boolean {
        return try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ApkProvider.uriFor(ctx, file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            lastError = ""
            Diag.info("Install", "ACTION_VIEW installer started")
            true
        } catch (e: Exception) {
            lastError = "Esta TV no tiene un instalador disponible, usa «Descarga manual» para instalarlo en la TV"
            Diag.error("Install", "no installer activity", e)
            false
        }
    }

    const val ACTION_INSTALL_RESULT = "com.studyontv.tv.INSTALL_RESULT"
}
