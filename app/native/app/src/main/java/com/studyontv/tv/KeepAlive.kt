package com.studyontv.tv

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * 「后台保活」自检：只读取系统状态，不申请权限。
 * 品牌路径同时供开机权限引导和家长面板使用，避免两处文案漂移。
 */
object KeepAlive {
    fun ignoringBattery(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
    }

    fun notificationsEnabled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            false
        } else if (Build.VERSION.SDK_INT >= 24) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        } else true
    } catch (_: Exception) { true }

    fun usbDebugging(context: Context): Boolean = globalFlag(context, Settings.Global.ADB_ENABLED)
    fun developerOptions(context: Context): Boolean = globalFlag(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

    private fun globalFlag(context: Context, key: String): Boolean = try {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    } catch (_: Exception) { false }

    fun report(context: Context): JSONObject = JSONObject()
        .put("ignoringBattery", ignoringBattery(context))
        .put("notifications", notificationsEnabled(context))
        .put("usbDebugging", usbDebugging(context))
        .put("developerOptions", developerOptions(context))
        .put("sdk", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("brandTip", brandTip(BuildConfig.FLAVOR == "tv"))

    /** 按厂商给出自启动设置路径；同厂商的电视和手机路径不同。 */
    fun brandTip(tv: Boolean): String? {
        val name = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        if (tv) return when {
            name.contains("tcl") -> "TV TCL: Ajustes → Aplicaciones → 题了个题 → Inicio automático, activa «Permitir inicio automático»"
            name.contains("hisense") || name.contains("vidaa") -> "TV Hisense: Ajustes → Gestión de apps → 题了个题 → Permisos → Permitir inicio automático"
            name.contains("skyworth") || name.contains("coocaa") -> "Skyworth / Coocaa: Ajustes → Aplicaciones → 题了个题 → Permiso de inicio automático, establécelo en Permitir"
            // #141：HyperOS 电视有的版本把「应用管理」整个去掉了，家长找不到入口
            name.contains("xiaomi") || name.contains("redmi") -> "TV Xiaomi / Redmi: Ajustes → Aplicaciones → Gestión de apps → 题了个题, permite el inicio automático y pon la política de ahorro de energía en «Sin restricciones». Si tu versión del sistema no tiene «Gestión de apps», no pasa nada: basta con que «Servicio de control» e «Inicio automático» funcionen en esta página; si necesitas ir a los ajustes, pulsa «Abrir ajustes del sistema» abajo"
            name.contains("changhong") -> "TV Changhong: Ajustes → Gestión de apps → 题了个题 → Permitir ejecución en segundo plano"
            name.contains("huawei") || name.contains("honor") -> "Huawei / Honor Vision: Ajustes → Aplicaciones → Gestión de inicio de apps → 题了个题 → Gestión manual, permite todo"
            name.contains("sony") -> "TV Sony: Ajustes → Aplicaciones → 题了个题 → Batería → Sin restricciones"
            name.contains("samsung") -> "TV Samsung: Ajustes → Aplicaciones → 题了个题 → Batería → Sin restricciones"
            name.contains("lg") -> "TV LG: Ajustes → General → Aplicaciones → 题了个题 → Batería → Sin restricciones"
            name.contains("philips") -> "TV Philips: Ajustes → Aplicaciones → 题了个题 → Inicio automático / ejecución en segundo plano, establécelo en Permitir"
            name.contains("sharp") -> "TV Sharp: Ajustes → Gestión de apps → 题了个题 → Inicio automático, establécelo en Permitir"
            name.contains("konka") -> "TV Konka: Ajustes → Aplicaciones → 题了个题 → Inicio automático, establécelo en Permitir"
            name.contains("letv") -> "TV LeTV: Ajustes → Gestión de apps → 题了个题 → Inicio automático, establécelo en Permitir"
            else -> null
        }
        return when {
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                "Móvil Xiaomi / Redmi / POCO: Ajustes → Ajustes de apps → Gestión de apps → 题了个题 → Inicio automático; política de ahorro de energía → Sin restricciones"
            name.contains("huawei") || name.contains("honor") ->
                "Móvil Huawei / Honor: ① Ajustes → Aplicaciones → Gestión de inicio de apps → 题了个题 → Gestión manual, permite todo; ② Ajustes → Batería → Más ajustes de batería → Mantener la conexión de red durante la suspensión (para no perder la conexión con la pantalla apagada); ③ desliza la tarjeta de tareas recientes hacia abajo para bloquearla"
            name.contains("oppo") || name.contains("realme") || name.contains("oneplus") ->
                "OPPO / realme / OnePlus: Ajustes → Aplicaciones → Gestión de inicio automático → 题了个题; Batería → Permitir actividad en segundo plano"
            name.contains("vivo") || name.contains("iqoo") ->
                "vivo / iQOO: Ajustes → Aplicaciones y permisos → Gestión de permisos → Inicio automático → 题了个题"
            name.contains("samsung") ->
                "Móvil Samsung: Ajustes → Batería → Límites de uso en segundo plano → Aplicaciones que nunca se suspenden, añade 题了个题"
            name.contains("meizu") ->
                "Móvil Meizu: Ajustes → Gestión de apps → Permisos → Gestión en segundo plano → Permitir ejecución en segundo plano"
            else -> null
        }
    }
}
