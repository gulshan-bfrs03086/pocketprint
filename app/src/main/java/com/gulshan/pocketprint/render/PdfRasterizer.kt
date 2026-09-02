package com.gulshan.pocketprint.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Turns PDF pages into bitmaps using the platform renderer. Every raster target
 * (PWG, ESC/POS, TSPL, ZPL) funnels through here, so PDF stays the single
 * intermediate format in the pipeline.
 *
 * PdfRenderer allows only one open page at a time and is not thread-safe, so
 * pages are produced strictly in sequence.
 */
object PdfRasterizer {

    fun pageCount(file: File): Int = openRenderer(file).use { (pfd, renderer) ->
        val count = renderer.pageCount
        renderer.close()
        pfd.close()
        count
    }

    /**
     * Renders each page in turn, handing the bitmap to [block]. The bitmap is
     * recycled once the callback returns, so callers must not retain it.
     *
     * [targetWidthPx] fixes the raster width (thermal printers have a fixed head
     * width); height follows the page aspect ratio. When null, [dpi] is used.
     */
    fun forEachPage(
        file: File,
        dpi: Int = 203,
        targetWidthPx: Int? = null,
        pageRange: IntRange? = null,
        block: (index: Int, bitmap: Bitmap) -> Unit,
    ) {
        openRenderer(file).use { (pfd, renderer) ->
            try {
                for (i in 0 until renderer.pageCount) {
                    // pageRange is 1-based and inclusive, as the user sees it.
                    if (pageRange != null && (i + 1) !in pageRange) continue

                    renderer.openPage(i).use { page ->
                        val widthPx = targetWidthPx
                            ?: Math.round(page.width * dpi / 72f).coerceAtLeast(1)
                        val heightPx = Math.round(
                            page.height.toFloat() * widthPx / page.width.toFloat(),
                        ).coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(
                            widthPx, heightPx, Bitmap.Config.ARGB_8888,
                        )
                        // Unpainted PDF regions are transparent; printers need white.
                        Canvas(bitmap).drawColor(Color.WHITE)

                        val matrix = Matrix().apply {
                            setScale(
                                widthPx.toFloat() / page.width,
                                heightPx.toFloat() / page.height,
                            )
                        }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        if (com.gulshan.pocketprint.BuildConfig.DEBUG) {
                            val row = IntArray(widthPx)
                            var dark = 0
                            var opaque = 0
                            var y = 0
                            while (y < heightPx) {
                                bitmap.getPixels(row, 0, widthPx, 0, y, widthPx, 1)
                                for (p in row) {
                                    if ((p ushr 24 and 0xFF) == 255) opaque++
                                    val lum = 0.299f * (p ushr 16 and 0xFF) +
                                        0.587f * (p ushr 8 and 0xFF) + 0.114f * (p and 0xFF)
                                    if (lum < 128f) dark++
                                }
                                y += 8 // sample every 8th row
                            }
                            android.util.Log.i(
                                "PdfDiag",
                                "page $i pdfPt=${page.width}x${page.height} " +
                                    "raster=${widthPx}x$heightPx dpi=$dpi " +
                                    "sampledDarkPx=$dark opaquePx=$opaque",
                            )
                        }

                        try {
                            block(i, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            } finally {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }

    /**
     * Renders a page in horizontal bands instead of one full-page bitmap.
     *
     * A4 at 600 dpi is 4958 x 7016 pixels; a single ARGB_8888 bitmap for that is
     * 139 MB, and the conversion buffers on top of it push past any reasonable
     * heap. Bands keep peak memory proportional to the band height instead of
     * the page height.
     *
     * [onPageStart] receives the full page dimensions before any band arrives.
     * The band bitmap is reused between callbacks, so it must not be retained.
     */
    fun forEachPageBanded(
        file: File,
        dpi: Int = 300,
        targetWidthPx: Int? = null,
        pageRange: IntRange? = null,
        maxBandPixels: Int = 2_000_000,
        onPageStart: (index: Int, widthPx: Int, heightPx: Int) -> Unit,
        onBand: (bitmap: Bitmap, yOffset: Int, rows: Int) -> Unit,
        onPageEnd: (index: Int) -> Unit = {},
    ) {
        openRenderer(file).use { holder ->
            val renderer = holder.renderer
            for (i in 0 until renderer.pageCount) {
                if (pageRange != null && (i + 1) !in pageRange) continue

                renderer.openPage(i).use { page ->
                    val widthPx = (targetWidthPx
                        ?: Math.round(page.width * dpi / 72f)).coerceAtLeast(1)
                    val heightPx = Math.round(
                        page.height.toFloat() * widthPx / page.width.toFloat(),
                    ).coerceAtLeast(1)

                    onPageStart(i, widthPx, heightPx)

                    val bandRows = (maxBandPixels / widthPx).coerceIn(1, heightPx)
                    val band = Bitmap.createBitmap(widthPx, bandRows, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(band)
                    val scaleX = widthPx.toFloat() / page.width
                    val scaleY = heightPx.toFloat() / page.height

                    try {
                        var y = 0
                        while (y < heightPx) {
                            val rows = minOf(bandRows, heightPx - y)

                            // Unpainted PDF area is transparent; printers need white.
                            canvas.drawColor(Color.WHITE, android.graphics.PorterDuff.Mode.SRC)

                            // Shift the page up so this band lands at the bitmap origin.
                            val matrix = Matrix().apply {
                                setScale(scaleX, scaleY)
                                postTranslate(0f, -y.toFloat())
                            }
                            page.render(
                                band, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                            )

                            onBand(band, y, rows)
                            y += rows
                        }
                        onPageEnd(i)
                    } finally {
                        band.recycle()
                    }
                }
            }
        }
    }

    private fun openRenderer(file: File): Holder {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return Holder(pfd, PdfRenderer(pfd))
    }

    private class Holder(
        val pfd: ParcelFileDescriptor,
        val renderer: PdfRenderer,
    ) : AutoCloseable {
        operator fun component1() = pfd
        operator fun component2() = renderer
        override fun close() {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

/** Bitmap helpers shared by the raster encoders. */
object Raster {

    /**
     * Floyd-Steinberg dithering straight into packed 1-bit rows, MSB first,
     * where a set bit means ink.
     *
     * The error diffusion only ever needs the current and next row, so this
     * streams a row at a time rather than lifting the whole image into an
     * IntArray plus a FloatArray. Only [rows] rows are read, so a reused band
     * bitmap taller than its valid content is handled correctly.
     */
    fun toPackedMono(
        source: Bitmap,
        rows: Int = source.height,
        threshold: Int = 128,
        dither: Boolean = true,
    ): ByteArray {
        val w = source.width
        val h = rows.coerceIn(0, source.height)
        val bytesPerRow = (w + 7) / 8
        val out = ByteArray(bytesPerRow * h)
        if (h == 0 || w == 0) return out

        val rowPixels = IntArray(w)
        // Padded by one on each side so the diagonal terms need no bounds checks.
        var current = FloatArray(w + 2)
        var next = FloatArray(w + 2)

        for (y in 0 until h) {
            source.getPixels(rowPixels, 0, w, 0, y, w, 1)
            val rowBase = y * bytesPerRow

            for (x in 0 until w) {
                val luminance = luminanceOf(rowPixels[x])
                val value = if (dither) luminance + current[x + 1] else luminance
                val quantised = if (value < threshold) 0f else 255f

                if (quantised == 0f) {
                    out[rowBase + (x shr 3)] =
                        (out[rowBase + (x shr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                }

                if (dither) {
                    val error = value - quantised
                    current[x + 2] += error * 7f / 16f
                    next[x] += error * 3f / 16f
                    next[x + 1] += error * 5f / 16f
                    next[x + 2] += error * 1f / 16f
                }
            }

            if (dither) {
                val spent = current
                current = next
                next = spent
                java.util.Arrays.fill(next, 0f)
            }
        }
        return out
    }

    /** 8-bit grayscale rows, 0 = black, 255 = white (sGray colour space). */
    fun toGrayBytes(source: Bitmap, rows: Int = source.height): ByteArray {
        val w = source.width
        val h = rows.coerceIn(0, source.height)
        val out = ByteArray(w * h)
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            source.getPixels(rowPixels, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                out[base + x] = luminanceOf(rowPixels[x]).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** 24-bit sRGB rows, chunked. */
    fun toRgbBytes(source: Bitmap, rows: Int = source.height): ByteArray {
        val w = source.width
        val h = rows.coerceIn(0, source.height)
        val out = ByteArray(w * h * 3)
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            source.getPixels(rowPixels, 0, w, 0, y, w, 1)
            val base = y * w * 3
            for (x in 0 until w) {
                val p = compositeOnWhite(rowPixels[x])
                out[base + x * 3] = ((p ushr 16) and 0xFF).toByte()
                out[base + x * 3 + 1] = ((p ushr 8) and 0xFF).toByte()
                out[base + x * 3 + 2] = (p and 0xFF).toByte()
            }
        }
        return out
    }

    /** Transparent pixels are composited onto white before any conversion. */
    private fun compositeOnWhite(pixel: Int): Int {
        val a = (pixel ushr 24) and 0xFF
        if (a == 255) return pixel
        val alpha = a / 255f
        val r = (((pixel ushr 16) and 0xFF) * alpha + 255 * (1 - alpha)).toInt()
        val g = (((pixel ushr 8) and 0xFF) * alpha + 255 * (1 - alpha)).toInt()
        val b = ((pixel and 0xFF) * alpha + 255 * (1 - alpha)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun luminanceOf(pixel: Int): Float {
        val p = compositeOnWhite(pixel)
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
