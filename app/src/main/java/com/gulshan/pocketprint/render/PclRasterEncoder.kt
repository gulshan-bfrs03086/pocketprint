package com.gulshan.pocketprint.render

import android.content.Context
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/**
 * PCL 5 monochrome raster. Most USB-attached and older network lasers accept
 * this even when they refuse PDF, so it is the safe fallback for those.
 *
 * A 1 bit prints a dot, which matches Raster.packBits directly.
 */
object PclRasterEncoder {

    private const val ESC = 0x1B.toByte()

    /** PCL paper-size selector values for ESC & l # A. */
    private fun paperCode(media: MediaSize): Int = when (media.id) {
        MediaSize.LETTER.id -> 2
        MediaSize.LEGAL.id -> 3
        MediaSize.A4.id -> 26
        MediaSize.A5.id -> 25
        else -> 26
    }

    suspend fun fromPdf(
        context: Context,
        pdf: File,
        options: PrintOptions,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val out = Spool.newFile(context, ".pcl")
        var pages = 0

        // PCL raster tops out at 600 dpi on most devices; 300 is universally safe.
        val dpi = if (options.dpi >= 600) 600 else 300

        BufferedOutputStream(out.outputStream(), 256 * 1024).use { sink ->
            esc(sink, "E")                                   // reset
            esc(sink, "&l${paperCode(options.mediaSize)}A")   // paper size
            esc(sink, "&l0O")                                // portrait
            esc(sink, "&l0E")                                // top margin 0
            if (options.copies > 1) esc(sink, "&l${options.copies.coerceIn(1, 99)}X")

            var bytesPerRow = 0

            PdfRasterizer.forEachPageBanded(
                file = pdf,
                dpi = dpi,
                pageRange = options.pageRange,
                onPageStart = { _, widthPx, heightPx ->
                    bytesPerRow = (widthPx + 7) / 8
                    esc(sink, "*t${dpi}R")        // raster resolution
                    esc(sink, "*r0F")             // raster orientation: logical page
                    esc(sink, "*r${widthPx}S")    // source width in pixels
                    esc(sink, "*r${heightPx}T")   // source height in rows
                    esc(sink, "*p0x0Y")           // cursor to origin
                    esc(sink, "*r1A")             // begin raster at current position
                    esc(sink, "*b0M")             // compression mode 0: uncompressed
                    pages++
                },
                onBand = { bitmap, _, rows ->
                    val packed = Raster.toPackedMono(bitmap, rows = rows, dither = options.dither)
                    for (y in 0 until rows) {
                        esc(sink, "*b${bytesPerRow}W")
                        sink.write(packed, y * bytesPerRow, bytesPerRow)
                    }
                },
                onPageEnd = {
                    esc(sink, "*rC")              // end raster
                    sink.write(0x0C)              // form feed
                },
            )

            esc(sink, "E")                                   // reset
        }

        if (pages == 0) throw IllegalStateException("PDF produced no pages for PCL output")
        RenderedDocument(out, PrintLanguage.PCL, pages)
    }

    private fun esc(sink: OutputStream, command: String) {
        sink.write(ESC.toInt())
        sink.write(command.toByteArray(Charsets.US_ASCII))
    }
}
