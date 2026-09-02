package com.gulshan.pocketprint.render

import android.content.Context
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.SourceDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Office documents cannot be laid out on-device without shipping a full office
 * engine, so conversion is delegated to a service the user points us at.
 *
 * The default shape matches Gotenberg's LibreOffice route
 * (POST /forms/libreoffice/convert, multipart field "files"), which also covers
 * a plain LibreOffice container behind a small HTTP wrapper.
 */
interface OfficeConverter {
    suspend fun toPdf(context: Context, source: SourceDocument, localFile: File): RenderedDocument

    companion object {
        val OFFICE_MIME_PREFIXES = listOf(
            "application/msword",
            "application/vnd.ms-",
            "application/vnd.openxmlformats-officedocument",
            "application/vnd.oasis.opendocument",
            "application/rtf",
        )

        val OFFICE_EXTENSIONS = setOf(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp", "rtf",
        )

        fun isOffice(source: SourceDocument): Boolean =
            source.extension in OFFICE_EXTENSIONS ||
                OFFICE_MIME_PREFIXES.any { source.mimeType.startsWith(it) }
    }
}

class OfficeConversionUnavailable(message: String) : Exception(message)

/** Fails with an actionable message when no conversion endpoint is configured. */
object NoOfficeConverter : OfficeConverter {
    override suspend fun toPdf(
        context: Context,
        source: SourceDocument,
        localFile: File,
    ): RenderedDocument = throw OfficeConversionUnavailable(
        "No document converter is configured, so ${source.displayName} cannot be " +
            "printed directly. Set a converter URL in Settings, or open the file " +
            "in its own app and share it here as a PDF.",
    )
}

class RemoteOfficeConverter(
    private val endpoint: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) : OfficeConverter {

    override suspend fun toPdf(
        context: Context,
        source: SourceDocument,
        localFile: File,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val mediaType = source.mimeType.toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files",
                source.displayName,
                localFile.asRequestBody(mediaType),
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("User-Agent", "PocketPrint/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw OfficeConversionUnavailable(
                    "Converter returned HTTP ${response.code} for ${source.displayName}",
                )
            }
            val stream = response.body?.byteStream()
                ?: throw OfficeConversionUnavailable("Converter returned an empty body")

            val out = Spool.newFile(context, ".pdf")
            out.outputStream().use { sink -> stream.copyTo(sink, 64 * 1024) }

            val pages = runCatching { PdfRasterizer.pageCount(out) }.getOrDefault(0)
            if (pages == 0) {
                throw OfficeConversionUnavailable(
                    "Converter did not return a readable PDF for ${source.displayName}",
                )
            }
            RenderedDocument(out, PrintLanguage.PDF, pages)
        }
    }
}
