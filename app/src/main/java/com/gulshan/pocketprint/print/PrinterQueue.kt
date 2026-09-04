package com.gulshan.pocketprint.print

import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * One queue per physical printer, shared by every path that prints.
 *
 * A thermal printer offers exactly one RFCOMM slot. Two jobs sent at once do
 * not interleave politely: the second fails to connect, or worse, connects to a
 * printer mid-stream and produces garbage from two documents spliced together.
 *
 * The in-app path used to serialise behind a mutex owned by the foreground
 * service, and the system print dialog's path did not participate in it at all,
 * so an in-app job and a job from Chrome's Print dialog would collide. Putting
 * the queue here, keyed by the device rather than by the saved-printer record,
 * means both paths wait on the same lock — and so would any third path added
 * later, which is the point of it not living in a caller.
 *
 * Keyed by device on purpose: two saved entries can point at the same hardware
 * (one saved as Classic, one as auto-detect, say), and the printer does not
 * care that the app filed them separately.
 */
object PrinterQueue {

    // Bounded by the number of distinct printers this process has printed to,
    // which for a phone is a handful. Not worth evicting.
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun keyOf(printer: Printer): String = when (val address = printer.address) {
        is PrinterAddress.Bluetooth -> "bt:${address.mac.lowercase()}"
        is PrinterAddress.Usb ->
            "usb:${address.vendorId}:${address.productId}:${address.serial.orEmpty()}"
        is PrinterAddress.Raw -> "raw:${address.host.lowercase()}:${address.port}"
        is PrinterAddress.Ipp -> "ipp:${address.host.lowercase()}:${address.port}${address.path}"
    }

    /**
     * Whether a job is currently holding this printer.
     *
     * Advisory, and racy by nature — the answer can be stale the instant it is
     * returned. It is used to tell the print dialog that a printer is busy,
     * where being briefly wrong is fine; it is never used to decide whether it
     * is safe to print, which is what [withPrinter] is for.
     */
    fun isBusy(printer: Printer): Boolean = locks[keyOf(printer)]?.isLocked == true

    /**
     * Runs [block] with exclusive use of the printer.
     *
     * [onWaiting] fires only when the job actually has to queue, so an
     * uncontended job does not tell the user it is waiting for itself.
     */
    suspend fun <T> withPrinter(
        printer: Printer,
        onWaiting: () -> Unit = {},
        onResumed: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        val mutex = locks.getOrPut(keyOf(printer)) { Mutex() }

        var queued = false
        if (!mutex.tryLock()) {
            queued = true
            onWaiting()
            mutex.lock()
        }

        return try {
            if (queued) onResumed()
            block()
        } finally {
            mutex.unlock()
        }
    }
}
