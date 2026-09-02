package com.gulshan.pocketprint.discovery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.transport.UsbTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Enumerates USB-OTG attached printers and brokers the runtime permission. */
class UsbDiscovery(private val context: Context) {

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        private const val ACTION_USB_PERMISSION = "com.gulshan.pocketprint.USB_PERMISSION"
    }

    fun attachedPrinters(includeNonPrinterClass: Boolean = false): List<Printer> =
        usbManager.deviceList.values
            .filter { includeNonPrinterClass || UsbTransport.isPrinter(it) }
            .map { toPrinter(it) }

    fun hasPermission(printer: Printer): Boolean {
        val a = printer.address as? PrinterAddress.Usb ?: return false
        val device = UsbTransport.findDevice(context, a.vendorId, a.productId) ?: return false
        return usbManager.hasPermission(device)
    }

    /**
     * Shows the system USB permission dialog and suspends until the user
     * answers. Returns false if the device vanished or access was refused.
     */
    suspend fun requestPermission(printer: Printer): Boolean {
        val a = printer.address as? PrinterAddress.Usb ?: return false
        val device = UsbTransport.findDevice(context, a.vendorId, a.productId) ?: return false
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            // API 31 requires an explicit mutability flag on the PendingIntent.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
            )
            usbManager.requestPermission(device, pending)

            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    private fun toPrinter(device: UsbDevice): Printer {
        val name = runCatching { device.productName }.getOrNull()
            ?: "USB printer %04x:%04x".format(device.vendorId, device.productId)
        val manufacturer = runCatching { device.manufacturerName }.getOrNull()

        return Printer(
            id = "usb:${device.vendorId}:${device.productId}",
            displayName = name,
            address = PrinterAddress.Usb(
                vendorId = device.vendorId,
                productId = device.productId,
                serial = runCatching { device.serialNumber }.getOrNull(),
            ),
            makeAndModel = listOfNotNull(manufacturer, name).distinct().joinToString(" "),
            // USB page printers usually speak PCL or PostScript; label units their
            // own dialect. Default to raster-free PDF and let the user correct it.
            capabilities = PrinterCapabilities(
                languages = listOf(com.gulshan.pocketprint.model.PrintLanguage.PCL),
                resolutionsDpi = listOf(300),
            ),
            lastSeenEpochMs = System.currentTimeMillis(),
        )
    }
}
