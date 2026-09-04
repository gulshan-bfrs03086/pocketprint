@file:Suppress("MissingPermission")

package com.gulshan.pocketprint.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.transport.BluetoothTransport

/**
 * Finds Bluetooth printers. Paired devices are the reliable source; an active
 * scan is offered as well, but many thermal printers only advertise while in
 * pairing mode, so pairing in Android Settings first is the usual path.
 */
class BluetoothDiscovery(private val context: Context) {

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermission(): Boolean = BluetoothTransport.hasConnectPermission(context)

    /** Devices already paired in Android Settings. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Printer> {
        if (!hasPermission()) return emptyList()
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        val bonded = runCatching { a.bondedDevices.orEmpty().toList() }
            .getOrDefault(emptyList())

        // Prefer devices that declare the imaging/printer class or whose name
        // matches a known printer family; fall back to everything only when that
        // finds nothing, so an oddly named printer is still reachable.
        val likely = bonded.filter {
            isDeclaredPrinter(it) || looksLikePrinter(runCatching { it.name }.getOrNull())
        }
        return (likely.ifEmpty { bonded }).map { toPrinter(it) }
    }

    /*
     * There used to be two scans here: a classic inquiry and an LE
     * advertisement scan. Neither ever had a caller, and between them they were
     * the only reason this app asked for BLUETOOTH_SCAN, or for
     * ACCESS_FINE_LOCATION on Android 11 and below - the permission that once
     * made the package refuse to install on a rugged terminal with no GPS.
     *
     * CompanionPairing replaces both with the system's own picker, which scans
     * on the app's behalf and therefore needs no scan permission at all. What
     * is left here is the bonded list, which needs only BLUETOOTH_CONNECT.
     */

    /**
     * Tags the printer with the radio the adapter reports. DEVICE_TYPE_UNKNOWN
     * and DEVICE_TYPE_DUAL both stay AUTO so the transport probes at print time.
     */
    private fun toPrinter(device: BluetoothDevice): Printer =
        printerFor(device, linkOf(device))

    companion object {

        /**
         * One Bluetooth device, as a printer.
         *
         * Shared with the companion-device picker on purpose: a printer paired
         * through the system picker and the same printer seen later in the
         * bonded list have to come out with the same id, or the user ends up
         * with two entries for one machine and no idea which is which.
         */
        @SuppressLint("MissingPermission")
        fun printerFor(
            device: BluetoothDevice,
            link: BtLink,
            advertisedName: String? = null,
        ): Printer {
            val name = (advertisedName ?: runCatching { device.name }.getOrNull()).orEmpty()
                .ifBlank { device.address }
            return Printer(
                id = "bt:${device.address}",
                displayName = name,
                address = PrinterAddress.Bluetooth(mac = device.address, link = link),
                makeAndModel = advertisedName ?: runCatching { device.name }.getOrNull(),
                capabilities = guessCapabilities(name),
                lastSeenEpochMs = System.currentTimeMillis(),
            )
        }

        @SuppressLint("MissingPermission")
        fun linkOf(device: BluetoothDevice): BtLink =
            when (runCatching { device.type }.getOrNull()) {
                BluetoothDevice.DEVICE_TYPE_LE -> BtLink.BLE
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtLink.CLASSIC
                else -> BtLink.AUTO
            }

        private val PRINTER_NAME_HINTS = listOf(
            "print", "pos", "receipt", "label", "tsc", "zebra", "zq", "zd", "gprinter",
            "xprinter", "xp-", "rp", "mtp", "ep-", "bixolon", "epson", "star", "sunmi",
            "munbyn", "rongta", "goojprt", "thermal", "brother", "ql-", "pt-",
            // 4BARCODE desktop label printers report bare model names such as
            // "4B-2044PA", which match none of the generic hints above.
            "4b-", "4barcode", "204", "205", "3054", "honeywell", "argox", "citizen",
        )

        /** Bit 7 of the imaging minor class marks a printer. */
        @SuppressLint("MissingPermission")
        fun isDeclaredPrinter(device: BluetoothDevice): Boolean {
            val cls = runCatching { device.bluetoothClass }.getOrNull() ?: return false
            if (cls.majorDeviceClass != BluetoothClass.Device.Major.IMAGING) return false
            return (cls.deviceClass and 0x80) != 0
        }

        /**
         * Thermal printers rarely announce their command language, so infer it
         * from the model name. The user can override this in printer settings.
         */
        fun guessCapabilities(name: String): PrinterCapabilities {
            val n = name.lowercase()
            return when {
                // 4BARCODE (4B-*) desktop label printers speak TSPL, as do TSC's
                // own TE/TTP families that they are compatible with.
                n.contains("tsc") || n.contains("te2") || n.contains("ttp") ||
                    n.startsWith("4b-") || n.contains("4barcode") || n.contains("argox") ->
                    PrinterCapabilities.TSPL_LABEL
                n.contains("zebra") || n.startsWith("zq") || n.startsWith("zd") ||
                    n.startsWith("gk") || n.startsWith("gx") ->
                    PrinterCapabilities.ZPL_LABEL
                n.contains("58") -> PrinterCapabilities.ESC_POS_80MM.copy(rasterWidthDots = 384)
                else -> PrinterCapabilities.ESC_POS_80MM
            }
        }

        fun looksLikePrinter(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return PRINTER_NAME_HINTS.any { n.contains(it) }
        }
    }
}
