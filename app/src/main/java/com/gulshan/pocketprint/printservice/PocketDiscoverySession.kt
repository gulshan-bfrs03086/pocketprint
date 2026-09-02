package com.gulshan.pocketprint.printservice

import android.os.Handler
import android.os.Looper
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.gulshan.pocketprint.ServiceLocator
import com.gulshan.pocketprint.model.Printer
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

    private val main = Handler(Looper.getMainLooper())
    private var watchJob: Job? = null
    private val known = java.util.concurrent.ConcurrentHashMap<String, Printer>()

    companion object {
        private const val TAG = "PocketDiscovery"
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.i(TAG, "onStartPrinterDiscovery, priority=${priorityList.size}")
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
    }

    /**
     * Called when the framework wants fresh capabilities. IPP printers get a
     * real query; everything else already carries static capabilities.
     */
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        scope.launch {
            val engine = ServiceLocator.printEngine(service.applicationContext)
            val repository = ServiceLocator.printerRepository(service.applicationContext)

            val refreshed = printerIds.mapNotNull { id ->
                val printer = known[id.localId] ?: repository.find(id.localId) ?: return@mapNotNull null
                val probed = engine.probe(printer)
                if (probed != printer) repository.save(probed)
                probed
            }
            if (refreshed.isNotEmpty()) publish(refreshed)
        }
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        scope.launch {
            val repository = ServiceLocator.printerRepository(service.applicationContext)
            val printer = repository.find(printerId.localId) ?: return@launch
            val probed = ServiceLocator.printEngine(service.applicationContext).probe(printer)
            publish(listOf(probed))
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

    override fun onDestroy() {
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * Withdraws printers the user has deleted or hidden. Without this the
     * framework keeps showing them in every app's print dialog indefinitely.
     */
    private fun retire(stillVisible: Set<String>) {
        val gone = known.keys.filterNot { it in stillVisible }
        if (gone.isEmpty()) return
        gone.forEach { known.remove(it) }
        // generatePrinterId is main-thread only, exactly like removePrinters.
        main.post {
            runCatching { removePrinters(gone.map { service.generatePrinterId(it) }) }
        }
    }

    /**
     * Publishes printers to the framework.
     *
     * Both generatePrinterId and addPrinters are annotated @MainThread and begin
     * with PrintService.throwIfNotCalledOnMainThread(), so the whole PrinterInfo
     * construction has to happen on the main looper — not just the final
     * addPrinters call. Building the ids on a worker threw IllegalAccessError
     * for every printer, which left the system print dialog searching forever.
     */
    private fun publish(printers: List<Printer>) {
        if (printers.isEmpty()) return
        printers.forEach { known[it.id] = it }

        main.post {
            val infos: List<PrinterInfo> = printers.mapNotNull { printer ->
                runCatching {
                    buildPrinterInfo(
                        printer = printer,
                        printerId = service.generatePrinterId(printer.id),
                        packageName = service.packageName,
                    )
                }.onFailure {
                    Log.w(TAG, "could not publish ${printer.displayName}", it)
                }.getOrNull()
            }
            Log.i(TAG, "publishing ${infos.size} printers to the framework")
            if (infos.isEmpty()) return@post
            runCatching { addPrinters(infos) }
                .onFailure { Log.w(TAG, "addPrinters rejected ${infos.size} printers", it) }
        }
    }
}
