package com.gulshan.pocketprint.printservice

import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.ServiceLocator
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.DuplexMode
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Orientation
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.print.JobListener
import com.gulshan.pocketprint.print.PrinterAvailability
import com.gulshan.pocketprint.render.Spool
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Registers our printers with Android's print framework, so any app's Print
 * dialog can reach a Bluetooth, USB, raw-socket or IPP printer that this app
 * knows about.
 *
 * The framework always hands us a PDF, whatever the source app was. Turning
 * that PDF into ESC/POS, TSPL, ZPL or PCL is the render pipeline's job.
 *
 * The user must switch this service on once, under
 * Settings -> Connected devices -> Printing.
 */
class PocketPrintService : PrintService() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "uncaught in print scope", t) },
    )
    private val activeJobs = ConcurrentHashMap<String, Job>()

    companion object {
        private const val TAG = "PocketPrintService"
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession =
        PocketDiscoverySession(this, scope)

    /**
     * The framework calls this on the main thread with a handle that must not
     * be touched anywhere else. PrintFramework turns it into plain values plus
     * a handle whose every method posts back to the looper, and that is the
     * only shape of it a coroutine ever sees.
     */
    override fun onPrintJobQueued(printJob: PrintJob) {
        val queued = PrintFramework.take(this, printJob) ?: return
        val options = optionsFrom(queued.info.attributes, queued.info.copies)

        val job = scope.launch {
            try {
                val printer = ServiceLocator.printerRepository(applicationContext)
                    .saved.first()
                    .firstOrNull { it.id == queued.localId }

                if (printer == null) {
                    queued.handle.fail(getString(R.string.system_printer_gone))
                    return@launch
                }

                val spooled = spoolDocument(queued.descriptor)
                if (spooled == null) {
                    queued.handle.fail(getString(R.string.system_document_unreadable))
                    return@launch
                }

                try {
                    when (
                        val result = ServiceLocator.printEngine(applicationContext).printPdf(
                            printer = printer,
                            pdf = spooled,
                            jobName = queued.label,
                            options = options,
                            // Without this the dialog shows "printing" while the
                            // job sits behind another one on the same printer,
                            // which reads as stuck. Blocked with a reason reads
                            // as waiting, which is what is actually happening.
                            listener = JobListener(
                                onWaitingForPrinter = { waiting ->
                                    if (waiting) {
                                        queued.handle.block(
                                            getString(
                                                R.string.system_waiting_for_printer,
                                                printer.displayName,
                                            ),
                                        )
                                    } else {
                                        queued.handle.resume()
                                    }
                                },
                            ),
                        )
                    ) {
                        // The framework has exactly two terminal states here,
                        // complete() and fail(), and no way to say "handed over,
                        // outcome unknown" - so an unconfirmed job has to be
                        // completed. The distinction is not lost: it is recorded
                        // honestly in the app's own job history, which is where
                        // the reason can actually be shown.
                        is PrintResult.Completed -> {
                            Log.i(TAG, "${printer.displayName} confirmed ${result.bytesSent} bytes")
                            queued.handle.complete()
                        }
                        is PrintResult.Sent -> {
                            Log.i(
                                TAG,
                                "sent ${result.bytesSent} bytes to ${printer.displayName}, " +
                                    "unconfirmed: ${result.reason}",
                            )
                            queued.handle.complete()
                        }
                        is PrintResult.Failure -> queued.handle.fail(result.message)
                    }
                } finally {
                    runCatching { spooled.delete() }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "job failed", t)
                queued.handle.fail(t.message ?: getString(R.string.system_print_failed))
            } finally {
                activeJobs.remove(queued.key)
            }
        }

        activeJobs[queued.key] = job
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        activeJobs.remove(PrintFramework.keyOf(printJob))?.cancel()
        PrintFramework.cancel(printJob)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Copies the framework's PDF out of the descriptor PrintFramework.take
     * captured on the main thread. Only the descriptor crosses threads.
     */
    private suspend fun spoolDocument(descriptor: ParcelFileDescriptor): File? =
        withContext(Dispatchers.IO) {
            val target = Spool.newFile(applicationContext, ".pdf")
            val copied = runCatching {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }.onFailure { Log.e(TAG, "spool failed", it) }.isSuccess

            runCatching { descriptor.close() }

            if (!copied || target.length() == 0L) {
                target.delete()
                null
            } else {
                target
            }
        }

    /** Translates the framework's PrintAttributes into our own options. */
    private fun optionsFrom(attributes: PrintAttributes?, copies: Int): PrintOptions {
        val media = attributes?.mediaSize
        val size = media?.let {
            MediaSize(
                id = it.id,
                label = it.getLabel(packageManager) ?: it.id,
                // PrintAttributes measures in thousandths of an inch.
                widthMicrons = Math.round(it.widthMils * 25.4f),
                heightMicrons = Math.round(it.heightMils * 25.4f),
            )
        } ?: MediaSize.A4

        val landscape = media?.isPortrait == false

        return PrintOptions(
            copies = copies.coerceAtLeast(1),
            mediaSize = size,
            orientation = if (landscape) Orientation.LANDSCAPE else Orientation.PORTRAIT,
            colorMode = if (attributes?.colorMode == PrintAttributes.COLOR_MODE_COLOR) {
                ColorMode.COLOR
            } else {
                ColorMode.MONOCHROME
            },
            duplex = when (attributes?.duplexMode) {
                PrintAttributes.DUPLEX_MODE_LONG_EDGE -> DuplexMode.LONG_EDGE
                PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> DuplexMode.SHORT_EDGE
                else -> DuplexMode.SIMPLEX
            },
            dpi = attributes?.resolution?.horizontalDpi?.takeIf { it > 0 } ?: 300,
        )
    }
}

/** Maps our saved printers into PrinterInfo objects the framework can show. */
internal fun buildPrinterInfo(
    printer: Printer,
    printerId: PrinterId,
    packageName: String,
    availability: PrinterAvailability,
): PrinterInfo {
    val capabilities = PrinterCapabilitiesInfo.Builder(printerId).apply {
        val sizes = printer.capabilities.mediaSizes.ifEmpty { listOf(MediaSize.A4) }
        sizes.forEachIndexed { index, size ->
            addMediaSize(
                PrintAttributes.MediaSize(
                    size.id,
                    size.label,
                    Math.round(size.widthMicrons / 25.4f),
                    Math.round(size.heightMicrons / 25.4f),
                ),
                index == 0,
            )
        }

        val resolutions = printer.capabilities.resolutionsDpi.ifEmpty { listOf(300) }
        resolutions.forEachIndexed { index, dpi ->
            addResolution(
                PrintAttributes.Resolution("dpi-$dpi", "$dpi dpi", dpi, dpi),
                index == 0,
            )
        }

        val colorModes = if (printer.capabilities.supportsColor) {
            PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME
        } else {
            PrintAttributes.COLOR_MODE_MONOCHROME
        }
        val defaultColor = if (printer.capabilities.supportsColor) {
            PrintAttributes.COLOR_MODE_COLOR
        } else {
            PrintAttributes.COLOR_MODE_MONOCHROME
        }
        setColorModes(colorModes, defaultColor)

        // Thermal and label printers print edge to edge; sheet printers do not.
        val margins = if (printer.capabilities.rasterWidthDots != null) {
            PrintAttributes.Margins.NO_MARGINS
        } else {
            PrintAttributes.Margins(200, 200, 200, 200)
        }
        setMinMargins(margins)
    }.build()

    // Published as IDLE forever until now, which is how someone ends up
    // tapping Print on a printer whose Bluetooth radio is switched off.
    val status = when (availability) {
        PrinterAvailability.IDLE -> PrinterInfo.STATUS_IDLE
        PrinterAvailability.BUSY -> PrinterInfo.STATUS_BUSY
        PrinterAvailability.UNAVAILABLE -> PrinterInfo.STATUS_UNAVAILABLE
    }

    return PrinterInfo.Builder(printerId, printer.displayName, status)
        .setCapabilities(capabilities)
        .setDescription(printer.makeAndModel ?: printer.subtitle)
        .build()
}
