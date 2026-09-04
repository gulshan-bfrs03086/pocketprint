package com.gulshan.pocketprint

import com.gulshan.pocketprint.permissions.AppPermissions
import com.gulshan.pocketprint.permissions.PermissionStatus
import com.gulshan.pocketprint.permissions.PrintServiceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two readings that are easy to get wrong in the same way: treating "we do not
 * know" as "no". One would tell a first-time user to go and fix a permission
 * they have never been asked about; the other would tell them to switch on a
 * print service that is already on.
 */
class PermissionStateTest {

    private fun decide(
        granted: Boolean = false,
        rationale: Boolean = false,
        asked: Boolean = false,
    ) = AppPermissions.decide(
        anyRequested = true,
        granted = granted,
        anyRationale = rationale,
        alreadyAsked = asked,
    )

    @Test
    fun `a fresh install has not been refused, it has not been asked`() {
        // Not granted, no rationale to show — identical from the outside to a
        // permission refused twice. Reading it as blocked would open a
        // first-run session by telling somebody to go to app settings.
        assertEquals(PermissionStatus.ASKABLE, decide(asked = false))
    }

    @Test
    fun `refused after being asked is blocked, which is the state with no way out`() {
        assertEquals(PermissionStatus.BLOCKED, decide(asked = true))
    }

    @Test
    fun `a rationale means the system will still ask`() {
        assertEquals(PermissionStatus.ASKABLE, decide(rationale = true, asked = true))
    }

    @Test
    fun `granted wins over everything`() {
        assertEquals(PermissionStatus.GRANTED, decide(granted = true, asked = true))
    }

    @Test
    fun `a permission this Android version does not have is not a problem`() {
        assertEquals(
            PermissionStatus.GRANTED,
            AppPermissions.decide(
                anyRequested = false,
                granted = false,
                anyRationale = false,
                alreadyAsked = false,
            ),
        )
    }

    @Test
    fun `local network access is inert until the platform enforces it`() {
        // Empty on every version shipping today, which is what makes declaring
        // and requesting it now cost nothing: an empty list is GRANTED, so no
        // dialog appears and no banner shows until Android 17 puts LAN sockets
        // behind it.
        assertEquals(
            android.os.Build.VERSION.SDK_INT >= 37,
            AppPermissions.localNetwork.isNotEmpty(),
        )
    }

    @Test
    fun `the permission name is spelled the way the platform spells it`() {
        // Written out by hand because it does not exist in the SDK this builds
        // against. The build refuses to bump targetSdk to 37 without it being
        // in the manifest, which is the moment to check this against the real
        // SDK - so it is worth having the exact string pinned in one place.
        assertEquals(
            "android.permission.ACCESS_LOCAL_NETWORK",
            AppPermissions.ACCESS_LOCAL_NETWORK,
        )
    }

    @Test
    fun `the print service switch reads as enabled only when it says so`() {
        assertEquals(
            PrintServiceState.Status.ENABLED,
            PrintServiceState.classify(
                enabled = "com.gulshan.pocketprint/.printservice.PocketPrintService",
                disabled = null,
                packageName = "com.gulshan.pocketprint",
            ),
        )
    }

    @Test
    fun `an empty enabled list means off`() {
        assertEquals(
            PrintServiceState.Status.DISABLED,
            PrintServiceState.classify("", null, "com.gulshan.pocketprint"),
        )
        assertEquals(
            PrintServiceState.Status.DISABLED,
            PrintServiceState.classify("com.other/.Service", null, "com.gulshan.pocketprint"),
        )
    }

    @Test
    fun `a setting that cannot be read is unknown, never off`() {
        // Some Android versions will not hand this setting over. Claiming the
        // service is off would send people to fix something already working.
        assertEquals(
            PrintServiceState.Status.UNKNOWN,
            PrintServiceState.classify(null, null, "com.gulshan.pocketprint"),
        )
    }

    @Test
    fun `being on the disabled list is a definite no`() {
        assertEquals(
            PrintServiceState.Status.DISABLED,
            PrintServiceState.classify(
                enabled = null,
                disabled = "com.gulshan.pocketprint/.printservice.PocketPrintService",
                packageName = "com.gulshan.pocketprint",
            ),
        )
    }
}
