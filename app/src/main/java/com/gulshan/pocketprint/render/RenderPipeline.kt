package com.gulshan.pocketprint.render

import android.content.Context
import android.net.Uri
import com.gulshan.pocketprint.label.EscPos
import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.label.Zpl
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.SourceDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns whatever the user picked into bytes a specific printer understands.
 *
 * Everything funnels through PDF as the intermediate representation, because it
 * is the one format we can both produce (images, text, HTML, office via the
 * converter) and consume (the platform rasterizer). The second stage then
 * re-encodes that PDF into the printer's own language.
 */
class RenderPipeline(
    private val context: Context,
    private val officeConverter: () -> OfficeConverter = { NoOfficeConverter },
) {

    private val rawLabelExtensions = setOf("tspl", "zpl", "escpos", "prn", "bin")

    suspend fun render(
        source: SourceDocument,
        printer: Printer,
        options: PrintOptions,
    ): RenderedDocument {
        val target = chooseLanguage(printer)

        // A raw command file goes straight through untouched; re-encoding it
        // would corrupt the very commands the user wanted to send.
        if (source.extension in rawLabelExtensions) {
            val file = Spool.copyToCache(context, Uri.parse(source.uri), ".${source.extension}")
            return RenderedDocument(file, target, pageCount = 1)
        }

        val pdf = toPdf(source, options)
        return convert(pdf, target, printer, options)
    }

    /** Renders already-prepared PDF bytes, used by the system print service. */
    suspend fun renderPdf(
        pdf: File,
        printer: Printer,
        options: PrintOptions,
    ): RenderedDocument {
        val target = chooseLanguage(printer)
        val doc = RenderedDocument(
            pdf, PrintLanguage.PDF,
            runCatching { PdfRasterizer.pageCount(pdf) }.getOrDefault(1),
            temporary = false,
        )
        return convert(doc, target, printer, options)
    }

    suspend fun renderText(text: String, printer: Printer, options: PrintOptions): RenderedDocument {
        val pdf = PdfBuilder.fromText(context, text, options)
        return convert(pdf, chooseLanguage(printer), printer, options)
    }

    suspend fun renderUrl(url: String, printer: Printer, options: PrintOptions): RenderedDocument {
        val pdf = WebToPdf.fromUrl(context, url, options)
        return convert(pdf, chooseLanguage(printer), printer, options)
    }

    /** Picks the best language the printer advertises, preferring PDF. */
    fun chooseLanguage(printer: Printer): PrintLanguage {
        val supported = printer.capabilities.languages
        return when {
            supported.isEmpty() -> PrintLanguage.PDF
            PrintLanguage.PDF in supported -> PrintLanguage.PDF
            PrintLanguage.PWG_RASTER in supported -> PrintLanguage.PWG_RASTER
            else -> supported.first()
        }
    }

    private suspend fun toPdf(source: SourceDocument, options: PrintOptions): RenderedDocument {
        val uri = Uri.parse(source.uri)
        val mime = source.mimeType

        return when {
            mime == "application/pdf" || source.extension == "pdf" -> {
                val file = Spool.copyToCache(context, uri, ".pdf")
                PdfBuilder.copyPdf(context, file)
            }

            mime.startsWith("image/") -> PdfBuilder.fromImages(context, listOf(uri), options)

            source.isUrl -> WebToPdf.fromUrl(context, source.uri, options)

            mime == "text/html" || source.extension in setOf("html", "htm") -> {
                val file = Spool.copyToCache(context, uri, ".html")
                WebToPdf.fromHtml(
                    context,
                    file.readText(),
                    baseUrl = null,
                    options = options,
                )
            }

            mime.startsWith("text/") || source.extension in setOf("txt", "log", "csv", "md") -> {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                }
                PdfBuilder.fromText(context, text, options, title = source.displayName)
            }

            OfficeConverter.isOffice(source) -> {
                val local = Spool.copyToCache(context, uri, ".${source.extension}")
                officeConverter().toPdf(context, source, local)
            }

            else -> throw UnsupportedOperationException(
                "Don't know how to print ${source.displayName} (${source.mimeType})",
            )
        }
    }

    private suspend fun convert(
        pdf: RenderedDocument,
        target: PrintLanguage,
        printer: Printer,
        options: PrintOptions,
    ): RenderedDocument {
        // Copies must be applied exactly once. Over IPP the printer honours the
        // `copies` job attribute, so the stream itself must carry a single copy;
        // on a raw byte transport there is no such attribute, so the copy count
        // has to be baked into the commands instead.
        val streamOptions = if (printer.address is PrinterAddress.Ipp) {
            options.copy(copies = 1)
        } else {
            options
        }

        return when (target) {
            PrintLanguage.PDF -> pdf

            PrintLanguage.PWG_RASTER -> PwgRasterEncoder.fromPdf(
                context, pdf.file, streamOptions, printer.capabilities.supportsColor,
            ).also { pdf.cleanupIfTemporary() }

            PrintLanguage.PCL -> PclRasterEncoder.fromPdf(context, pdf.file, streamOptions)
                .also { pdf.cleanupIfTemporary() }

            PrintLanguage.ESC_POS ->
                toEscPos(pdf, printer, streamOptions).also { pdf.cleanupIfTemporary() }
            PrintLanguage.TSPL ->
                toTspl(pdf, printer, streamOptions).also { pdf.cleanupIfTemporary() }
            PrintLanguage.ZPL ->
                toZpl(pdf, printer, streamOptions).also { pdf.cleanupIfTemporary() }

            // No encoder produces these. Sending PCL bytes labelled as PostScript
            // just earns a document-format rejection, so fail with a real reason.
            PrintLanguage.POSTSCRIPT, PrintLanguage.JPEG, PrintLanguage.PLAIN_TEXT ->
                throw UnsupportedOperationException(
                    "${printer.displayName} accepts only ${target.mimeType}, which " +
                        "PocketPrint cannot generate. Ask the printer for PDF or " +
                        "PWG raster support, or connect it as a raw 9100 printer.",
                )
        }
    }

    private suspend fun toEscPos(
        pdf: RenderedDocument,
        printer: Printer,
        options: PrintOptions,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val width = printer.capabilities.rasterWidthDots ?: 576
        val dpi = printer.capabilities.resolutionsDpi.firstOrNull() ?: 203
        val buffer = ByteArrayOutputStream(64 * 1024)
        var pages = 0

        repeat(options.copies.coerceAtLeast(1)) {
            PdfRasterizer.forEachPage(
                pdf.file, dpi = dpi, targetWidthPx = width, pageRange = options.pageRange,
            ) { _, bitmap ->
                val builder = EscPos(width).initialize()
                    .image(bitmap, dither = options.dither).cut()
                buffer.write(builder.build())
                pages++
            }
        }

        writeOut(buffer, ".escpos", PrintLanguage.ESC_POS, pages)
    }

    private suspend fun toTspl(
        pdf: RenderedDocument,
        printer: Printer,
        options: PrintOptions,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val dpi = printer.capabilities.resolutionsDpi.firstOrNull() ?: 203
        val media = pickLabelMedia(printer, options)
        val width = printer.capabilities.rasterWidthDots ?: media.dotsWide(dpi)
        val buffer = ByteArrayOutputStream(64 * 1024)
        var pages = 0

        PdfRasterizer.forEachPage(
            pdf.file, dpi = dpi, targetWidthPx = width, pageRange = options.pageRange,
        ) { _, bitmap ->
            val builder = Tspl(media, dpi)
                .setup(density = options.density)
                .image(0, 0, bitmap, dither = options.dither)
            builder.print(sets = 1, copies = options.copies.coerceAtLeast(1))
            buffer.write(builder.build())
            pages++
        }

        writeOut(buffer, ".tspl", PrintLanguage.TSPL, pages)
    }

    private suspend fun toZpl(
        pdf: RenderedDocument,
        printer: Printer,
        options: PrintOptions,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val dpi = printer.capabilities.resolutionsDpi.firstOrNull() ?: 203
        val media = pickLabelMedia(printer, options)
        val width = printer.capabilities.rasterWidthDots ?: media.dotsWide(dpi)
        val buffer = ByteArrayOutputStream(64 * 1024)
        var pages = 0

        PdfRasterizer.forEachPage(
            pdf.file, dpi = dpi, targetWidthPx = width, pageRange = options.pageRange,
        ) { _, bitmap ->
            val builder = Zpl(media, dpi)
                .start(density = options.density)
                .image(0, 0, bitmap, dither = options.dither)
            builder.end(copies = options.copies.coerceAtLeast(1))
            buffer.write(builder.build())
            pages++
        }

        writeOut(buffer, ".zpl", PrintLanguage.ZPL, pages)
    }

    /** Label stock the user chose, if the printer lists it; otherwise its default. */
    private fun pickLabelMedia(printer: Printer, options: PrintOptions): MediaSize =
        printer.capabilities.mediaSizes.firstOrNull { it.id == options.mediaSize.id }
            ?: printer.capabilities.mediaSizes.firstOrNull()
            ?: options.mediaSize

    private fun writeOut(
        buffer: ByteArrayOutputStream,
        suffix: String,
        language: PrintLanguage,
        pages: Int,
    ): RenderedDocument {
        if (pages == 0) throw IllegalStateException("Nothing was rendered for $language")
        val out = Spool.newFile(context, suffix)
        val bytes = buffer.toByteArray()
        out.outputStream().use { it.write(bytes) }
        if (com.gulshan.pocketprint.BuildConfig.DEBUG) {
            runCatching {
                val dir = context.getExternalFilesDir(null)
                val dump = File(dir, "last-stream$suffix")
                dump.outputStream().use { it.write(bytes) }
                android.util.Log.i(
                    "StreamDiag",
                    "wrote ${bytes.size} bytes of $language to ${dump.absolutePath}",
                )
            }
        }
        return RenderedDocument(out, language, pages)
    }
}

fun RenderedDocument.cleanupIfTemporary() {
    if (temporary) runCatching { file.delete() }
}
