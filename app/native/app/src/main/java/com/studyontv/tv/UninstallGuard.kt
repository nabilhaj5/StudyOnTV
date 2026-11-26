package com.studyontv.tv

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Protección contra desinstalación (Issue #4): una vez activada como «app de administración del dispositivo»,
 * el sistema no permite desinstalarla directamente, y el niño no podrá borrarla desde «Mis aplicaciones».
 * El padre debe desactivarla primero en «Apps de administración del dispositivo» de los ajustes del sistema
 * para poder desinstalarla. No se solicita ninguna política de administración.
 */
class UninstallGuard : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        context.getSharedPreferences("sotv", Context.MODE_PRIVATE).edit().putBoolean("uninstall_guard_enabled", true).apply()
        restoreOwnerPolicy(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        SystemSettingsGuard.onPotentialTamper(context)
        return "Si la desactivas, «题了个题» podrá desinstalarse y el control del tiempo de uso dejará de funcionar. Confirma que es el padre quien está haciendo esto."
    }

    companion object {
        fun component(context: Context) = ComponentName(context, UninstallGuard::class.java)
        fun owner(context: Context): Boolean = try {
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) { false }
        fun restoreOwnerPolicy(context: Context) {
            if (!owner(context)) return
            try {
                val enabled = context.getSharedPreferences("sotv", Context.MODE_PRIVATE).getBoolean("uninstall_guard_enabled", true)
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).setUninstallBlocked(component(context), context.packageName, enabled)
            } catch (e: Exception) { Diag.warn("Guard", "cannot restore device owner uninstall policy") }
        }
        fun setEnabled(context: Context, enabled: Boolean) {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (owner(context)) manager.setUninstallBlocked(component(context), context.packageName, enabled)
            else {
                require(!enabled) { "Autoriza el administrador del dispositivo en el dispositivo controlado" }
                manager.removeActiveAdmin(component(context))
            }
            context.getSharedPreferences("sotv", Context.MODE_PRIVATE).edit().putBoolean("uninstall_guard_enabled", enabled).apply()
        }
        fun active(context: Context): Boolean = try {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (manager.isDeviceOwnerApp(context.packageName)) manager.isUninstallBlocked(component(context), context.packageName)
            else manager.isAdminActive(component(context))
        } catch (_: Exception) { false }
        fun requestIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Una vez activado, el niño no podrá desinstalar «题了个题» directamente. El padre debe desactivarlo aquí primero para poder desinstalarla.")
    }
}
