package com.gulshan.pocketprint.transport

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.gulshan.pocketprint.model.PrinterAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * USB printer class (bInterfaceClass 7) over OTG. Payload goes out on the
 * interface's bulk OUT endpoint; bidirectional printers also expose a bulk IN
 * endpoint carrying status.
 */
class UsbTransport(
    context: Context,
    private val address: PrinterAddress.Usb,
) : PrinterTransport {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var endpointOut: UsbEndpoint? = null
    private var endpointIn: UsbEndpoint? = null

    override val description get() = "usb://%04x:%04x".format(address.vendorId, address.productId)

    companion object {
        const val USB_CLASS_PRINTER = 7

        /** Every USB printer interface on a device, ignoring composite extras. */
        fun printerInterfaces(device: UsbDevice): List<UsbInterface> =
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == USB_CLASS_PRINTER }

        fun isPrinter(device: UsbDevice): Boolean = printerInterfaces(device).isNotEmpty()

        fun findDevice(context: Context, vendorId: Int, productId: Int): UsbDevice? {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return manager.deviceList.values.firstOrNull {
                it.vendorId == vendorId && it.productId == productId
            }
        }
    }

    override suspend fun open() = withContext(Dispatchers.IO) {
        val found = usbManager.deviceList.values.firstOrNull {
            it.vendorId == address.vendorId && it.productId == address.productId
        } ?: throw TransportException("USB printer ${description} is not connected")

        if (!usbManager.hasPermission(found)) {
            throw TransportException("USB permission not granted for ${found.deviceName}")
        }

        // Prefer a true printer-class interface, but fall back to any interface
        // that exposes bulk endpoints, since some label printers misdeclare.
        val candidate = printerInterfaces(found).firstOrNull()
            ?: (0 until found.interfaceCount).map { found.getInterface(it) }
                .firstOrNull { hasBulkOut(it) }
            ?: throw TransportException("No printer interface on ${found.deviceName}")

        val conn = usbManager.openDevice(found)
            ?: throw TransportException("Could not open ${found.deviceName}")

        if (!conn.claimInterface(candidate, true)) {
            conn.close()
            throw TransportException("Could not claim USB interface")
        }

        var out: UsbEndpoint? = null
        var input: UsbEndpoint? = null
        for (i in 0 until candidate.endpointCount) {
            val ep = candidate.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) out = out ?: ep
            if (ep.direction == UsbConstants.USB_DIR_IN) input = input ?: ep
        }

        if (out == null) {
            conn.releaseInterface(candidate)
            conn.close()
            throw TransportException("USB interface has no bulk OUT endpoint")
        }

        device = found
        connection = conn
        iface = candidate
        endpointOut = out
        endpointIn = input
    }

    private fun hasBulkOut(candidate: UsbInterface): Boolean =
        (0 until candidate.endpointCount).any {
            val ep = candidate.getEndpoint(it)
            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT
        }

    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val conn = connection ?: throw TransportException("USB device not open")
            val ep = endpointOut ?: throw TransportException("USB endpoint not open")

            // Stay at or below the endpoint's packet size to avoid stalls.
            val chunk = ep.maxPacketSize.takeIf { it > 0 }?.times(8) ?: 4096
            val buf = ByteArray(chunk)
            var total = 0L

            source.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    var offset = 0
                    while (offset < n) {
                        val sent = conn.bulkTransfer(ep, buf, offset, n - offset, 10_000)
                        if (sent < 0) throw TransportException("USB bulk transfer failed")
                        offset += sent
                    }
                    total += n
                    onProgress(total)
                }
            }
            total
        }

    override suspend fun finish() {
        // Bulk transfers complete synchronously, so only a short settle is
        // needed for the printer to consume its endpoint buffer.
        kotlinx.coroutines.delay(250)
    }

    override suspend fun readAvailable(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext ByteArray(0)
        val ep = endpointIn ?: return@withContext ByteArray(0)
        val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
        val n = conn.bulkTransfer(ep, buf, buf.size, timeoutMs.toInt())
        if (n > 0) buf.copyOf(n) else ByteArray(0)
    }

    override fun close() {
        val conn = connection
        val i = iface
        if (conn != null && i != null) runCatching { conn.releaseInterface(i) }
        runCatching { conn?.close() }
        connection = null
        iface = null
        endpointOut = null
        endpointIn = null
        device = null
    }
}
