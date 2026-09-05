package com.gulshan.pocketprint.printservice

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.print.PrinterId
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import androidx.core.content.ContextCompat
import com.gulshan.pocketprint.ServiceLocator
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.print.PrinterAvailability
import com.gulshan.pocketprint.print.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Feeds saved printers to the Android print framework.
 *
 * Only printers the user has saved and marked as system-visible are published.
 * Live mDNS results are deliberately excluded: the framework caches printers
 * aggressively, and a list that changes under it produces a confusing dialog.
 */
class PocketDiscoverySession(
    private val service: PrintService,
    private val scope: CoroutineScope,
) : PrinterDiscoverySession() {

    // Every generatePrinterId, addPrinters and removePrinters for this session
    // goes through here, and nowhere else - see PrintFramework.
    private val publisher by lazy { PrintFramework.Publisher(service, this) }
    private var watchJob: Job? = null
    private val known = java.util.concurrent.ConcurrentHashMap<String, Printer>()

    /**
     * The last answer a network printer gave about itself. Reachability over
     * the LAN cannot be decided without I/O, so it is remembered from the last
     * probe rather than guessed at publish time. Absent means never asked,
     * which is not the same as unreachable.
     */
    private val networkStatus = java.util.concurrent.ConcurrentHashMap<String, PrinterAvailability>()

    /**
     * A Bluetooth printer's availability is entirely about the radio, and the
     * radio changes without anything else in this app happening. Without this
     * the dialog keeps offering a Bluetooth printer for as long as it stays
     * open after the user turns Bluetooth off.
     */
    private val bluetoothWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            Log.i(TAG, "bluetooth state changed, refreshing printer status")
            scope.launch { publish(known.values.toList()) }
        }
    }
    private var watchingBluetooth = false

    companion object {
        private const val TAG = "PocketDiscovery"
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.i(TAG, "onStartPrinterDiscovery, priority=${priorityList.size}")
        startWatchingBluetooth()
        watchJob?.cancel()
        watchJob = scope.launch {
            ServiceLocator.printerRepository(service.applicationContext)
                .saved
                .collectLatest { printers ->
                    Log.i(TAG, "saved printers emitted: ${printers.size}")
                    val visible = printers.filter { it.exposeToSystem }
                    retire(visible.map { it.id }.toSet())
                    publish(visible)
                }
        }
    }

    override fun onStopPrinterDiscovery() {
        watchJob?.cancel()
        watchJob = null
        stopWatchingBluetooth()
    }

    private fun startWatchingBluetooth() {
        if (watchingBluetooth) return
        val registered = runCatching {
            ContextCompat.registerReceiver(
                service.applicationContext,
                bluetoothWatcher,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        // Not fatal if it fails: the status is still correct every time the
        // list is republished, just not the instant the radio changes.
        if (registered) watchingBluetooth = true
    }

    private fun stopWatchingBluetooth() {
        if (!watchingBluetooth) return
        runCatching { service.applicationContext.unregisterReceiver(bluetoothWatcher) }
        watchingBluetooth = false
    }

    /**
     * Called when the framework wants fresh capabilities. IPP printers get a
     * real query; everything else already carries static capabilities.
     */
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        // The list belongs to the framework; read it before leaving its thread.
        val localIds = printerIds.map { it.localId }
        scope.launch {
            val engine = ServiceLocator.printEngine(service.applicationContext)
            val repository = ServiceLocator.printerRepository(service.applicationContext)

            val refreshed = localIds.mapNotNull { localId ->
                val printer = known[localId] ?: repository.find(localId) ?: return@mapNotNull null
                val probed = engine.probe(printer)
                if (probed != printer) repository.save(probed)
                engine.networkStatus(probed)?.let { networkStatus[probed.id] = it }
                probed
            }
            if (refreshed.isNotEmpty()) publish(refreshed)
        }
    }

    /**
     * The framework calls this while a printer is on screen, which is exactly
     * when it is worth spending a round trip to ask a network printer whether
     * it is idle, printing or stopped.
     */
    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        val localId = printerId.localId
        scope.launch {
            val repository = ServiceLocator.printerRepository(service.applicationContext)
            val printer = repository.find(localId) ?: return@launch
            val engine = ServiceLocator.printEngine(service.applicationContext)
            val probed = engine.probe(printer)
            engine.networkStatus(probed)?.let { networkStatus[probed.id] = it }
            publish(listOf(probed))
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

    override fun onDestroy() {
        watchJob?.cancel()
        watchJob = null
        stopWatchingBluetooth()
    }

    /**
     * Withdraws printers the user has deleted or hidden. Without this the
     * framework keeps showing them in every app's print dialog indefinitely.
     */
    private fun retire(stillVisible: Set<String>) {
        val gone = known.keys.filterNot { it in stillVisible }
        if (gone.isEmpty()) return
        gone.forEach { known.remove(it); networkStatus.remove(it) }
        publisher.retire(gone)
    }

    /**
     * Publishes printers to the framework.
     *
     * generatePrinterId and addPrinters both begin with
     * PrintService.throwIfNotCalledOnMainThread() - neither carries an
     * @MainThread annotation, so no lint check will ever say so. Building the
     * ids on a worker threw IllegalAccessError for every printer, runCatching
     * swallowed it, and the system print dialog searched forever. The whole
     * PrinterInfo construction now happens inside PrintFramework.Publisher, on
     * the main looper, and this class holds no PrinterId of its own.
     */
    private fun publish(printers: List<Printer>) {
        if (printers.isEmpty()) return
        printers.forEach { known[it.id] = it }

        // Availability is worked out here, off the main thread, because the
        // radio and USB checks are binder calls. Only the finished verdict
        // crosses onto the looper with the rest of the PrinterInfo build.
        val availability = printers.associate { printer ->
            printer.id to PrinterStatus.of(
                context = service.applicationContext,
                printer = printer,
                networkStatus = networkStatus[printer.id],
            )
        }
        publisher.publish(printers, availability)
    }
}
