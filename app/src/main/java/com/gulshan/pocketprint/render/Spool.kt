package com.gulshan.pocketprint.render

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.SourceDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A payload that is ready to hand to a transport. */
data class RenderedDocument(
    val file: File,
    val language: PrintLanguage,
    val pageCount: Int,
    /** Set when the payload was produced by us and should be deleted after printing. */
    val temporary: Boolean = true,
) {
    val sizeBytes: Long get() = file.length()
}

/** Raised when a document is larger than [Spool.MAX_DOCUMENT_BYTES]. */
class DocumentTooLarge(val limitBytes: Long) : IOException(
    "Document is larger than ${limitBytes / (1024 * 1024)} MB",
)

/** Cache-directory scratch space for documents being prepared. */
object Spool {

    /**
     * Ceiling on anything copied into the cache.
     *
     * The share target is exported, so the byte count on the other end of a
     * content:// URI is whatever the sending app feels like producing, and a
     * provider is free to report one size and then stream another. Without a
     * ceiling the only limit is the free space on the device, filled by a
     * background copy the user never asked for. 64 MB is far past any real
     * document this prints - a 4x6 label is kilobytes, a scanned A4 page a few
     * megabytes.
     */
    const val MAX_DOCUMENT_BYTES: Long = 64L * 1024 * 1024

    /**
     * Ceiling on how long that copy may take.
     *
     * This is aimed at a provider that never returns, not at a slow one: a
     * cloud-backed DocumentsProvider may genuinely spend a while fetching a
     * large file before it can serve a byte, so the limit is generous.
     */
    const val COPY_TIMEOUT_MS: Long = 120_000L

    fun dir(context: Context): File =
        File(context.cacheDir, "spool").apply { mkdirs() }

    fun newFile(context: Context, suffix: String): File =
        File.createTempFile("job-", suffix, dir(context))

    /**
     * PdfRenderer needs a seekable file descriptor, and content:// URIs are not
     * always seekable, so anything we intend to render gets copied locally.
     *
     * Bounded in both size and time, because the source is frequently another
     * app's content provider and neither is ours to trust. A partial copy is
     * deleted rather than left behind for the pipeline to find and try to
     * render.
     */
    suspend fun copyToCache(
        context: Context,
        uri: Uri,
        suffix: String,
        maxBytes: Long = MAX_DOCUMENT_BYTES,
        timeoutMs: Long = COPY_TIMEOUT_MS,
    ): File = withContext(Dispatchers.IO) {
        val target = newFile(context, suffix)
        try {
            withTimeout(timeoutMs) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.FileNotFoundException("Cannot open $uri")

                // A read that never returns would otherwise outlive the timeout:
                // cancelling a coroutine does not interrupt a blocking read, but
                // closing the stream underneath it does.
                val onCancel = coroutineContext.job.invokeOnCompletion {
                    runCatching { input.close() }
                }
                try {
                    input.use { source ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) throw DocumentTooLarge(maxBytes)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    onCancel.dispose()
                }
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
        target
    }

    /** Reads display name, MIME type and size for a picked document. */
    suspend fun describe(context: Context, uri: Uri): SourceDocument =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: guessMimeFromUri(uri)
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            var size = -1L

            val cursor: Cursor? = runCatching {
                resolver.query(uri, null, null, null, null)
            }.getOrNull()

            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !it.isNull(nameIdx)) name = it.getString(nameIdx)
                    val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !it.isNull(sizeIdx)) size = it.getLong(sizeIdx)
                }
            }

            SourceDocument(uri.toString(), name, mime, size)
        }

    private fun guessMimeFromUri(uri: Uri): String {
        val ext = uri.toString().substringAfterLast('.', "").lowercase()
        return MIME_BY_EXTENSION[ext] ?: "application/octet-stream"
    }

    val MIME_BY_EXTENSION = mapOf(
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic",
        "txt" to "text/plain", "log" to "text/plain", "csv" to "text/csv",
        "html" to "text/html", "htm" to "text/html",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "tspl" to "application/octet-stream", "zpl" to "application/octet-stream",
    )

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }
}
