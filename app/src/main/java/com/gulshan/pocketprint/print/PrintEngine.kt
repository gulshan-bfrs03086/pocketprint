package com.gulshan.pocketprint.print

import android.content.Context
import android.util.Log
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.ipp.IppCapabilityMapper
import com.gulshan.pocketprint.ipp.IppClient
import com.gulshan.pocketprint.ipp.IppJobState
import com.gulshan.pocketprint.ipp.IppStatus
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.SourceDocument
import com.gulshan.pocketprint.render.RenderPipeline
import com.gulshan.pocketprint.render.RenderedDocument
import com.gulshan.pocketprint.transport.PrinterTransport
import com.gulshan.pocketprint.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import com.gulshan.pocketprint.ipp.PrinterTrust
import com.gulshan.pocketprint.ipp.UntrustedCertificateException

/**
 * The one place a job actually happens: render for the target printer, then
 * push the result down the matching transport.
 */
class PrintEngine(
    private val context: Context,
    private val pipeline: RenderPipeline,
    private val ippClient: IppClient = IppClient(),
    /** The words for a job's ending, apart so the ending can be tested without a Resources. */
    private val string: (Int) -> String = { context.getString(it) },
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
    private fun unconfirmedReason(address: PrinterAddress): String = string(
        when (address) {
            is PrinterAddress.Bluetooth -> R.string.unconfirmed_bluetooth
            is PrinterAddress.Usb -> R.string.unconfirmed_usb
            is PrinterAddress.Raw -> R.string.unconfirmed_raw
            is PrinterAddress.Ipp -> R.string.unconfirmed_ipp
        },
    )

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
                reason = context.getString(R.string.ipp_no_job_id),
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
                        reason = context.getString(R.string.ipp_stopped_answering, jobId),
                    )
                }

            if (response.statusCode == IppStatus.CLIENT_ERROR_NOT_FOUND) {
                return PrintResult.Sent(
                    bytesSent, jobId,
                    reason = context.getString(R.string.ipp_job_forgotten, jobId),
                )
            }
            if (!response.isSuccess) {
                return PrintResult.Sent(
                    bytesSent, jobId,
                    reason = context.getString(
                        R.string.ipp_refused_to_report, jobId, response.statusText,
                    ),
                )
            }

            val state = IppCapabilityMapper.jobState(response) ?: continue
            val reasons = IppCapabilityMapper.jobStateReasons(response)
            lastSeen = state
            onStatus(describe(state, reasons))

            when (state) {
                IppJobState.COMPLETED -> return PrintResult.Completed(bytesSent, jobId)
                IppJobState.ABORTED, IppJobState.CANCELED -> return PrintResult.Failure(
                    if (reasons.isEmpty()) {
                        context.getString(R.string.ipp_job_ended, state.label)
                    } else {
                        context.getString(
                            R.string.ipp_job_ended_because,
                            state.label,
                            reasons.joinToString { it.replace('-', ' ') },
                        )
                    },
                )
                else -> Unit
            }
        }

        val waited = if (windowMs >= 60_000) {
            val minutes = (windowMs / 60_000).toInt()
            context.resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
        } else {
            val seconds = (windowMs / 1_000).toInt()
            context.resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
        }
        return PrintResult.Sent(
            bytesSent, jobId,
            reason = context.getString(
                R.string.ipp_still_working,
                lastSeen?.label ?: context.getString(R.string.ipp_working_on_it),
                waited,
            ),
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
     * Why an IPPS printer cannot be reached over TLS - or null if it can, or
     * if the address is not IPPS at all.
     *
     * probe() swallows every failure, which is right for a probe: a printer
     * that is switched off is not an error. But a refused certificate is not
     * "switched off", it is a question only the user can answer, and this is
     * how the question reaches them instead of vanishing into a warn log.
     */
    suspend fun certificateProblem(printer: Printer): UntrustedCertificateException? {
        val address = printer.address as? PrinterAddress.Ipp ?: return null
        if (!address.secure) return null
        return runCatching {
            ippClient.getPrinterAttributes(address)
            null
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            PrinterTrust.untrustedCause(it)
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

    /**
     * Sends bytes the caller built itself. [language] is the dialect they are
     * in: it is what decides whether the printer can be asked how the job went,
     * and null means the caller does not know, so nothing is asked.
     */
    suspend fun printRaw(
        printer: Printer,
        bytes: ByteArray,
        jobName: String,
        options: PrintOptions,
        listener: JobListener = JobListener(),
        language: PrintLanguage? = null,
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
                        supportedResolutions = printer.capabilities.resolutionsDpi,
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
                    PrintResult.Failure(
                        context.getString(R.string.printer_rejected_job, response.statusText),
                    )
                }
            } else {
                onPrinter(printer, listener) {
                    TransportFactory.create(context, printer).use { transport ->
                        transport.open()
                        // The same delivery a rendered document gets, so a label
                        // from the designer, the test page or calibration is
                        // confirmed, and its paper fault reported, like any other.
                        val outcome = deliverSerial(
                            transport = transport,
                            printerKey = PrinterQueue.keyOf(printer),
                            language = language,
                            source = bytes.inputStream(),
                            stallMs = WRITE_STALL_MS,
                            onBusy = { waited -> reportWaiting(listener, waited) },
                        )
                        resultFor(outcome.ending, outcome.sent, unconfirmedReason(address))
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
            listener.onStatus(
                context.getString(R.string.queue_waiting, printer.displayName),
            )
        },
        onResumed = { listener.onWaitingForPrinter(false) },
        block = delivery,
    )

    private fun reportWaiting(listener: JobListener, waitedMs: Long) {
        listener.onStatus(context.getString(R.string.tspl_waiting_for_printer, waitedMs / 1_000))
    }

    /**
     * What a job's ending means for the person who pressed Print.
     *
     * Only a printer that went busy with the job and said it was ready again is
     * Completed. A fault it reports is a failure, because the alternative is a
     * job reported as done with the paper out. Everything else is Sent, with the
     * reason there is nothing stronger to say.
     */
    private fun resultFor(ending: Ending?, sent: Long, unconfirmed: String): PrintResult = when (ending) {
        is Ending.Finished -> PrintResult.Completed(sent)
        is Ending.Fault -> PrintResult.Failure(
            string(
                when (ending.fault) {
                    TsplStatus.Fault.PAPER_EMPTY -> R.string.tspl_fault_paper_empty
                    TsplStatus.Fault.PAPER_JAM -> R.string.tspl_fault_paper_jam
                    TsplStatus.Fault.HEAD_OPEN -> R.string.tspl_fault_head_open
                },
            ),
        )
        is Ending.ReadyButNeverSeenBusy ->
            PrintResult.Sent(sent, reason = string(R.string.tspl_ready_not_busy))
        is Ending.NeedsAttention ->
            PrintResult.Sent(sent, reason = string(R.string.tspl_needs_attention))
        is Ending.TimedOut ->
            PrintResult.Sent(sent, reason = context.getString(R.string.tspl_still_busy, ending.waitedMs / 1_000))
        is Ending.NoAnswer, is Ending.LinkLost, null -> PrintResult.Sent(sent, reason = unconfirmed)
    }

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
                    supportedResolutions = printer.capabilities.resolutionsDpi,
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
                PrintResult.Failure(
                    if (unsupported == null) {
                        context.getString(R.string.printer_rejected_job, response.statusText)
                    } else {
                        context.getString(
                            R.string.printer_rejected_unsupported,
                            response.statusText,
                            unsupported,
                        )
                    },
                )
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
                        val outcome = deliverSerial(
                            transport = transport,
                            printerKey = PrinterQueue.keyOf(printer),
                            language = rendered.language,
                            source = rendered.file.inputStream(),
                            stallMs = WRITE_STALL_MS,
                            onProgress = { written -> listener.onProgress(written, total) },
                            onBusy = { waited -> reportWaiting(listener, waited) },
                        )
                        val sent = outcome.sent
                        val ending = outcome.ending
                        val answeredBefore = outcome.answeredBefore

                        Diagnostics.record(
                            TAG,
                            "sent $sent bytes of ${rendered.language} over " +
                                transport.description +
                                (ending?.let { "; printer: $it (answered before the job: $answeredBefore)" } ?: ""),
                        )
                        resultFor(ending, sent, unconfirmedReason(address))
                    }
                }
            }
        }
    } finally {
        if (rendered.temporary) runCatching { rendered.file.delete() }
    }
}
