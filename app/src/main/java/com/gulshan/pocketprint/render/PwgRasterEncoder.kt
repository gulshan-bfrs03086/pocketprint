package com.gulshan.pocketprint.render

import android.content.Context
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.DuplexMode
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/**
 * PWG Raster (PWG 5102.4) writer.
 *
 * Layout: a four-byte file sync word, then, per page, a fixed 1796-byte page
 * header followed by RLE-compressed scan lines. All integers are big-endian,
 * which is what the "RaS2" sync word signifies.
 *
 * This is the mandatory fallback format for IPP Everywhere: a printer may
 * decline application/pdf, but it must accept image/pwg-raster.
 */
object PwgRasterEncoder {

    private const val SYNC_WORD = "RaS2"
    private const val PAGE_HEADER_BYTES = 1796

    enum class Mode(
        val bitsPerColor: Int,
        val bitsPerPixel: Int,
        val numColors: Int,
        val colorSpace: Int,
    ) {
        /** sGray, 8 bits: 0 is black, 255 is white. */
        GRAY_8(8, 8, 1, 18),

        /** sRGB, 24 bits chunked. */
        RGB_24(8, 24, 3, 19),
    }

    /** Converts a PDF into a PWG raster stream at the printer's resolution. */
    suspend fun fromPdf(
        context: Context,
        pdf: File,
        options: PrintOptions,
        supportsColor: Boolean,
    ): RenderedDocument = withContext(Dispatchers.IO) {
        val mode = if (supportsColor && options.colorMode == ColorMode.COLOR) {
            Mode.RGB_24
        } else {
            Mode.GRAY_8
        }

        val out = Spool.newFile(context, ".pwg")
        var pages = 0

        val bytesPerPixel = mode.bitsPerPixel / 8

        BufferedOutputStream(out.outputStream(), 256 * 1024).use { sink ->
            sink.write(SYNC_WORD.toByteArray(Charsets.US_ASCII))

            PdfRasterizer.forEachPageBanded(
                file = pdf,
                dpi = options.dpi,
                pageRange = options.pageRange,
                onPageStart = { _, widthPx, heightPx ->
                    sink.write(
                        pageHeader(
                            width = widthPx,
                            height = heightPx,
                            bytesPerLine = widthPx * bytesPerPixel,
                            options = options,
                            mode = mode,
                            totalPages = 0,
                        ),
                    )
                    pages++
                },
                onBand = { bitmap, _, rows ->
                    // Line groups are self-contained, so concatenating the bands
                    // produces exactly the same stream as encoding the full page.
                    val raw = when (mode) {
                        Mode.GRAY_8 -> Raster.toGrayBytes(bitmap, rows)
                        Mode.RGB_24 -> Raster.toRgbBytes(bitmap, rows)
                    }
                    encodeLines(sink, raw, bitmap.width, rows, bytesPerPixel)
                },
            )
        }

        if (pages == 0) throw IllegalStateException("PDF produced no pages to rasterize")
        RenderedDocument(out, PrintLanguage.PWG_RASTER, pages)
    }

    /**
     * PWG line encoding. Each group starts with a repeat count, then packets:
     *   0..127   -> the next single pixel repeats (count + 1) times
     *   129..255 -> the next (257 - count) pixels are literal
     *   128      -> reserved, never emitted
     * Identical adjacent lines collapse into one group, which is why a mostly
     * white page compresses so well.
     */
    internal fun encodeLines(
        sink: OutputStream,
        raw: ByteArray,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
    ) {
        val bytesPerLine = width * bytesPerPixel
        var y = 0

        while (y < height) {
            val lineStart = y * bytesPerLine

            // Collapse runs of identical lines (max 256 per group).
            var repeat = 0
            while (
                repeat < 255 &&
                y + repeat + 1 < height &&
                regionsEqual(raw, lineStart, (y + repeat + 1) * bytesPerLine, bytesPerLine)
            ) repeat++

            sink.write(repeat)
            encodeLine(sink, raw, lineStart, width, bytesPerPixel)
            y += repeat + 1
        }
    }

