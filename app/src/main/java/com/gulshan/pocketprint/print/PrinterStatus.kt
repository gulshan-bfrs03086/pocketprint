package com.gulshan.pocketprint.print

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.transport.BluetoothTransport
import com.gulshan.pocketprint.transport.UsbTransport

/** What the print dialog should say about a printer right now. */
enum class PrinterAvailability { IDLE, BUSY, UNAVAILABLE }

/**
 * Works out whether a printer can currently be reached.
 *
 * Every printer used to be published to Android's print dialog as STATUS_IDLE,
 * forever. Bluetooth switched off, the Bluetooth permission revoked, the USB
 * cable pulled — none of it reached the dialog, so people tapped Print on a
 * printer that could not possibly answer and got a failure a minute later.
 *
 * The bar for UNAVAILABLE is deliberately high: it greys the printer out, and a
 * printer that cannot be selected also cannot explain itself. So it is reserved
 * for conditions that definitely block a job and that the dialog itself cannot
 * do anything about. Anything recoverable — a USB device that is plugged in but
 * not yet permitted, say — stays selectable, because the failure message that
 * follows is more use than a greyed-out row with no explanation.
 */
object PrinterStatus {

    /**
     * The part that can be answered from local state alone: radios, cables and
     * permissions. Cheap enough to call while publishing a list, though it does
     * make binder calls, so keep it off the main thread.
     *
     * [networkStatus] is what a network printer reported when last asked, since
     * reachability over the LAN cannot be answered without I/O. Null means
     * nobody has asked, which is not the same as unreachable.
     */
    fun of(
        context: Context,
        printer: Printer,
        networkStatus: PrinterAvailability? = null,
    ): PrinterAvailability {
        if (PrinterQueue.isBusy(printer)) return PrinterAvailability.BUSY

        return when (printer.address) {
            is PrinterAddress.Bluetooth -> bluetooth(context)
            is PrinterAddress.Usb -> usb(context, printer)
            is PrinterAddress.Ipp, is PrinterAddress.Raw ->
                networkStatus ?: PrinterAvailability.IDLE
        }
    }

    /**
     * What an IPP printer said about itself, as an availability.
     *
     * Separated from the request that fetched it so the rules can be read - and
     * tested - without a printer. printer-state is an enum: 3 idle,
     * 4 processing, 5 stopped.
     *
     * Note that "stopped" and "not accepting jobs" are both UNAVAILABLE. A
     * stopped printer will still take the job over IPP and hold it, which looks
     * like success and prints nothing until someone walks over to it.
     */
    fun fromIpp(
        reachable: Boolean,
        acceptingJobs: Boolean,
        printerState: Int?,
    ): PrinterAvailability = when {
        !reachable -> PrinterAvailability.UNAVAILABLE
        !acceptingJobs -> PrinterAvailability.UNAVAILABLE
        printerState == 5 -> PrinterAvailability.UNAVAILABLE
        printerState == 4 -> PrinterAvailability.BUSY
        else -> PrinterAvailability.IDLE
    }

    private fun bluetooth(context: Context): PrinterAvailability {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return when {
            adapter == null -> PrinterAvailability.UNAVAILABLE
            !adapter.isEnabled -> PrinterAvailability.UNAVAILABLE
            !BluetoothTransport.hasConnectPermission(context) -> PrinterAvailability.UNAVAILABLE
            // Bonding is deliberately not checked. A BLE printer is often
            // reachable over GATT without ever being bonded, and treating those
            // as unavailable would hide printers that work.
            else -> PrinterAvailability.IDLE
        }
    }

    private fun usb(context: Context, printer: Printer): PrinterAvailability {
        val address = printer.address as? PrinterAddress.Usb
            ?: return PrinterAvailability.UNAVAILABLE
        val attached = UsbTransport.findDevice(context, address.vendorId, address.productId)
        // Unplugged is final; unpermitted is not, and is left selectable so the
        // job can fail with something the user can act on.
        return if (attached == null) PrinterAvailability.UNAVAILABLE else PrinterAvailability.IDLE
    }
}
