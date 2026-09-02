@file:Suppress("MissingPermission")

package com.gulshan.pocketprint.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.transport.BleTransport
import com.gulshan.pocketprint.transport.BluetoothTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    /** Live inquiry scan. Requires BLUETOOTH_SCAN on API 31+, location below it. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<Printer> = callbackFlow {
        val a = adapter
        if (a == null || !a.isEnabled || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let { trySend(toPrinter(it)) }
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        runCatching { a.startDiscovery() }

        awaitClose {
            runCatching { a.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * LE advertisement scan. BLE-only printers never answer a classic inquiry
     * and are usually not bonded either, so [scan] and [bondedDevices] cannot
     * see them at all -- this is the only way to discover them.
     *
     * On API 30 and below an LE scan additionally needs ACCESS_FINE_LOCATION
     * *and* system Location switched on, or it silently reports nothing.
     */
    @SuppressLint("MissingPermission")
    fun scanLe(): Flow<Printer> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled && hasPermission() }?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertised = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                val name = result.scanRecord?.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                // Advertising a known printer service is proof; otherwise fall
                // back to the name hints the classic path already uses.
                val isPrinter = advertised.any { it in PRINTER_SERVICE_UUIDS } ||
                    looksLikePrinter(name)
                if (isPrinter) trySend(toPrinter(result.device, BtLink.BLE, name))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
        }

        // Filtering on service UUID lets the controller do the work, but many
        // printers advertise nothing but a name, so scan unfiltered and sort it
        // out in the callback.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(emptyList<ScanFilter>(), settings, callback) }

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    private fun toPrinter(
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

    /**
     * Tags the printer with the radio the adapter reports. DEVICE_TYPE_UNKNOWN
     * and DEVICE_TYPE_DUAL both stay AUTO so the transport probes at print time.
     */
    @SuppressLint("MissingPermission")
    private fun toPrinter(device: BluetoothDevice): Printer =
        toPrinter(device, linkOf(device))

    @SuppressLint("MissingPermission")
    private fun linkOf(device: BluetoothDevice): BtLink =
        when (runCatching { device.type }.getOrNull()) {
            BluetoothDevice.DEVICE_TYPE_LE -> BtLink.BLE
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtLink.CLASSIC
            else -> BtLink.AUTO
        }

    companion object {
        /** Service UUIDs a BLE thermal printer is likely to advertise. */
        val PRINTER_SERVICE_UUIDS: Set<java.util.UUID> =
            BleTransport.KNOWN_ENDPOINTS.map { it.first }.toSet()

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
