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
    val density: Int = 8,
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

enum class JobState { QUEUED, RENDERING, SENDING, COMPLETED, FAILED, CANCELLED }

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
)

/** Outcome of handing bytes to a printer. */
sealed interface PrintResult {
    data class Success(val bytesSent: Long, val jobId: Int? = null) : PrintResult
    data class Failure(val message: String, val cause: Throwable? = null) : PrintResult
}
