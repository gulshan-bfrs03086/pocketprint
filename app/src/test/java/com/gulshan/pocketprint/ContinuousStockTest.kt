package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.InkBlocks
import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.label.TsplBitmap
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Stock with no gap: a continuous label roll, or a receipt roll printed in
 * TSPL. The printer is told to stop looking for a gap (GAP 0 mm, 0 mm) and feeds
 * exactly the height it was given, so the bitmap is the only thing that varies -
 * and it is the same bitmap path the gapped labels use, now with trimming and
 * compression in it.
 *
 * What is pinned is that nothing about those changes depends on the sensing, and
 * that the page a receipt makes - narrow, and as tall as the paper - survives
 * them intact.
 */
class ContinuousStockTest {

    private val continuous = LabelStock(sensing = MediaSensing.CONTINUOUS)

    private fun commandsOf(stream: ByteArray) = String(stream, Charsets.ISO_8859_1).split("\r\n")

    @Test
    fun `continuous stock turns gap sensing off, and compression does not change that`() {
        for (compress in listOf(false, true)) {
            val stream = Tspl(MediaSize.LABEL_75X75, 203, compress).apply {
                setup(continuous)
                text(20, 20, "NO GAP", font = "3")
                print(1, 1)
            }.build()

            val lines = commandsOf(stream)
            assertEquals("SIZE 75 mm, 75 mm", lines[0])
            assertEquals("a gap would make the printer hunt for one", "GAP 0 mm, 0 mm", lines[1])
            assertFalse("BLINE or a gap height leaked in", lines.any { it.startsWith("BLINE") || it == "GAP 3 mm, 0 mm" })
            assertTrue(lines.contains("CLS"))
            assertTrue(lines.contains("PRINT 1,1"))
        }
    }

    @Test
    fun `a bitmap on continuous stock is the same bytes as on gapped stock`() {
        // Sensing is in the preamble only. The picture must not depend on it.
        val page = ByteArray(75 * 120) { 0xFF.toByte() }
        for (row in 20 until 80) for (col in 10 until 60) page[row * 75 + col] = (if (col % 2 == 0) 0x00 else 0x55).toByte()

        fun body(stock: LabelStock, compress: Boolean): ByteArray {
            val stream = Tspl(MediaSize.LABEL_75X75, 203, compress).apply {
                setup(stock)
                bitmap(0, 0, page, 75, 120)
                print(1, 1)
            }.build()
            val at = String(stream, Charsets.ISO_8859_1).indexOf("CLS\r\n") + 5
            return stream.copyOfRange(at, stream.size)
        }

        for (compress in listOf(false, true)) {
            assertArrayEquals(
                "compress=$compress",
                body(LabelStock(), compress), body(continuous, compress),
            )
        }
    }

    /** A receipt as a page: 80 mm paper is 72 bytes wide, and the page is as tall as the paper. */
    private fun receiptPage(heightRows: Int): ByteArray {
        val page = ByteArray(72 * heightRows) { 0xFF.toByte() }
        val random = Random(80)
        // Glyph-like bytes: a few patterns repeated, the way a line of type is,
        // and not noise, which nothing compresses.
        val glyphs = byteArrayOf(0x00, 0x7E, 0x18, 0x3C, 0xE7.toByte(), 0xC3.toByte(), 0x81.toByte(), 0x24)
        // Lines of text-like ink down the page, a barcode block, a rule, and a
        // lot of white, the way a receipt is.
        var y = 30
        while (y + 24 < heightRows * 3 / 5) {
            val width = random.nextInt(20, 66)
            for (row in y until y + 20) for (col in 3 until 3 + width) page[row * 72 + col] = glyphs[(col * 3 + row / 5) % glyphs.size]
            y += 34
        }
        for (row in heightRows * 3 / 5 until heightRows * 3 / 5 + 90) for (col in 10 until 62) page[row * 72 + col] = (if (col % 2 == 0) 0x00 else 0xAA).toByte()
        for (col in 0 until 72) page[(heightRows * 3 / 5 + 100) * 72 + col] = 0x00
        return page
    }

    @Test
    fun `a receipt-sized page is sent whole, and a fraction of its full size`() {
        // 80 mm paper at 203 dpi: 576 dots = 72 bytes across, 2400 rows (about
        // 300 mm) down. As one bitmap that is 172,800 bytes - at 190 bytes a
        // second, fifteen minutes on the printer measured.
        val page = receiptPage(2400)
        val full = page.size

        for (compress in listOf(false, true)) {
            val started = System.nanoTime()
            val commands = TsplBitmap.encode(page, 72, 2400, 0, 0, compress)
            val millis = (System.nanoTime() - started) / 1_000_000

            assertTrue("took $millis ms", millis < 3_000)

            // Nothing was lost: redraw it with a plain decoder and compare.
            val redrawn = ByteArray(full) { 0xFF.toByte() }
            var p = 0
            while (p < commands.size) {
                val dots: Int; val yDots: Int; val w: Int; val h: Int; val data: ByteArray
                if (commands[p] == '!'.code.toByte()) {
                    fun le(at: Int) = (commands[p + at].toInt() and 0xFF) or ((commands[p + at + 1].toInt() and 0xFF) shl 8)
                    dots = le(2); yDots = le(4); w = le(6); h = le(8)
                    val decoded = LzssDecoder.decode(commands, p + 16, w * h)
                    data = decoded.bytes; p = decoded.end
                } else {
                    var q = p + 7
                    val f = IntArray(5)
                    for (i in 0 until 5) { val s = q; while (commands[q] != ','.code.toByte()) q++; f[i] = String(commands, s, q - s, Charsets.US_ASCII).toInt(); q++ }
                    dots = f[0]; yDots = f[1]; w = f[2]; h = f[3]
                    data = commands.copyOfRange(q, q + w * h); p = q + w * h
                }
                for (r in 0 until h) System.arraycopy(data, r * w, redrawn, (yDots + r) * 72 + dots / 8, w)
                p += 2
            }
            assertArrayEquals("compress=$compress: the receipt came out different", page, redrawn)
            assertTrue("compress=$compress: ${commands.size} of $full", commands.size < full / (if (compress) 6 else 2))
        }
    }

    @Test
    fun `an empty receipt page sends no bitmap, so a blank page does not cost a roll of paper's worth of bytes`() {
        val blank = ByteArray(72 * 2400) { 0xFF.toByte() }

        assertEquals(0, TsplBitmap.encode(blank, 72, 2400, 0, 0, compress = true).size)
        assertEquals(emptyList<Any>(), InkBlocks.find(blank, 72, 2400))
    }
}
