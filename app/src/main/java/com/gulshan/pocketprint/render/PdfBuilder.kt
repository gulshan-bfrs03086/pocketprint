package com.gulshan.pocketprint.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Orientation
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Produces PDFs from the source types we can lay out ourselves: bitmaps and
 * plain text. PDF is the lingua franca here, because both IPP printers and the
 * Android print framework speak it, and our own rasterizer consumes it.
 */
object PdfBuilder {

    /** PDF user space is 1/72 inch, so page boxes are computed in points. */
    private fun pageSize(options: PrintOptions): Pair<Int, Int> {
        val w = Math.round(options.mediaSize.widthPoints)
        val h = Math.round(options.mediaSize.heightPoints)
        return if (options.orientation == Orientation.LANDSCAPE) h to w else w to h
    }

    suspend fun fromImages(
        context: Context,
        uris: List<Uri>,
        options: PrintOptions,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val (pw, ph) = pageSize(options)
        val doc = PdfDocument()
        var pages = 0

        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = decodeScaled(context, uri, maxOf(pw, ph) * 4)
                    ?: return@forEachIndexed
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(pw, ph, index + 1).create(),
                )
                drawCentred(page.canvas, bitmap, pw, ph, options.fitToPage)
                doc.finishPage(page)
                bitmap.recycle()
                pages++
            }

            if (pages == 0) throw IllegalStateException("No printable images were decoded")

            val out = Spool.newFile(context, ".pdf")
            out.outputStream().use { doc.writeTo(it) }
            RenderedDocument(out, PrintLanguage.PDF, pages)
        } finally {
            doc.close()
        }
    }

    suspend fun fromText(
        context: Context,
        text: String,
        options: PrintOptions,
        title: String? = null,
        fontSizePt: Float = 10f,
        marginPt: Float = 36f,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val (pw, ph) = pageSize(options)
        val doc = PdfDocument()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontSizePt
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val lineHeight = paint.fontSpacing
        val usableWidth = pw - 2 * marginPt
        val linesPerPage = ((ph - 2 * marginPt) / lineHeight).toInt().coerceAtLeast(1)

        val wrapped = wrapLines(text, paint, usableWidth)
        val chunks = wrapped.chunked(linesPerPage).ifEmpty { listOf(listOf("")) }

        try {
            chunks.forEachIndexed { pageIndex, lines ->
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(pw, ph, pageIndex + 1).create(),
                )
                var y = marginPt + lineHeight
                if (pageIndex == 0 && !title.isNullOrBlank()) {
                    val titlePaint = Paint(paint).apply {
                        textSize = fontSizePt * 1.4f
                        isFakeBoldText = true
                    }
                    page.canvas.drawText(title, marginPt, y, titlePaint)
                    y += titlePaint.fontSpacing * 1.5f
                }
                lines.forEach { line ->
                    page.canvas.drawText(line, marginPt, y, paint)
                    y += lineHeight
                }
                doc.finishPage(page)
            }

            val out = Spool.newFile(context, ".pdf")
            out.outputStream().use { doc.writeTo(it) }
            RenderedDocument(out, PrintLanguage.PDF, chunks.size)
        } finally {
            doc.close()
        }
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { rawLine ->
            val line = rawLine.replace("\t", "    ")
            if (paint.measureText(line) <= maxWidth) {
                out += line
                return@forEach
            }
            val builder = StringBuilder()
            line.split(' ').forEach { word ->
                val candidate = if (builder.isEmpty()) word else "$builder $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    builder.clear().append(candidate)
                } else {
                    if (builder.isNotEmpty()) out += builder.toString()
                    builder.clear()
                    // A single word longer than the line still has to be broken.
                    var remainder = word
                    while (paint.measureText(remainder) > maxWidth && remainder.length > 1) {
                        var cut = remainder.length
                        while (cut > 1 && paint.measureText(remainder.take(cut)) > maxWidth) cut--
                        out += remainder.take(cut)
                        remainder = remainder.drop(cut)
                    }
                    builder.append(remainder)
                }
            }
            if (builder.isNotEmpty()) out += builder.toString()
        }
        return out
    }

    private fun drawCentred(
        canvas: Canvas,
        bitmap: Bitmap,
        pageWidth: Int,
        pageHeight: Int,
        fitToPage: Boolean,
    ) {
        val scale = if (fitToPage) {
            minOf(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
        } else {
            minOf(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
                .coerceAtMost(1f)
        }
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (pageWidth - w) / 2f
        val top = (pageHeight - h) / 2f
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
    }

    /** Two-pass decode so a 50-megapixel photo does not blow the heap. */
    fun decodeScaled(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            (bounds.outWidth / sample) > maxDimension || (bounds.outHeight / sample) > maxDimension
        ) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    fun copyPdf(context: Context, source: File): RenderedDocument {
        val pageCount = runCatching { PdfRasterizer.pageCount(source) }.getOrDefault(0)
        return RenderedDocument(source, PrintLanguage.PDF, pageCount)
    }

    @Suppress("unused")
    fun defaultMedia(): MediaSize = MediaSize.A4
}
