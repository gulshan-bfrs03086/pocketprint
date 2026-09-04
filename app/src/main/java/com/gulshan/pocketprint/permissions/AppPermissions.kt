package com.gulshan.pocketprint.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Where the app stands with a permission. */
enum class PermissionStatus {
    GRANTED,

    /** Not granted, but the system will still show the dialog. */
    ASKABLE,

    /**
     * Denied to the point where the system will not ask again. From Android 11
     * that takes two refusals, and after it the only route is app settings —
     * which is precisely the state this app used to leave people in with no way
     * to find out or get out.
     */
    BLOCKED,
}

/**
 * The permissions this app needs, and when.
 *
 * Every one of them used to be requested on the first frame, before the user
 * had seen what the app was for, with an empty result handler. Two refusals is
 * permanent on modern Android, and there was nothing anywhere that noticed, so
 * the app simply stopped being able to see Bluetooth printers for good.
 */
object AppPermissions {

    /**
     * Reaching a Bluetooth printer. Split at API 31; before that, scanning
     * required the location permission, which is why the legacy build asks for
     * something the modern one does not.
     */
    val bluetooth: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Showing job progress, and the Cancel action that goes with it. */
    val notifications: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: List<String>): Boolean =
        permissions.all { isGranted(context, it) }

    /**
     * [alreadyAsked] has to be remembered by the caller: on a fresh install a
     * permission that has never been requested looks exactly like one that has
     * been refused twice — not granted, no rationale to show — and telling the
     * user to go to app settings when they have not been asked yet would be
     * nonsense.
     */
    fun status(
        activity: Activity,
        permissions: List<String>,
        alreadyAsked: Boolean,
    ): PermissionStatus = decide(
        anyRequested = permissions.isNotEmpty(),
        granted = allGranted(activity, permissions),
        anyRationale = permissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        },
        alreadyAsked = alreadyAsked,
    )

    /**
     * The decision on its own, away from the Android calls that produce its
     * inputs.
     *
     * The interesting row is the last one. A permission never requested and a
     * permission refused twice look identical from the outside - not granted,
     * no rationale to offer - and only one of them should send somebody to app
     * settings. Getting that backwards on a fresh install means telling a
     * first-time user to go and fix a setting they have not been asked about.
     */
    internal fun decide(
        anyRequested: Boolean,
        granted: Boolean,
        anyRationale: Boolean,
        alreadyAsked: Boolean,
    ): PermissionStatus = when {
        !anyRequested -> PermissionStatus.GRANTED
        granted -> PermissionStatus.GRANTED
        anyRationale -> PermissionStatus.ASKABLE
        alreadyAsked -> PermissionStatus.BLOCKED
        else -> PermissionStatus.ASKABLE
    }

    /** The only route back once the system has stopped asking. */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Asks for a permission at the moment it is needed, and reports the answer. */
fun interface PermissionRequester {
    fun ensure(permissions: List<String>, onResult: (granted: Boolean) -> Unit)
}

/**
 * A requester bound to this composition.
 *
 * The point of it is timing. Asking for Bluetooth as the app opens is a dialog
 * about nothing; asking when somebody taps "Set up my printer" is a dialog
 * about the thing they just asked for, which is the difference between a grant
 * and a refusal.
 *
 * [onAsked] fires with the permissions actually put to the user, so the caller
 * can remember that it asked — see [AppPermissions.status].
 */
@Composable
fun rememberPermissionRequester(onAsked: (List<String>) -> Unit = {}): PermissionRequester {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        pending.value?.invoke(granted)
        pending.value = null
    }

    return remember(launcher, context) {
        PermissionRequester { permissions, onResult ->
            val missing = permissions.filterNot { AppPermissions.isGranted(context, it) }
            if (missing.isEmpty()) {
                onResult(true)
            } else {
                onAsked(missing)
                pending.value = onResult
                launcher.launch(missing.toTypedArray())
            }
        }
    }
}
