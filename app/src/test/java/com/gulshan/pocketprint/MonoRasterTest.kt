package com.gulshan.pocketprint

import android.graphics.Color
import com.gulshan.pocketprint.render.Raster
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Bit order and polarity for the raster that goes to the printer — and, since
 * the preview reads the same bits back, for what the user is shown before a
 * label is consumed. Both are easy to get backwards: TSPL inverts polarity on
 * the wire, so the one place that says which way round it is here needs to be
 * unambiguous.
 */
class MonoRasterTest {

    private val B = Color.BLACK
    private val W = Color.WHITE

    @Test
    fun `bits run most significant first, and a set bit is ink`() {
        val packed = byteArrayOf(0b1000_0001.toByte())
        assertArrayEquals(
            intArrayOf(B, W, W, W, W, W, W, B),
            Raster.monoRowPixels(packed, rowBase = 0, width = 8),
        )
    }

    @Test
    fun `a row narrower than its byte ignores the padding bits`() {
        // 5 dots stored in one byte: the low three bits are padding and must
        // not appear as three stray dots of ink at the end of every row.
        val packed = byteArrayOf(0b1010_1111.toByte())
        assertArrayEquals(
            intArrayOf(B, W, B, W, B),
            Raster.monoRowPixels(packed, rowBase = 0, width = 5),
        )
    }

    @Test
    fun `rows are addressed by their own offset`() {
        val packed = byteArrayOf(0b0000_0000, 0b1111_1111.toByte())
        assertArrayEquals(
            intArrayOf(W, W, W, W, W, W, W, W),
            Raster.monoRowPixels(packed, rowBase = 0, width = 8),
        )
        assertArrayEquals(
            intArrayOf(B, B, B, B, B, B, B, B),
            Raster.monoRowPixels(packed, rowBase = 1, width = 8),
        )
    }

    @Test
    fun `a truncated payload reads as blank rather than throwing`() {
        // A preview of a short stream should look short, not crash.
        assertArrayEquals(
            intArrayOf(W, W, W, W, W, W, W, W),
            Raster.monoRowPixels(ByteArray(0), rowBase = 0, width = 8),
        )
    }
}
