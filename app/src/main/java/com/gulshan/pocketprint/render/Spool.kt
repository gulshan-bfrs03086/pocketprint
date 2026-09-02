package com.gulshan.pocketprint.render

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.SourceDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

/** Cache-directory scratch space for documents being prepared. */
object Spool {

    fun dir(context: Context): File =
        File(context.cacheDir, "spool").apply { mkdirs() }

    fun newFile(context: Context, suffix: String): File =
        File.createTempFile("job-", suffix, dir(context))

    /**
     * PdfRenderer needs a seekable file descriptor, and content:// URIs are not
     * always seekable, so anything we intend to render gets copied locally.
     */
    suspend fun copyToCache(context: Context, uri: Uri, suffix: String): File =
        withContext(Dispatchers.IO) {
            val target = newFile(context, suffix)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw java.io.FileNotFoundException("Cannot open $uri")
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
