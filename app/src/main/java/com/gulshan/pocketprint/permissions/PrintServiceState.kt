package com.gulshan.pocketprint.permissions

import android.content.Context
import android.provider.Settings

/**
 * Whether Android's print subsystem has PocketPrint switched on.
 *
 * The app could not tell, which made its own instructions unanswerable: it told
 * people to go and enable a print service without ever being able to say
 * whether they had. Half the "it doesn't appear in Chrome's print dialog"
 * reports are this switch.
 *
 * There is no public API for it, so this reads the setting the system writes.
 * That read is not guaranteed to be permitted on every version, which is why
 * [Status.UNKNOWN] exists and is reported honestly rather than being collapsed
 * into "off" — telling somebody to turn on something already on is its own kind
 * of wrong.
 */
object PrintServiceState {

    enum class Status { ENABLED, DISABLED, UNKNOWN }

    private const val ENABLED_PRINT_SERVICES = "enabled_print_services"
    private const val DISABLED_PRINT_SERVICES = "disabled_print_services"

    fun status(context: Context): Status = classify(
        enabled = read(context, ENABLED_PRINT_SERVICES),
        disabled = read(context, DISABLED_PRINT_SERVICES),
        packageName = context.packageName,
    )

    /**
     * The reading, away from the settings lookup.
     *
     * Null means the setting could not be read at all, which is not the same as
     * an empty setting: one is "Android will not say", the other is "nothing is
     * enabled". Collapsing the first into "off" would tell people to go and
     * switch on something that is already on.
     */
    internal fun classify(enabled: String?, disabled: String?, packageName: String): Status = when {
        enabled != null && enabled.contains(packageName) -> Status.ENABLED
        disabled != null && disabled.contains(packageName) -> Status.DISABLED
        enabled != null -> Status.DISABLED
        else -> Status.UNKNOWN
    }

    private fun read(context: Context, key: String): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, key)
    }.getOrNull()
}
