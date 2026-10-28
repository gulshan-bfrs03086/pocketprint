package com.gulshan.pocketprint.printservice

/**
 * Gets the system print framework to bind this app's print service again
 * after it has been left holding a binding that will never connect.
 *
 * How that happens, reproduced on a zltd N7L (Android 9), three times out of
 * three:
 *
 * 1. The package is in the stopped state - freshly installed and never opened,
 *    or force-stopped (from App info, or by `am force-stop`).
 * 2. Something makes the framework bind the service: another app's print
 *    dialog, or Settings > Printing > PocketPrint. On that ROM the bind to a
 *    stopped package is silently held: the service sits in ActivityManager's
 *    pending list with no process started, and the dialog searches. This much
 *    clears itself the moment the app is opened.
 * 3. The package is stopped or replaced again while that bind is still pending
 *    (`am force-stop`, `adb install -r`, an update). The pending service is
 *    brought down and its binding marked dead. Android 9's RemotePrintService
 *    only recovers from a service dying after it had connected; a binding that
 *    dies before connecting leaves it believing a bind is in progress, so it
 *    never binds again. Every print dialog then searches forever, opening the
 *    app does not help and neither does force-stopping it.
 *
 * Switching the service off and on in Settings fixes it, because that destroys
 * the framework's record of the service and makes a new one. An app cannot
 * write that setting, but it can disable its own service component, which
 * makes the print framework drop the service the same way, and enable it again.
 *
 * The catch, and the reason an earlier attempt at this showed no effect: the
 * package manager batches component changes and broadcasts them after a delay
 * - ten seconds on that device, measured by watching `dumpsys print`. Disable
 * and re-enable inside that window and the print framework sees one broadcast
 * for a component that is enabled, and changes nothing. On the device, a 0 s
 * and a 3 s gap left the wedge in place; a 20 s gap cleared it. So the
 * component stays disabled for [HOLD_MS], longer than that delay.
 *
 * This class is only the sequence, with the clock and the component switch
 * passed in, so that it can be tested without a device. PrintServiceRebind is
 * the Android side.
 */
internal class Rebind(
    private val component: ComponentSwitch,
    private val timer: Timer,
) {
    /** The service component's enabled state, as far as this needs to know it. */
    interface ComponentSwitch {
        fun isDisabled(): Boolean
        fun disable()
        /** Back to the manifest's default, which is enabled. */
        fun restore()
    }

    fun interface Timer {
        fun after(delayMs: Long, action: () -> Unit)
    }

    var running = false
        private set

    /**
     * Disables the service component and restores it [HOLD_MS] later, then
     * calls [onDone]. Returns false, and does nothing, if a rebind is already
     * running.
     */
    fun start(onDone: () -> Unit = {}): Boolean {
        if (running) return false
        running = true
        component.disable()
        timer.after(HOLD_MS) {
            component.restore()
            running = false
            onDone()
        }
        return true
    }

    /**
     * Run at every process start. If the process died between [start] and the
     * restore - the user swiped the app away inside the hold - the component
     * would otherwise stay disabled, and PocketPrint would be gone from every
     * print dialog with nothing to bring it back. Nothing else in the app ever
     * disables it, so a disabled component found here is always one of ours.
     */
    fun repairIfLeftDisabled(): Boolean {
        if (running || !component.isDisabled()) return false
        component.restore()
        return true
    }

    companion object {
        /**
         * Comfortably over the package manager's broadcast delay: ten seconds
         * on Android 9, measured; one second on later versions. Too short and
         * the rebind silently does nothing; too long only means PocketPrint is
         * missing from print dialogs for a little longer, once.
         */
        const val HOLD_MS = 15_000L
    }
}

/**
 * Whether opening the app should rebind the print service.
 *
 * Only when the app may have been stopped since it last checked - the
 * precondition for the wedge on the device it was found on - and only when the
 * print service is not running in this process. A running service means the
 * framework is connected to it, so there is nothing to repair, and disabling it
 * would only take PocketPrint out of a print dialog that is using it.
 */
internal fun shouldRebind(mayHaveBeenStopped: Boolean, serviceRunning: Boolean): Boolean =
    mayHaveBeenStopped && !serviceRunning