    internal fun encodeLine(
        sink: OutputStream,
        raw: ByteArray,
        lineStart: Int,
        width: Int,
        bytesPerPixel: Int,
    ) {
        var x = 0
        while (x < width) {
            val pixelStart = lineStart + x * bytesPerPixel

            // How many times does this pixel repeat, capped at 128?
            var run = 1
            while (
                run < 128 && x + run < width &&
                regionsEqual(raw, pixelStart, pixelStart + run * bytesPerPixel, bytesPerPixel)
            ) run++

            if (run > 1) {
                sink.write(run - 1)
                sink.write(raw, pixelStart, bytesPerPixel)
                x += run
                continue
            }

            // No repeat here: gather literal pixels until a repeat begins.
            var literal = 1
            while (literal < 128 && x + literal < width) {
                val here = lineStart + (x + literal) * bytesPerPixel
                val next = here + bytesPerPixel
                val startsARun = x + literal + 1 < width &&
                    regionsEqual(raw, here, next, bytesPerPixel)
                if (startsARun) break
                literal++
            }

            if (literal == 1) {
                // A literal packet encodes 2..128 pixels (257 - count), so a
                // lone pixel has to go out as a repeat-once packet instead.
                sink.write(0)
                sink.write(raw, pixelStart, bytesPerPixel)
            } else {
                sink.write(257 - literal)
                sink.write(raw, pixelStart, literal * bytesPerPixel)
            }
            x += literal
        }
    }

    private fun regionsEqual(data: ByteArray, a: Int, b: Int, length: Int): Boolean {
        if (a + length > data.size || b + length > data.size) return false
        for (i in 0 until length) if (data[a + i] != data[b + i]) return false
        return true
    }

    private fun pageHeader(
        width: Int,
        height: Int,
        bytesPerLine: Int,
        options: PrintOptions,
        mode: Mode,
        totalPages: Int,
    ): ByteArray {
        val h = ByteArray(PAGE_HEADER_BYTES)

        // MediaClass must read "PwgRaster" for PWG raster streams.
        putCString(h, 0, "PwgRaster")
        putCString(h, 64, "")                    // MediaColor
        putCString(h, 128, "")                   // MediaType
        putCString(h, 192, "")                   // PrintContentOptimize

        putU32(h, 268, 0)                        // CutMedia
        putU32(h, 272, if (options.duplex != DuplexMode.SIMPLEX) 1 else 0)
        putU32(h, 276, options.dpi)              // HWResolution X
        putU32(h, 280, options.dpi)              // HWResolution Y
        putU32(h, 300, 0)                        // InsertSheet
        putU32(h, 304, 0)                        // Jog
        putU32(h, 308, 0)                        // LeadingEdge
        putU32(h, 324, 0)                        // MediaPosition
        putU32(h, 328, 0)                        // MediaWeight
        putU32(h, 340, options.copies.coerceAtLeast(1))
        putU32(h, 344, 0)                        // Orientation (already baked in)

        // PageSize is in PDF points.
        putU32(h, 352, Math.round(options.mediaSize.widthPoints))
        putU32(h, 356, Math.round(options.mediaSize.heightPoints))

        // Tumble distinguishes short-edge from long-edge binding.
        putU32(h, 368, if (options.duplex == DuplexMode.SHORT_EDGE) 1 else 0)
        putU32(h, 372, width)
        putU32(h, 376, height)
        putU32(h, 384, mode.bitsPerColor)
        putU32(h, 388, mode.bitsPerPixel)
        putU32(h, 392, bytesPerLine)
        putU32(h, 396, 0)                        // ColorOrder: chunked
        putU32(h, 400, mode.colorSpace)
        putU32(h, 420, mode.numColors)

        putU32(h, 452, totalPages)               // TotalPageCount, 0 = unknown
        putU32(h, 456, 1)                        // CrossFeedTransform
        putU32(h, 460, 1)                        // FeedTransform
        putU32(h, 464, 0)                        // ImageBoxLeft
        putU32(h, 468, 0)                        // ImageBoxTop
        putU32(h, 472, width)                    // ImageBoxRight
        putU32(h, 476, height)                   // ImageBoxBottom
        putU32(h, 480, 0)                        // AlternatePrimary
        putU32(h, 484, 0)                        // PrintQuality: 0 = default

        putCString(h, 1668, "")                  // RenderingIntent
        putCString(h, 1732, options.mediaSize.id) // PageSizeName

        return h
    }

    private fun putU32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    /** Fixed-width, NUL-padded ASCII field. */
    private fun putCString(buf: ByteArray, offset: Int, value: String, size: Int = 64) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val n = minOf(bytes.size, size - 1)
        System.arraycopy(bytes, 0, buf, offset, n)
        for (i in offset + n until offset + size) buf[i] = 0
    }
}
