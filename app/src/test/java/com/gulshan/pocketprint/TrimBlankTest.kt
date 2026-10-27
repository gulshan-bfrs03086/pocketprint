package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.TrimBlank
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page on a continuous roll is as long as its ink and a margin, not as long as
 * the size somebody picked. These pin the arithmetic; [TsplTrimTest] pins what
 * it does to the stream.
 */
class TrimBlankTest {

    private val widthBytes = 75 // a 75 mm label at 203 dpi, 599 dots
    private val height = 599

    /** A page with ink at the given rows. TSPL's polarity: 0xFF is blank, 0 is a dot. */
    private fun page(vararg inkRows: Int, rows: Int = height, widthBytes: Int = this.widthBytes): ByteArray {
        val packed = ByteArray(widthBytes * rows) { 0xFF.toByte() }
        for (row in inkRows) packed[row * widthBytes + widthBytes / 2] = 0
        return packed
    }

    private fun keep(vararg inkRows: Int, dpi: Int = 203, rows: Int = height) =
        TrimBlank.rowsToKeep(page(*inkRows, rows = rows), widthBytes, rows, dpi)

    // -- how much is kept ---------------------------------------------------

    @Test
    fun `a page is kept down to its last ink and a margin`() {
        // Last ink on row 200: 201 rows of page, and 4 mm (32 dots at 203 dpi).
        assertEquals(201 + 32, keep(10, 200))
    }

    @Test
    fun `it is the lowest ink that counts, wherever the rest is`() {
        assertEquals(301 + 32, keep(0, 300, 120))
    }

    @Test
    fun `the margin is four millimetres at any resolution`() {
        // 4 mm is 32 dots at 203 dpi and 48 at 300.
        assertEquals(101 + 32, keep(100, dpi = 203))
        assertEquals(101 + 48, keep(100, dpi = 300))
    }

    @Test
    fun `a page is never made longer than it is`() {
        // Ink on the last row, and ink so near the bottom that the margin
        // would run past it.
        assertEquals(height, keep(height - 1))
        assertEquals(height, keep(590))
    }

    @Test
    fun `a page with nothing on it keeps a short stub, not nothing`() {
        // 10 mm is 80 dots at 203 dpi. Zero rows is not a label.
        assertEquals(80, keep())
    }

    @Test
    fun `a page with only a little at the top is not cut shorter than the stub`() {
        // Ink on row 5 and a 4 mm margin is 38 rows, under the 10 mm minimum.
        assertEquals(80, keep(5))
    }

    @Test
    fun `a page shorter than the stub is left as it is`() {
        assertEquals(50, keep(rows = 50))
        assertEquals(50, keep(10, rows = 50))
    }

    @Test
    fun `ink in a row's last byte counts`() {
        val packed = ByteArray(widthBytes * height) { 0xFF.toByte() }
        packed[200 * widthBytes + widthBytes - 1] = 0x7F // one dot, in the last byte of the row

        assertEquals(201 + 32, TrimBlank.rowsToKeep(packed, widthBytes, height, 203))
    }

    @Test
    fun `ink in a row's first byte counts`() {
        val packed = ByteArray(widthBytes * height) { 0xFF.toByte() }
        packed[200 * widthBytes] = 0xFE.toByte()

        assertEquals(201 + 32, TrimBlank.rowsToKeep(packed, widthBytes, height, 203))
    }

    @Test
    fun `finding the last ink row`() {
        assertEquals(-1, TrimBlank.lastInkRow(page(), widthBytes, height))
        assertEquals(0, TrimBlank.lastInkRow(page(0), widthBytes, height))
        assertEquals(598, TrimBlank.lastInkRow(page(3, 598), widthBytes, height))
    }

    // -- the length SIZE is given -------------------------------------------

    @Test
    fun `a length in dots becomes millimetres rounded up to a tenth`() {
        // 333 dots at 203 dpi is 41.67 mm.
        assertEquals("41.7", TrimBlank.mmText(333, 203))
    }

    @Test
    fun `it is rounded up, never down, so the last row is not cut off`() {
        // 427 dots is 53.42 mm: 53.4 would be 427.2 dots at 8 a millimetre
        // but only 426.8 at 203 / 25.4 - a dot short on a printer that
        // divides the other way.
        assertEquals("53.5", TrimBlank.mmText(427, 203))
    }

    @Test
    fun `a length that is a whole tenth is not nudged up`() {
        // 200 dpi is 7.874 dots a millimetre, so 400 dots is 50.8 mm exactly.
        assertEquals("50.8", TrimBlank.mmText(400, 200))
    }

    @Test
    fun `a whole number of millimetres has no decimal point`() {
        // A full 75 x 75 mm page, 599 dots: 74.95 mm, rounded up.
        assertEquals("75", TrimBlank.mmText(599, 203))
    }

    @Test
    fun `lengths at another resolution`() {
        // 300 dpi: 48 dots is 4.064 mm.
        assertEquals("4.1", TrimBlank.mmText(48, 300))
    }

    @Test
    fun `a length in millimetres becomes whole dots, rounded up`() {
        assertEquals(32, TrimBlank.dots(4f, 203))
        assertEquals(80, TrimBlank.dots(10f, 203))
        assertEquals(48, TrimBlank.dots(4f, 300))
    }

    // -- which stock -------------------------------------------------------

    @Test
    fun `continuous stock is trimmed`() {
        assertTrue(TrimBlank.applies(LabelStock(sensing = MediaSensing.CONTINUOUS)))
    }

    @Test
    fun `continuous stock is not trimmed when it was turned off`() {
        assertFalse(TrimBlank.applies(LabelStock(sensing = MediaSensing.CONTINUOUS, trimBlank = false)))
    }

    @Test
    fun `gap and black mark stock are never trimmed, whatever the setting says`() {
        assertFalse(TrimBlank.applies(LabelStock(sensing = MediaSensing.GAP, trimBlank = true)))
        assertFalse(TrimBlank.applies(LabelStock(sensing = MediaSensing.BLACK_MARK, trimBlank = true)))
    }

    @Test
    fun `trimming is on unless somebody turns it off`() {
        assertTrue(LabelStock().trimBlank)
    }
}
