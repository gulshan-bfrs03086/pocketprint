package com.gulshan.pocketprint.print

import android.content.Context
import android.util.Log
import com.gulshan.pocketprint.ipp.IppCapabilityMapper
import com.gulshan.pocketprint.ipp.IppClient
import com.gulshan.pocketprint.ipp.IppJobState
import com.gulshan.pocketprint.ipp.IppStatus
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.SourceDocument
import com.gulshan.pocketprint.render.RenderPipeline
import com.gulshan.pocketprint.render.RenderedDocument
import com.gulshan.pocketprint.transport.TransportFactory
import com.gulshan.pocketprint.transport.guardAgainstStall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one place a job actually happens: render for the target printer, then
 * push the result down the matching transport.
 */
class PrintEngine(
    private val context: Context,
    private val pipeline: RenderPipeline,
    private val ippClient: IppClient = IppClient(),
) {

    companion object {
        private const val TAG = "PrintEngine"

        /**
         * How long to keep asking an IPP printer whether a job finished.
         *
         * Long enough for a real document on a slow printer; short enough that
         * one which will never answer does not hold a job open all afternoon.
         * Running out is not a failure - it downgrades the outcome to Sent,
         * which is the truth: it was handed over and we stopped watching.
         */
        private const val DOCUMENT_POLL_WINDOW_MS = 5 * 60_000L

        /** A label is seconds of work; waiting minutes for one is not useful. */
        private const val LABEL_POLL_WINDOW_MS = 20_000L

        private const val POLL_FIRST_MS = 500L
        private const val POLL_MAX_MS = 4_000L

        /**
         * How long a write may make no progress at all before the transport is
         * closed out from under it.
         *
         * Generous on purpose. A rasterised page over Bluetooth legitimately
         * takes minutes, and killing a slow-but-working job is a worse failure
         * than making someone wait. But a full minute in which not one byte
         * moved is not a slow printer, it is a stuck one - and the old
         * behaviour there was to block forever with every queued job behind it.
         */
        private const val WRITE_STALL_MS = 60_000L
    }

    /**
     * Why a transport cannot confirm a job.
     *
     * These are not hedges. RFCOMM, USB bulk and raw 9100 have no reply channel
     * for job outcome at all: the write returns when the bytes are in a buffer,
     * and the page in your hand is the only confirmation that exists anywhere
     * in the system. Saying so is the difference between a user who checks the
     * paper and a user who spends an evening on the command language.
     */
    private fun unconfirmedReason(address: PrinterAddress): String = when (address) {
        is PrinterAddress.Bluetooth ->
            "Bluetooth printing carries no acknowledgement. The printer accepted " +
                "the bytes; only the label tells you what it did with them."
        is PrinterAddress.Usb ->
            "USB printing carries no acknowledgement. The printer accepted the " +
                "bytes; only the page tells you what it did with them."
        is PrinterAddress.Raw ->
            "Raw port 9100 carries no acknowledgement. The printer accepted the " +
                "bytes; only the page tells you what it did with them."
        is PrinterAddress.Ipp ->
            "The printer accepted the job but never said what became of it."
    }

    /**
     * Follows an IPP job to a terminal state, which is the one case where the
     * printer will actually tell us.
     *
     * Every exit that is not job-state=completed returns Sent rather than
     * Completed, with the reason attached. That includes the printer forgetting
     * the job: printers purge finished jobs on their own schedule, so
     * not-found usually does mean it printed - "usually" being exactly the kind
     * of inference this whole change exists to stop making silently.
     */
    private suspend fun awaitIppCompletion(
        address: PrinterAddress.Ipp,
        jobId: Int?,
        bytesSent: Long,
        windowMs: Long,
        onStatus: (String) -> Unit,
    ): PrintResult {
        if (jobId == null) {
            return PrintResult.Sent(
                bytesSent,
                reason = "The printer accepted the job but gave it no id, so its " +
                    "progress cannot be followed.",
            )
        }

        val deadline = System.currentTimeMillis() + windowMs
        var interval = POLL_FIRST_MS
        var lastSeen: IppJobState? = null

        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            interval = (interval * 2).coerceAtMost(POLL_MAX_MS)

            val response = runCatching { ippClient.getJobAttributes(address, jobId) }
                .getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "job $jobId poll failed", it)
                    return PrintResult.Sent(
                        bytesSent, jobId,
                        reason = "The printer stopped answering questions about job " +
                            "$jobId, so what became of it is unknown.",
                    )
                }

            if (response.statusCode == IppStatus.CLIENT_ERROR_NOT_FOUND) {
                return PrintResult.Sent(
                    bytesSent, jobId,
                    reason = "The printer no longer has a record of job $jobId. That " +
                        "usually means it finished, but the printer never said so.",
                )
            }
            if (!response.isSuccess) {
                return PrintResult.Sent(
                    bytesSent, jobId,
                    reason = "The printer refused to report on job $jobId " +
                        "(${response.statusText}).",
                )
            }

            val state = IppCapabilityMapper.jobState(response) ?: continue
            val reasons = IppCapabilityMapper.jobStateReasons(response)
            lastSeen = state
            onStatus(describe(state, reasons))

            when (state) {
                IppJobState.COMPLETED -> return PrintResult.Completed(bytesSent, jobId)
                IppJobState.ABORTED, IppJobState.CANCELED -> return PrintResult.Failure(
                    "The printer ${state.label} the job" +
                        reasons.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = ": ") { it.replace('-', ' ') }
                            .orEmpty(),
                )
                else -> Unit
            }
        }

        val waited = if (windowMs >= 60_000) {
            "${windowMs / 60_000} minutes"
        } else {
            "${windowMs / 1_000} seconds"
        }
        return PrintResult.Sent(
            bytesSent, jobId,
            reason = "The printer was still ${lastSeen?.label ?: "working on the job"} " +
                "after $waited, so it was left to get on with it.",
        )
    }

    private fun describe(state: IppJobState, reasons: List<String>): String {
        val detail = reasons.joinToString { it.replace('-', ' ') }
        return if (detail.isBlank()) state.label else "${state.label} - $detail"
    }

    /**
     * runCatching catches Throwable, which includes CancellationException.
     * Swallowing that breaks structured concurrency: the caller believes the
     * job merely failed and carries on suspending inside a coroutine that is
     * already cancelled, so its own cleanup never runs.
     */
    private fun failureOf(t: Throwable): PrintResult {
        if (t is kotlinx.coroutines.CancellationException) throw t
        return PrintResult.Failure(t.message ?: t.javaClass.simpleName, t)
    }

    /**
     * Asks an IPP printer what it can do and returns an updated copy. Non-IPP
     * printers are returned unchanged, since they have no capability protocol.
     */
    suspend fun probe(printer: Printer): Printer {
        val address = printer.address as? PrinterAddress.Ipp ?: return printer
        return runCatching {
            val response = ippClient.getPrinterAttributes(address)
            if (!response.isSuccess) {
                Log.w(TAG, "probe failed: ${response.statusText}")
                return printer
            }
            printer.copy(
                displayName = printer.displayName.ifBlank {
                    IppCapabilityMapper.displayName(response, printer.displayName)
                },
                makeAndModel = IppCapabilityMapper.makeAndModel(response) ?: printer.makeAndModel,
                location = IppCapabilityMapper.location(response) ?: printer.location,
                capabilities = IppCapabilityMapper.toCapabilities(response),
                lastSeenEpochMs = System.currentTimeMillis(),
            )
        }.getOrElse {
            Log.w(TAG, "probe threw for ${printer.displayName}", it)
            printer
        }
    }

    /**
     * Asks a network printer whether it is in a state to take work.
     *
     * Returns null for printers with no status protocol, which is every
     * transport except IPP: raw 9100, RFCOMM and USB bulk will accept bytes
     * from a printer that is jammed, out of paper or switched to a different
     * interpreter, and say nothing about any of it.
     */
    suspend fun networkStatus(printer: Printer): PrinterAvailability? {
        val address = printer.address as? PrinterAddress.Ipp ?: return null
        return runCatching {
            val response = ippClient.getPrinterAttributes(address)
            PrinterStatus.fromIpp(
                reachable = response.isSuccess,
                acceptingJobs = IppCapabilityMapper.isAcceptingJobs(response),
                printerState = IppCapabilityMapper.printerState(response),
            )
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.i(TAG, "status probe failed for ${printer.displayName}: ${it.message}")
            PrinterAvailability.UNAVAILABLE
        }
    }

    /**
     * The first page as the printer will actually mark it, or null when there
     * is nothing useful to show. See [RenderPipeline.previewFirstPage].
     */
    suspend fun preview(
        printer: Printer,
        source: SourceDocument,
        options: PrintOptions,
    ): android.graphics.Bitmap? = pipeline.previewFirstPage(source, printer, options)

    suspend fun print(
        printer: Printer,
        source: SourceDocument,
        options: PrintOptions,
        listener: JobListener = JobListener(),
    ): PrintResult = runCatching {
        val rendered = pipeline.render(source, printer, options)
        send(printer, rendered, source.displayName, options, listener)
    }.getOrElse { failureOf(it) }

    /** Entry point for the system print service, which hands us a finished PDF. */
    suspend fun printPdf(
        printer: Printer,
        pdf: File,
        jobName: String,
        options: PrintOptions,
        listener: JobListener = JobListener(),
    ): PrintResult = runCatching {
        val rendered = pipeline.renderPdf(pdf, printer, options)
        send(printer, rendered, jobName, options, listener)
    }.getOrElse { failureOf(it) }

    suspend fun printRaw(
        printer: Printer,
        bytes: ByteArray,
        jobName: String,
        options: PrintOptions,
        listener: JobListener = JobListener(),
    ): PrintResult = runCatching {
        withContext(Dispatchers.IO) {
            val address = printer.address
            if (address is PrinterAddress.Ipp) {
                val response = onPrinter(printer, listener) {
                    ippClient.printJob(
                        address = address,
                        jobName = jobName,
                        format = printer.capabilities.preferredLanguage(),
                        options = options,
                        supportedMedia = printer.capabilities.mediaSizes.map { it.id },
                        contentLength = bytes.size.toLong(),
                        openDocument = { bytes.inputStream() },
                    )
                }
                if (response.isSuccess) {
                    awaitIppCompletion(
                        address = address,
                        jobId = IppCapabilityMapper.jobId(response),
                        bytesSent = bytes.size.toLong(),
                        windowMs = LABEL_POLL_WINDOW_MS,
                        onStatus = listener.onStatus,
                    )
                } else {
                    PrintResult.Failure("Printer rejected job: ${response.statusText}")
                }
            } else {
                onPrinter(printer, listener) {
                    TransportFactory.create(context, printer).use { transport ->
                        transport.open()
                        val sent = guardAgainstStall(
                            stallMs = WRITE_STALL_MS,
                            unblock = { runCatching { transport.close() } },
                        ) { heartbeat ->
                            transport.write(bytes) { heartbeat() }
                        }
                        // Must drain before use{} closes, or the tail is discarded.
                        transport.finish()
                        PrintResult.Sent(sent, reason = unconfirmedReason(address))
                    }
                }
            }
        }
    }.getOrElse { failureOf(it) }

    /**
     * Takes the printer's queue for the duration of [delivery], and tells the
     * caller if it had to wait.
     *
     * Deliberately wrapped around delivery only, not around rendering: a render
     * is local work that touches nothing on the printer, and holding a printer
     * hostage while a PDF rasterises would serialise two jobs that could have
     * overlapped for most of their lives.
     */
    private suspend fun <T> onPrinter(
        printer: Printer,
        listener: JobListener,
        delivery: suspend () -> T,
    ): T = PrinterQueue.withPrinter(
        printer = printer,
        onWaiting = {
            listener.onWaitingForPrinter(true)
            listener.onStatus("Waiting for another job on ${printer.displayName} to finish")
        },
        onResumed = { listener.onWaitingForPrinter(false) },
        block = delivery,
    )

    private suspend fun send(
        printer: Printer,
        rendered: RenderedDocument,
        jobName: String,
        options: PrintOptions,
        listener: JobListener,
    ): PrintResult = try {
        val total = rendered.sizeBytes
        val address = printer.address

        if (address is PrinterAddress.Ipp) {
            // The queue covers handing the job over, not waiting for it to
            // print. An IPP printer has a spooler of its own and is perfectly
            // happy to accept the next job while it works through this one;
            // holding our lock across a five-minute poll would serialise jobs
            // the printer never needed serialised.
            val response = onPrinter(printer, listener) {
                ippClient.printJob(
                    address = address,
                    jobName = jobName,
                    format = rendered.language,
                    options = options,
                    supportedMedia = printer.capabilities.mediaSizes.map { it.id },
                    contentLength = total,
                    openDocument = { rendered.file.inputStream() },
                )
            }
            listener.onProgress(total, total)

            if (response.isSuccess) {
                awaitIppCompletion(
                    address = address,
                    jobId = IppCapabilityMapper.jobId(response),
                    bytesSent = total,
                    windowMs = DOCUMENT_POLL_WINDOW_MS,
                    onStatus = listener.onStatus,
                )
            } else {
                val unsupported = response.unsupported()
                    .joinToString { it.name }
                    .takeIf { it.isNotBlank() }
                    ?.let { " (unsupported: $it)" }
                    .orEmpty()
                PrintResult.Failure("Printer rejected job: ${response.statusText}$unsupported")
            }
        } else {
            // Here the lock is the whole job. There is one RFCOMM slot, one USB
            // interface, one socket: the connection has to be opened, filled
            // and drained without another job arriving in the middle of it.
            onPrinter(printer, listener) {
                withContext(Dispatchers.IO) {
                    TransportFactory.create(context, printer).use { transport ->
                        transport.open()
                        // Copies are already baked into the payload by this point:
                        // TSPL via PRINT, ZPL via ^PQ, ESC/POS by repeating the page,
                        // PCL via ESC&l#X. So the stream goes out exactly once.
                        //
                        // Closing the transport is what unblocks a write stuck
                        // in a syscall, for a stall and for a cancellation
                        // alike; cancelling the coroutine on its own would not.
                        val sent = guardAgainstStall(
                            stallMs = WRITE_STALL_MS,
                            unblock = { runCatching { transport.close() } },
                        ) { heartbeat ->
                            transport.write(rendered.file.inputStream()) { written ->
                                heartbeat()
                                listener.onProgress(written, total)
                            }
                        }
                        transport.finish()
                        Diagnostics.record(
                            TAG,
                            "sent $sent bytes of ${rendered.language} over " +
                                transport.description,
                        )
                        PrintResult.Sent(sent, reason = unconfirmedReason(address))
                    }
                }
            }
        }
    } finally {
        if (rendered.temporary) runCatching { rendered.file.delete() }
    }
}
