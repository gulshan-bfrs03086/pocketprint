package com.gulshan.pocketprint.print

import android.content.Context
import android.util.Log
import com.gulshan.pocketprint.ipp.IppCapabilityMapper
import com.gulshan.pocketprint.ipp.IppClient
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.SourceDocument
import com.gulshan.pocketprint.render.RenderPipeline
import com.gulshan.pocketprint.render.RenderedDocument
import com.gulshan.pocketprint.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
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

    suspend fun print(
        printer: Printer,
        source: SourceDocument,
        options: PrintOptions,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): PrintResult = runCatching {
        val rendered = pipeline.render(source, printer, options)
        send(printer, rendered, source.displayName, options, onProgress)
    }.getOrElse { failureOf(it) }

    /** Entry point for the system print service, which hands us a finished PDF. */
    suspend fun printPdf(
        printer: Printer,
        pdf: File,
        jobName: String,
        options: PrintOptions,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): PrintResult = runCatching {
        val rendered = pipeline.renderPdf(pdf, printer, options)
        send(printer, rendered, jobName, options, onProgress)
    }.getOrElse { failureOf(it) }

    suspend fun printRaw(
        printer: Printer,
        bytes: ByteArray,
        jobName: String,
        options: PrintOptions,
    ): PrintResult = runCatching {
        withContext(Dispatchers.IO) {
            val address = printer.address
            if (address is PrinterAddress.Ipp) {
                val response = ippClient.printJob(
                    address = address,
                    jobName = jobName,
                    format = printer.capabilities.preferredLanguage(),
                    options = options,
                    supportedMedia = printer.capabilities.mediaSizes.map { it.id },
                    contentLength = bytes.size.toLong(),
                    openDocument = { bytes.inputStream() },
                )
                if (response.isSuccess) {
                    PrintResult.Success(bytes.size.toLong(), IppCapabilityMapper.jobId(response))
                } else {
                    PrintResult.Failure("Printer rejected job: ${response.statusText}")
                }
            } else {
                TransportFactory.create(context, printer).use { transport ->
                    transport.open()
                    val sent = transport.write(bytes)
                    // Must drain before use{} closes, or the tail is discarded.
                    transport.finish()
                    PrintResult.Success(sent)
                }
            }
        }
    }.getOrElse { failureOf(it) }

    private suspend fun send(
        printer: Printer,
        rendered: RenderedDocument,
        jobName: String,
        options: PrintOptions,
        onProgress: (Long, Long) -> Unit,
    ): PrintResult = try {
        val total = rendered.sizeBytes
        val address = printer.address

        if (address is PrinterAddress.Ipp) {
            val response = ippClient.printJob(
                address = address,
                jobName = jobName,
                format = rendered.language,
                options = options,
                supportedMedia = printer.capabilities.mediaSizes.map { it.id },
                contentLength = total,
                openDocument = { rendered.file.inputStream() },
            )
            onProgress(total, total)

            if (response.isSuccess) {
                PrintResult.Success(total, IppCapabilityMapper.jobId(response))
            } else {
                val unsupported = response.unsupported()
                    .joinToString { it.name }
                    .takeIf { it.isNotBlank() }
                    ?.let { " (unsupported: $it)" }
                    .orEmpty()
                PrintResult.Failure("Printer rejected job: ${response.statusText}$unsupported")
            }
        } else {
            withContext(Dispatchers.IO) {
                TransportFactory.create(context, printer).use { transport ->
                    transport.open()
                    // Copies are already baked into the payload by this point:
                    // TSPL via PRINT, ZPL via ^PQ, ESC/POS by repeating the page,
                    // PCL via ESC&l#X. So the stream goes out exactly once.
                    val sent = transport.write(rendered.file.inputStream()) { written ->
                        onProgress(written, total)
                    }
                    transport.finish()
                    PrintResult.Success(sent)
                }
            }
        }
    } finally {
        if (rendered.temporary) runCatching { rendered.file.delete() }
    }
}
