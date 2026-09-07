package com.gulshan.pocketprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gulshan.pocketprint.render.PdfRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Banding must not change the picture, only the peak memory used to make it.
 *
 * A 600 dpi A4 page is around 35 million pixels; at four bytes each that is
 * more heap than a phone will hand out, so pages are rendered in horizontal
 * strips. Each strip is produced by rendering the whole page through a matrix
 * translated by the strip's offset, which is exactly where an off-by-one lives:
 * a wrong offset shifts content by a few rows per band and the page comes out
 * looking almost right, with seams.
 *
 * On device because PdfRenderer and Bitmap are the things being tested. The PWG
 * equivalent is covered at the byte level by PwgRasterTest; this is the layer
 * below it, where the pixels come from.
 */
@RunWith(AndroidJUnit4::class)
class PdfRasterizerBandingTest {

    private lateinit var pdf: File

    /** Fixed page geometry, in PostScript points, so the raster sizes are predictable. */
    private val pageWidthPt = 595
    private val pageHeightPt = 842

    /** Narrow enough that several bands are needed at the width below. */
    private val targetWidthPx = 600
    private val bandPixels = 40_000

    @Before
    fun writePdf() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        pdf = File.createTempFile("banding-", ".pdf", dir)

        val document = PdfDocument()
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, 1).create(),
        )
        page.canvas.apply {
            drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
            // Hard horizontal stripes at known heights. A band offset that is
            // wrong by even one row moves an edge, and an edge is the one thing
            // a row profile cannot hide.
            for (i in 0 until 20) {
                val top = (i * 2 + 1) * pageHeightPt / 41f
                drawRect(0f, top, pageWidthPt.toFloat(), top + pageHeightPt / 82f, ink)
            }
            // A vertical rule too, so a horizontal shift would also show.
            drawRect(pageWidthPt / 2f - 2, 0f, pageWidthPt / 2f + 2, pageHeightPt.toFloat(), ink)
        }
        document.finishPage(page)
        pdf.outputStream().use { document.writeTo(it) }
        document.close()
    }

    private fun fullPage(): Bitmap {
        var copy: Bitmap? = null
        PdfRasterizer.forEachPage(pdf, targetWidthPx = targetWidthPx) { _, bitmap ->
            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return requireNotNull(copy) { "the rasterizer produced no page" }
    }

    private class Banded(
        val stitched: Bitmap,
        val bandCount: Int,
        val offsets: List<Int>,
        val rows: List<Int>,
        val pageWidth: Int,
        val pageHeight: Int,
    )

    private fun banded(): Banded {
        var stitched: Bitmap? = null
        var canvas: Canvas? = null
        var count = 0
        val offsets = mutableListOf<Int>()
        val rows = mutableListOf<Int>()
        var w = 0
        var h = 0

        PdfRasterizer.forEachPageBanded(
            pdf,
            targetWidthPx = targetWidthPx,
            maxBandPixels = bandPixels,
            onPageStart = { _, widthPx, heightPx ->
                w = widthPx
                h = heightPx
                stitched = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                canvas = Canvas(stitched!!)
            },
            onBand = { bitmap, yOffset, bandRows ->
                count++
                offsets += yOffset
                rows += bandRows
                // Only the first bandRows of the band are this page's; the
                // bitmap is reused and the last band is short.
                val slice = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bandRows)
                canvas!!.drawBitmap(slice, 0f, yOffset.toFloat(), null)
                slice.recycle()
            },
        )
        return Banded(requireNotNull(stitched), count, offsets, rows, w, h)
    }

    @Test
    fun theTestActuallyBands() {
        // Without this the rest could pass by rendering one band the size of the
        // page, which is the full-page path wearing a different name.
        val banded = banded()

        assertTrue(
            "expected several bands, got ${banded.bandCount}",
            banded.bandCount > 3,
        )
    }

    @Test
    fun everyRowIsCoveredExactlyOnceAndInOrder() {
        val banded = banded()

        assertEquals(banded.pageHeight, banded.rows.sum())
        // Contiguous and ascending: each band starts where the last one ended.
        var expected = 0
        banded.offsets.zip(banded.rows).forEach { (offset, rows) ->
            assertEquals("band did not start where the previous one ended", expected, offset)
            expected += rows
        }
        assertEquals(banded.pageHeight, expected)
    }

    @Test
    fun bandedOutputMatchesTheFullPageRender() {
        val full = fullPage()
        val banded = banded()

        assertEquals("width", full.width, banded.stitched.width)
        assertEquals("height", full.height, banded.stitched.height)

        val w = full.width
        val h = full.height
        val a = IntArray(w)
        val b = IntArray(w)
        var worst = 0
        var differing = 0
        var worstRow = -1

        for (y in 0 until h) {
            full.getPixels(a, 0, w, 0, y, w, 1)
            banded.stitched.getPixels(b, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if (a[x] == b[x]) continue
                differing++
                val delta = maxOf(
                    abs((a[x] ushr 16 and 0xFF) - (b[x] ushr 16 and 0xFF)),
                    abs((a[x] ushr 8 and 0xFF) - (b[x] ushr 8 and 0xFF)),
                    abs((a[x] and 0xFF) - (b[x] and 0xFF)),
                )
                if (delta > worst) {
                    worst = delta
                    worstRow = y
                }
            }
        }

        val fraction = differing.toDouble() / (w * h)
        // Rendering the same page through a translated matrix can differ by an
        // antialiasing step; being shifted by a row cannot. The numbers are in
        // the message so a failure says which of the two happened.
        assertTrue(
            "worst channel delta $worst at row $worstRow, " +
                "${"%.4f".format(fraction * 100)}% of pixels differ",
            worst <= 8 && fraction < 0.01,
        )
    }

    @Test
    fun stripeEdgesLandOnTheSameRows() {
        // The sharpest statement of "banding did not shift anything". A row
        // profile of a striped page is a square wave; a band offset that is
        // wrong moves the edges, and no amount of antialiasing does that.
        val full = fullPage()
        val banded = banded()

        val a = darkRowProfile(full)
        val b = darkRowProfile(banded.stitched)

        assertEquals(a.size, b.size)
        val moved = a.indices.count { abs(a[it] - b[it]) > full.width / 20 }
        assertEquals(
            "rows where the ink coverage moved: $moved of ${a.size}",
            0,
            moved,
        )
    }

    private fun darkRowProfile(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val row = IntArray(w)
        return IntArray(bitmap.height) { y ->
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            row.count { p ->
                val lum = 0.299f * (p ushr 16 and 0xFF) +
                    0.587f * (p ushr 8 and 0xFF) + 0.114f * (p and 0xFF)
                lum < 128f
            }
        }
    }
}
