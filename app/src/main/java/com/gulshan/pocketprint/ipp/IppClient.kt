package com.gulshan.pocketprint.ipp

import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.DuplexMode
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Orientation
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks IPP over HTTP. IPP is just a binary body POSTed with the content type
 * application/ipp, so an ordinary HTTP client is all that is required.
 */
class IppClient(
    private val http: OkHttpClient = defaultClient(),
    private val userName: String = "pocketprint",
) {
    private val requestIds = AtomicInteger(1)

    companion object {
        private val IPP_MEDIA = "application/ipp".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Attributes worth asking for; omitting the list makes some printers dump everything. */
        private val REQUESTED_ATTRIBUTES = listOf(
            "printer-name", "printer-info", "printer-location", "printer-make-and-model",
            "printer-state", "printer-state-reasons", "printer-is-accepting-jobs",
            "document-format-supported", "document-format-default",
            "media-supported", "media-default", "media-ready",
            "printer-resolution-supported", "printer-resolution-default",
            "color-supported", "print-color-mode-supported",
            "sides-supported", "sides-default",
            "copies-supported", "orientation-requested-supported",
            "print-quality-supported", "ipp-versions-supported",
            "media-col-database", "printer-uuid", "marker-levels", "marker-names",
        )
    }

    suspend fun getPrinterAttributes(address: PrinterAddress.Ipp): IppResponse =
        withContext(Dispatchers.IO) {
            val body = IppRequest(IppOperation.GET_PRINTER_ATTRIBUTES, requestIds.getAndIncrement())
                .operationAttributes {
                    charset("attributes-charset")
                    naturalLanguage("attributes-natural-language")
                    uri("printer-uri", address.uri)
                    nameValue("requesting-user-name", userName)
                    keywords("requested-attributes", REQUESTED_ATTRIBUTES)
                }
                .build()
            execute(address, body, null, 0L)
        }

    suspend fun validateJob(
        address: PrinterAddress.Ipp,
        jobName: String,
        format: PrintLanguage,
        options: PrintOptions,
        supportedMedia: List<String>,
    ): IppResponse = withContext(Dispatchers.IO) {
        val body = buildJobRequest(
            IppOperation.VALIDATE_JOB, address, jobName, format, options, supportedMedia,
        )
        execute(address, body, null, 0L)
    }

    /**
     * Print-Job: header and document travel in a single POST. [contentLength] of
     * -1 falls back to chunked transfer, which a few older printers reject.
     */
    suspend fun printJob(
        address: PrinterAddress.Ipp,
        jobName: String,
        format: PrintLanguage,
        options: PrintOptions,
        supportedMedia: List<String>,
        contentLength: Long,
        openDocument: () -> InputStream,
    ): IppResponse = withContext(Dispatchers.IO) {
        val header = buildJobRequest(
            IppOperation.PRINT_JOB, address, jobName, format, options, supportedMedia,
        )
        execute(address, header, openDocument, contentLength)
    }

    suspend fun cancelJob(address: PrinterAddress.Ipp, jobId: Int): IppResponse =
        withContext(Dispatchers.IO) {
            val body = IppRequest(IppOperation.CANCEL_JOB, requestIds.getAndIncrement())
                .operationAttributes {
                    charset("attributes-charset")
                    naturalLanguage("attributes-natural-language")
                    uri("printer-uri", address.uri)
                    integer("job-id", jobId)
                    nameValue("requesting-user-name", userName)
                }
                .build()
            execute(address, body, null, 0L)
        }

    suspend fun getJobAttributes(address: PrinterAddress.Ipp, jobId: Int): IppResponse =
        withContext(Dispatchers.IO) {
            val body = IppRequest(IppOperation.GET_JOB_ATTRIBUTES, requestIds.getAndIncrement())
                .operationAttributes {
                    charset("attributes-charset")
                    naturalLanguage("attributes-natural-language")
                    uri("printer-uri", address.uri)
                    integer("job-id", jobId)
                    nameValue("requesting-user-name", userName)
                }
                .build()
            execute(address, body, null, 0L)
        }

    private fun buildJobRequest(
        operation: Int,
        address: PrinterAddress.Ipp,
        jobName: String,
        format: PrintLanguage,
        options: PrintOptions,
        supportedMedia: List<String>,
    ): ByteArray = IppRequest(operation, requestIds.getAndIncrement())
        .operationAttributes {
            charset("attributes-charset")
            naturalLanguage("attributes-natural-language")
            uri("printer-uri", address.uri)
            nameValue("requesting-user-name", userName)
            nameValue("job-name", jobName.take(255))
            mimeType("document-format", format.mimeType)
        }
        .jobAttributes {
            integer("copies", options.copies.coerceAtLeast(1))

            PwgMedia.nameFor(options.mediaSize, supportedMedia)?.let { keyword("media", it) }

            keyword(
                "print-color-mode",
                if (options.colorMode == ColorMode.COLOR) "color" else "monochrome",
            )
            keyword(
                "sides",
                when (options.duplex) {
                    DuplexMode.SIMPLEX -> "one-sided"
                    DuplexMode.LONG_EDGE -> "two-sided-long-edge"
                    DuplexMode.SHORT_EDGE -> "two-sided-short-edge"
                },
            )
            // 3 = portrait, 4 = landscape (RFC 8011 orientation-requested).
            enumValue(
                "orientation-requested",
                if (options.orientation == Orientation.LANDSCAPE) 4 else 3,
            )
            resolution("printer-resolution", options.dpi, options.dpi)
            options.pageRange?.let { ranges("page-ranges", listOf(it)) }
        }
        .build()

    private fun execute(
        address: PrinterAddress.Ipp,
        header: ByteArray,
        openDocument: (() -> InputStream)?,
        documentLength: Long,
    ): IppResponse {
        val body = object : RequestBody() {
            override fun contentType() = IPP_MEDIA

            override fun contentLength(): Long =
                if (openDocument == null) header.size.toLong()
                else if (documentLength >= 0) header.size + documentLength
                else -1L

            override fun writeTo(sink: BufferedSink) {
                sink.write(header)
                openDocument?.invoke()?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                    }
                }
                sink.flush()
            }
        }

        val request = Request.Builder()
            .url(address.httpUrl)
            .post(body)
            .header("Accept", "application/ipp")
            .header("User-Agent", "PocketPrint/1.0")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IppException("HTTP ${response.code} from ${address.httpUrl}")
            }
            val bytes = response.body?.bytes()
                ?: throw IppException("Empty IPP response from ${address.httpUrl}")
            return IppDecoder.decode(bytes)
        }
    }
}

