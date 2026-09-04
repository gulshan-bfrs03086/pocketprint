package com.gulshan.pocketprint.model

import kotlinx.serialization.Serializable

/** What the user asked us to print, before any rendering happens. */
@Serializable
data class SourceDocument(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long = -1L,
) {
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()

    val isUrl: Boolean get() = mimeType == MIME_URL

    companion object {
        /**
         * Marks a document whose `uri` is a web address to be fetched and
         * rendered, rather than a content URI to be opened.
         */
        const val MIME_URL = "text/x-uri"
    }
}

@Serializable
data class PrintOptions(
    val copies: Int = 1,
    val mediaSize: MediaSize = MediaSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val colorMode: ColorMode = ColorMode.MONOCHROME,
    val duplex: DuplexMode = DuplexMode.SIMPLEX,
    val dpi: Int = 300,
    /** 1-based and inclusive. Both null means every page. */
    val pageFrom: Int? = null,
    val pageTo: Int? = null,
    val fitToPage: Boolean = true,
    /** Thermal printers: darkness/heat. 0-15 for TSPL, 0-30 for ZPL. */
    /** Error-diffusion dithering when reducing to 1 bit. Off gives hard threshold. */
    val dither: Boolean = true,
) {
    /** IntRange has no built-in serializer, so it is derived rather than stored. */
    val pageRange: IntRange?
        get() {
            val from = pageFrom ?: return null
            val to = pageTo ?: return null
            return if (to >= from) from..to else null
        }
}

/**
 * How far a job got.
 *
 * SENT and COMPLETED are deliberately not the same thing, and the distinction
 * is the whole point. A write to a Bluetooth socket returns when the bytes are
 * in the OS buffer; the printer may be out of paper, loaded with the wrong
 * stock, or off. Field testing produced six jobs in a row recorded as COMPLETED
 * with no error while nothing came out of the printer, which sent the whole
 * investigation into the command language, which was never at fault.
 *
 * SENT means the bytes left the device and nothing more. COMPLETED means the
 * printer said so.
 */
enum class JobState { QUEUED, RENDERING, SENDING, SENT, COMPLETED, FAILED, CANCELLED }

@Serializable
data class PrintJobRecord(
    val id: String,
    val printerId: String,
    val printerName: String,
    val documentName: String,
    val state: JobState,
    val createdAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val pageCount: Int = 0,
    val bytesSent: Long = 0,
    val error: String? = null,
    /** Why a [JobState.SENT] job could not be confirmed. Never set on a failure. */
    val note: String? = null,
)

/**
 * Outcome of handing bytes to a printer.
 *
 * There is no Success, on purpose. "The write returned" and "the document
 * printed" are different claims, and conflating them is what let this app tell
 * a user six times that a job had completed while the printer produced blank
 * labels. Only a printer that reports back can produce [Completed]; everything
 * else is [Sent], which says exactly as much as is actually known.
 */
sealed interface PrintResult {

    /** Not a failure. How much is known differs between the two. */
    sealed interface Delivered : PrintResult {
        val bytesSent: Long
        val jobId: Int?
    }

    /**
     * The payload left the device and the transport drained cleanly. Whether
     * anything was printed is unknown and, on most of these transports,
     * unknowable: RFCOMM, USB bulk and raw 9100 carry no acknowledgement.
     *
     * [reason] says why there is no confirmation, so the user has something to
     * pull on rather than a shrug.
     */
    data class Sent(
        override val bytesSent: Long,
        override val jobId: Int? = null,
        val reason: String,
    ) : Delivered

    /** The printer itself reported the job finished. */
    data class Completed(
        override val bytesSent: Long,
        override val jobId: Int? = null,
    ) : Delivered

    data class Failure(val message: String, val cause: Throwable? = null) : PrintResult
}
