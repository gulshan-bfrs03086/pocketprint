package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.PackedPage
import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What trimming does to a label's TSPL: the SIZE it asks for, and that nothing
 * is sent below it. Continuous stock only; every other stock is the page at the
 * size that was picked, exactly as it was.
 */
class TsplTrimTest {

    private val widthBytes = 75
    private val rows = 599

    private val continuous = LabelStock(sensing = MediaSensing.CONTINUOUS)

    /** A page, TSPL polarity, with a filled block of ink on each of the given row ranges. */
    private fun pageWithInk(vararg inkRows: IntRange, height: Int = rows): PackedPage {
        val packed = ByteArray(widthBytes * height) { 0xFF.toByte() }
        for (range in inkRows) for (row in range) for (b in 10..40) packed[row * widthBytes + b] = 0
        return PackedPage(packed, widthBytes, height)
    }

    private fun stream(stock: LabelStock, page: PackedPage, copies: Int = 1): String = String(
        Tspl(MediaSize.LABEL_75X75, 203, compressBitmaps = false)
            .page(stock, page, sets = 1, copies = copies)
            .build(),
        Charsets.ISO_8859_1,
    )

    private fun sizeLine(stream: String) = stream.lineSequence().first { it.startsWith("SIZE ") }

    /** The lowest dot row any BITMAP in the stream reaches, y + height. */
    private fun lowestBitmapRow(stream: String): Int =
        Regex("BITMAP (\\d+),(\\d+),(\\d+),(\\d+),0,").findAll(stream)
            .maxOfOrNull { it.groupValues[2].toInt() + it.groupValues[4].toInt() } ?: 0

    // -- continuous stock ---------------------------------------------------

    @Test
    fun `a continuous page is as long as its content and a margin`() {
        // Ink to row 300: 301 rows and 32 more is 333 dots, 41.7 mm.
        val out = stream(continuous, pageWithInk(20..300))

        assertEquals("SIZE 75 mm, 41.7 mm", sizeLine(out))
    }

    @Test
    fun `nothing is sent below the length that SIZE asks for`() {
        val out = stream(continuous, pageWithInk(20..300))

        assertTrue("a block reaches row ${lowestBitmapRow(out)}", lowestBitmapRow(out) <= 333)
        assertEquals("the content itself is all there", 301, lowestBitmapRow(out))
    }

    @Test
    fun `a page whose ink runs to the bottom keeps its full length`() {
        val out = stream(continuous, pageWithInk(20..598))

        assertEquals("SIZE 75 mm, 75 mm", sizeLine(out))
    }

    @Test
    fun `a blank page feeds a stub, not nothing`() {
        val out = stream(continuous, pageWithInk())

        assertEquals("SIZE 75 mm, 10.1 mm", sizeLine(out))
        assertFalse("there is nothing to draw", "BITMAP" in out)
        assertTrue("but it is still a label", "PRINT 1,1" in out)
    }

    @Test
    fun `each page is trimmed to itself`() {
        // The pipeline builds one label per page; two pages, two lengths.
        val short = stream(continuous, pageWithInk(10..100))
        val long = stream(continuous, pageWithInk(10..400))

        assertEquals("SIZE 75 mm, 16.7 mm", sizeLine(short)) // 101 + 32 = 133 dots, 16.64 mm
        assertEquals("SIZE 75 mm, 54.2 mm", sizeLine(long)) // 401 + 32 = 433 dots, 54.18 mm
    }

    @Test
    fun `a page taller than the label size is not cut off at it`() {
        // The roll has no end at 75 mm; content that runs past it is printed.
        val tall = pageWithInk(20..650, height = 700)

        val out = stream(continuous, tall)

        // 651 + 32 = 683 dots, 85.46 mm.
        assertEquals("SIZE 75 mm, 85.5 mm", sizeLine(out))
        assertEquals(651, lowestBitmapRow(out))
    }

    @Test
    fun `it still tells the printer there are no gaps`() {
        val out = stream(continuous, pageWithInk(20..300))

        assertTrue(out, "GAP 0 mm, 0 mm" in out)
        assertFalse(out, "GAP 3 mm" in out)
    }