/** Turns a Get-Printer-Attributes response into our capability model. */
object IppCapabilityMapper {

    fun toCapabilities(response: IppResponse): PrinterCapabilities {
        val g = response.printerGroup() ?: return PrinterCapabilities.UNKNOWN_NETWORK

        val formats = g["document-format-supported"]?.asStrings().orEmpty()
        // Only list what the render pipeline can actually emit. Advertising a
        // format we cannot generate makes chooseLanguage pick it and the job is
        // then rejected by the printer for a format mismatch.
        val languages = buildList {
            if (formats.any { it.equals("application/pdf", true) }) add(PrintLanguage.PDF)
            if (formats.any { it.equals("image/pwg-raster", true) }) add(PrintLanguage.PWG_RASTER)
            if (formats.any { it.contains("PCL", true) }) add(PrintLanguage.PCL)
        }.ifEmpty { listOf(PrintLanguage.PDF) }

        val media = g["media-supported"]?.asStrings().orEmpty()
            .mapNotNull { PwgMedia.parse(it) }
            .distinctBy { it.id }
            .ifEmpty { listOf(MediaSize.A4) }

        val resolutions = g["printer-resolution-supported"]?.values
            ?.filterIsInstance<IppValue.Resolution>()
            ?.map { it.dpi }
            ?.distinct()
            ?.sorted()
            ?.ifEmpty { null }
            ?: listOf(300)

        val colorModes = g["print-color-mode-supported"]?.asStrings().orEmpty()
        val supportsColor = g["color-supported"]?.asBool()
            ?: colorModes.any { it.equals("color", true) }

        val sides = g["sides-supported"]?.asStrings().orEmpty()
        val maxCopies = (g["copies-supported"]?.first as? IppValue.IntRangeValue)?.upper
            ?: g["copies-supported"]?.asInt()
            ?: 99

        return PrinterCapabilities(
            languages = languages,
            mediaSizes = media,
            resolutionsDpi = resolutions,
            supportsColor = supportsColor,
            supportsDuplex = sides.any { it.startsWith("two-sided") },
            maxCopies = maxCopies.coerceIn(1, 999),
        )
    }

    fun displayName(response: IppResponse, fallback: String): String =
        response.printerGroup()?.let { g ->
            g["printer-name"]?.asString()
                ?: g["printer-info"]?.asString()
                ?: g["printer-make-and-model"]?.asString()
        } ?: fallback

    fun makeAndModel(response: IppResponse): String? =
        response.printerGroup()?.get("printer-make-and-model")?.asString()

    fun location(response: IppResponse): String? =
        response.printerGroup()?.get("printer-location")?.asString()?.takeIf { it.isNotBlank() }

    fun isAcceptingJobs(response: IppResponse): Boolean =
        response.printerGroup()?.get("printer-is-accepting-jobs")?.asBool() ?: true

    /** printer-state is an enum: 3 idle, 4 processing, 5 stopped. */
    fun printerState(response: IppResponse): Int? =
        response.printerGroup()?.get("printer-state")?.asInt()

    fun stateText(response: IppResponse): String = when (printerState(response)) {
        3 -> "Idle"
        4 -> "Printing"
        5 -> "Stopped"
        else -> "Unknown"
    }

    fun jobId(response: IppResponse): Int? = response.jobGroup()?.get("job-id")?.asInt()

    fun jobState(response: IppResponse): IppJobState? =
        IppJobState.of(response.jobGroup()?.get("job-state")?.asInt())

    /**
     * Why the job is where it is - "media-empty-error", "job-printing" and so
     * on. The keyword "none" means the printer has nothing to add, so it is
     * dropped rather than shown to a user as a reason.
     */
    fun jobStateReasons(response: IppResponse): List<String> =
        response.jobGroup()?.get("job-state-reasons")?.asStrings().orEmpty()
            .filterNot { it.equals("none", ignoreCase = true) }
}

/**
 * Where an IPP job has got to. RFC 8011 §5.3.7.
 *
 * This enum is the reason a network print job can report an honest outcome at
 * all: it is the only transport here that will tell us what happened after the
 * bytes left. Bluetooth, USB and raw 9100 have no equivalent.
 */
enum class IppJobState(val code: Int, val terminal: Boolean) {
    PENDING(3, false),
    PENDING_HELD(4, false),
    PROCESSING(5, false),

    /** Paused - out of paper, cover open, or waiting for a person. */
    PROCESSING_STOPPED(6, false),

    CANCELED(7, true),
    ABORTED(8, true),
    COMPLETED(9, true),
    ;

    val label: String get() = name.lowercase().replace('_', ' ')

    companion object {
        fun of(code: Int?): IppJobState? = entries.firstOrNull { it.code == code }
    }
}
