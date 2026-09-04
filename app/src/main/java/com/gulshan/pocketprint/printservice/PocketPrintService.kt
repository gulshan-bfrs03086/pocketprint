package com.gulshan.pocketprint.printservice

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.gulshan.pocketprint.ServiceLocator
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.DuplexMode
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Orientation
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.print.JobListener
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
     * Every accessor on PrintJob and PrintDocument begins with
     * PrintService.throwIfNotCalledOnMainThread(), so the job handle must not be
     * touched from a worker. This callback already runs on the main thread, so
     * everything the background work needs — the id, the label, the attributes
     * and the document's file descriptor — is captured here, and only plain
     * values cross onto the IO dispatcher.
     */
    override fun onPrintJobQueued(printJob: PrintJob) {
        val info = printJob.info
        val localId = info.printerId?.localId

        if (localId == null) {
            printJob.fail("No printer was selected")
            return
        }

        val jobKey = printJob.id.toString()
        val jobLabel = info.label?.toString() ?: "Print job"
        val options = optionsFrom(info.attributes, info.copies)

        // Taking the descriptor here, before start(), keeps the one main-thread
        // read of the document together with the rest of the handle access.
        val descriptor = runCatching { printJob.document?.data }.getOrNull()
        if (descriptor == null) {
            printJob.fail("Could not read the document from the print system")
            return
        }

        printJob.start()

        val job = scope.launch {
            try {
                val printer = ServiceLocator.printerRepository(applicationContext)
                    .saved.first()
                    .firstOrNull { it.id == localId }

                if (printer == null) {
                    fail(printJob, "That printer is no longer saved in PocketPrint")
                    return@launch
                }

                val spooled = spoolDocument(descriptor)
                if (spooled == null) {
                    fail(printJob, "Could not read the document from the print system")
                    return@launch
                }

                try {
                    when (
                        val result = ServiceLocator.printEngine(applicationContext).printPdf(
                            printer = printer,
                            pdf = spooled,
                            jobName = jobLabel,
                            options = options,
                            // Without this the dialog shows "printing" while the
                            // job sits behind another one on the same printer,
                            // which reads as stuck. Blocked with a reason reads
                            // as waiting, which is what is actually happening.
                            listener = JobListener(
                                onWaitingForPrinter = { waiting ->
                                    if (waiting) {
                                        block(
                                            printJob,
                                            "Waiting for another job on " +
                                                printer.displayName,
                                        )
                                    } else {
                                        resume(printJob)
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
                            complete(printJob)
                        }
                        is PrintResult.Sent -> {
                            Log.i(
                                TAG,
                                "sent ${result.bytesSent} bytes to ${printer.displayName}, " +
                                    "unconfirmed: ${result.reason}",
                            )
                            complete(printJob)
                        }
                        is PrintResult.Failure -> fail(printJob, result.message)
                    }
                } finally {
                    runCatching { spooled.delete() }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "job failed", t)
                fail(printJob, t.message ?: "Printing failed")
            } finally {
                // jobKey was captured on the main thread; reading printJob.id
                // here would throw and escape the coroutine.
                activeJobs.remove(jobKey)
            }
        }

        activeJobs[jobKey] = job
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        // This callback runs on the main thread, so the id is safe to read.
        activeJobs.remove(printJob.id.toString())?.cancel()
        if (printJob.isStarted || printJob.isQueued) printJob.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Copies the framework's PDF out of the descriptor captured on the main
     * thread. Only the descriptor crosses threads, never the PrintJob.
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

    // PrintJob state transitions must happen on the main thread.
    private fun complete(printJob: PrintJob) = onMain {
        if (!printJob.isCompleted) printJob.complete()
    }

    private fun block(printJob: PrintJob, reason: String) = onMain {
        if (printJob.isStarted) printJob.block(reason)
    }

    /** The framework's name for leaving the blocked state is start(). */
    private fun resume(printJob: PrintJob) = onMain {
        if (printJob.isBlocked) printJob.start()
    }

    private fun fail(printJob: PrintJob, message: String) = onMain {
        if (!printJob.isFailed) printJob.fail(message)
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post { runCatching { block() } }
    }
}

/** Maps our saved printers into PrinterInfo objects the framework can show. */
internal fun buildPrinterInfo(
    printer: Printer,
    printerId: PrinterId,
    packageName: String,
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

    return PrinterInfo.Builder(printerId, printer.displayName, PrinterInfo.STATUS_IDLE)
        .setCapabilities(capabilities)
        .setDescription(printer.makeAndModel ?: printer.subtitle)
        .build()
}
