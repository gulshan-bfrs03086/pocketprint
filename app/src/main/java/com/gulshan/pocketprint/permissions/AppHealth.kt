package com.gulshan.pocketprint.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android is quietly turning this app off.
 *
 * The steady state this app is aiming for is that nobody ever opens it again:
 * a printer is set up once, and everything after that happens from inside
 * Gmail, Chrome and the share sheet. Android does not count a bound print
 * service as using the app. So after roughly three months of that intended,
 * correct usage it hibernates the app and revokes its permissions — and the
 * next print fails inside somebody else's app, with an error that app has no
 * way to explain.
 *
 * There is no way to warn somebody who is not looking, which is precisely the
 * situation. All that can be done is to say so plainly whenever they do open
 * the app, and to offer the switch that stops it.
 */
object AppHealth {

    enum class Hibernation {
        /** Exempt: Android will leave the app alone. */
        EXEMPT,

        /** Android will hibernate the app and revoke its permissions. */
        WILL_HIBERNATE,

        /** No auto-revoke on this Android version. */
        NOT_APPLICABLE,
    }

    @Suppress("DEPRECATION")
    fun hibernation(context: Context): Hibernation = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> Hibernation.NOT_APPLICABLE
        runCatching { context.packageManager.isAutoRevokeWhitelisted }.getOrDefault(false) ->
            Hibernation.EXEMPT
        else -> Hibernation.WILL_HIBERNATE
    }

    /**
     * Whether the app is exempt from Doze and app standby.
     *
     * Read only. The intent that asks for this exemption directly needs the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission, which Play restricts to
     * a short list of app categories that a printing app is not on — so the
     * offer is a route to the settings screen, not a dialog.
     */
    fun ignoresBatteryOptimisation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching {
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
    }

    /**
     * Opens wherever this version of Android keeps the hibernation switch.
     *
     * From Android 12 it lives in App info as "Pause app activity if unused";
     * Android 11 had a dedicated intent for it.
     */
    @Suppress("DEPRECATION")
    fun openHibernationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            Intent(
                Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
                Uri.fromParts("package", context.packageName, null),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { AppPermissions.openAppSettings(context) }
    }

    fun openBatterySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppPermissions.openAppSettings(context) }
    }
}
