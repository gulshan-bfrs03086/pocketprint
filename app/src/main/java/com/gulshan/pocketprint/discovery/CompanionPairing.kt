package com.gulshan.pocketprint.discovery

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import android.bluetooth.BluetoothAdapter
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.print.Diagnostics

/**
 * Pairing through Android's own device picker.
 *
 * Until now a printer had to be paired in Android Settings first, in a list
 * where nothing says which entry is the printer — a screen full of MAC
 * addresses and manufacturer strings, in an app that cannot help, before
 * PocketPrint gets to do anything at all. A BLE-only receipt printer could not
 * be added by any route, despite a complete GATT transport sitting here waiting
 * for one, because it never appears in that list.
 *
 * The Companion Device Manager hands the whole problem to the system: it scans,
 * it shows the picker, it bonds, and it returns the device. Two things follow
 * from that which are worth stating plainly. The app needs no scan permission
 * of its own — the system did the scanning — which is why this app no longer
 * asks for one. And the association survives a reboot, which the ad-hoc scan
 * this replaces did not.
 *
 * Available from Android 8. Below that, and on a device without the companion
 * feature, pairing is still Android Settings and the printer turns up in the
 * bonded list afterwards, which is what auto-setup has always worked from.
 */
object CompanionPairing {

    private const val TAG = "CompanionPairing"

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
            )

    /**
     * Opens the system picker.
     *
     * Deliberately unfiltered. Filtering by the SPP service UUID would show a
     * shorter and mostly correct list, and would also hide every printer whose
     * firmware does not advertise it — which is a good number of them, and the
     * failure would look like the printer being broken. The user knows which
     * device is theirs; the list only has to be reachable from here rather than
     * from three screens away in Settings.
     */
    fun request(
        context: Context,
        onPicker: (IntentSender) -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        if (!isSupported(context)) {
            onUnavailable(
                "This device has no companion device picker, so the printer has to be " +
                    "paired in Android's Bluetooth settings first. It will appear here " +
                    "afterwards.",
            )
            return
        }

        associateOnOreo(context, onPicker, onUnavailable)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun associateOnOreo(
        context: Context,
        onPicker: (IntentSender) -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            onUnavailable("The companion device picker is not available on this device.")
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().build())
            // False: somebody with two printers should get a list, not whichever
            // one the radio happened to hear from first.
            .setSingleDevice(false)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Superseded by onAssociationPending, which only exists from API 33.")
            override fun onDeviceFound(intentSender: IntentSender) {
                onPicker(intentSender)
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                onPicker(intentSender)
            }

            override fun onFailure(error: CharSequence?) {
                Diagnostics.record(TAG, "association failed: $error")
                onUnavailable(
                    error?.toString()
                        ?: "Android could not start the device picker. Pairing in " +
                            "Bluetooth settings still works.",
                )
            }
        }

        runCatching {
            manager.associate(request, callback, null)
        }.onFailure {
            Diagnostics.record(TAG, "associate threw: ${it.message}")
            onUnavailable(it.message ?: "The device picker could not be opened.")
        }
    }

    /**
     * The printer the user picked.
     *
     * Three shapes come back depending on the Android version and which filter
     * matched: an AssociationInfo from API 33, a BluetoothDevice for a classic
     * match, or a ScanResult for an LE one. The last of those is the whole
     * reason a BLE-only printer can now be added at all, and it is also how the
     * radio type is known without probing for it.
     */
    fun printerFrom(data: Intent?): Printer? {
        if (data == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val association = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
            val mac = association?.deviceMacAddress?.toString()
            if (!mac.isNullOrBlank()) {
                val device = runCatching {
                    BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac.uppercase())
                }.getOrNull() ?: return null
                return BluetoothDiscovery.printerFor(
                    device = device,
                    link = BluetoothDiscovery.linkOf(device),
                    advertisedName = association.displayName?.toString(),
                )
            }
        }

        @Suppress("DEPRECATION")
        val picked: Any? = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)

        return when (picked) {
            is BluetoothDevice -> BluetoothDiscovery.printerFor(picked, BtLink.CLASSIC)
            is ScanResult -> BluetoothDiscovery.printerFor(
                device = picked.device,
                // Found by an LE scan, so reach it over GATT rather than
                // spending a connect attempt discovering that RFCOMM times out.
                link = BtLink.BLE,
                advertisedName = picked.scanRecord?.deviceName,
            )
            else -> null
        }
    }
}