    @Test
    fun `the copies and the rest of the preamble are as they were`() {
        val out = stream(continuous.copy(darkness = 12, speedIps = 6), pageWithInk(20..300), copies = 3)

        assertTrue(out, "PRINT 1,3" in out)
        assertTrue(out, "SPEED 6" in out)
        assertTrue(out, "DENSITY 12" in out)
        assertTrue(out, "SET TEAR ON" in out)
    }

    @Test
    fun `size comes before the page and print after it`() {
        val out = stream(continuous, pageWithInk(20..300))

        assertTrue(out.indexOf("SIZE ") < out.indexOf("CLS"))
        assertTrue(out.indexOf("CLS") < out.indexOf("BITMAP"))
        assertTrue(out.indexOf("BITMAP") < out.indexOf("PRINT"))
    }

    // -- everything else ----------------------------------------------------

    /**
     * The stream as it was before trimming existed: the preamble for the stock,
     * the whole page, PRINT. Built from the same public pieces the old pipeline
     * used, so "exactly as it was" is something the tests compare, not claim.
     */
    private fun asItWas(stock: LabelStock, page: PackedPage, copies: Int = 1): String = String(
        Tspl(MediaSize.LABEL_75X75, 203, compressBitmaps = false)
            .setup(stock)
            .bitmap(0, 0, page.packed, page.widthBytes, page.height)
            .print(sets = 1, copies = copies)
            .build(),
        Charsets.ISO_8859_1,
    )

    private val untouched = listOf(
        "gap" to LabelStock(sensing = MediaSensing.GAP),
        "gap, with a different gap and darkness" to LabelStock(sensing = MediaSensing.GAP, gapMm = 4f, darkness = 12),
        "black mark" to LabelStock(sensing = MediaSensing.BLACK_MARK, gapMm = 2f),
        "continuous, trimming off" to LabelStock(sensing = MediaSensing.CONTINUOUS, trimBlank = false),
    )

    private val pages = listOf(
        "a page with ink in the middle" to pageWithInk(100..300),
        "a page with ink to the bottom" to pageWithInk(20..598),
        "a blank page" to pageWithInk(),
        "two blocks of ink" to pageWithInk(10..60, 400..500),
    )

    @Test
    fun `every stock but trimmed continuous is byte for byte what it was before trimming`() {
        for ((stockName, stock) in untouched) {
            for ((pageName, page) in pages) {
                assertEquals(
                    "$stockName, $pageName",
                    asItWas(stock, page),
                    stream(stock, page),
                )
            }
        }
    }

    @Test
    fun `copies are carried on the stocks that are not trimmed too`() {
        for ((stockName, stock) in untouched) {
            assertEquals(stockName, asItWas(stock, pageWithInk(50..200), copies = 4), stream(stock, pageWithInk(50..200), copies = 4))
        }
    }

    @Test
    fun `trimmed continuous differs from the old stream only in SIZE and in what is not sent`() {
        val page = pageWithInk(20..300)

        val trimmed = stream(continuous, page).replace("SIZE 75 mm, 41.7 mm", "SIZE 75 mm, 75 mm")

        assertEquals(asItWas(continuous, page), trimmed)
    }

    @Test
    fun `gap stock is the page at the size that was picked`() {
        val out = stream(LabelStock(sensing = MediaSensing.GAP), pageWithInk(20..300))

        assertEquals("SIZE 75 mm, 75 mm", sizeLine(out))
        assertTrue(out, "GAP 3 mm, 0 mm" in out)
    }

    @Test
    fun `black mark stock is the page at the size that was picked`() {
        val out = stream(LabelStock(sensing = MediaSensing.BLACK_MARK), pageWithInk(20..300))

        assertEquals("SIZE 75 mm, 75 mm", sizeLine(out))
    }

    @Test
    fun `continuous stock with trimming turned off is the page at the size that was picked`() {
        val out = stream(continuous.copy(trimBlank = false), pageWithInk(20..300))

        assertEquals("SIZE 75 mm, 75 mm", sizeLine(out))
        assertTrue(out, "GAP 0 mm, 0 mm" in out)
    }

    @Test
    fun `gap stock is not trimmed even when the page is mostly blank`() {
        val out = stream(LabelStock(sensing = MediaSensing.GAP, trimBlank = true), pageWithInk())

        assertEquals("SIZE 75 mm, 75 mm", sizeLine(out))
    }
}
