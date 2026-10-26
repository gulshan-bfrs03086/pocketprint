package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.InkBlock
import com.gulshan.pocketprint.label.InkBlocks
import com.gulshan.pocketprint.label.TsplBitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A label page is mostly white, and on an RE310 over Bluetooth every byte costs
 * about 5 ms: measured, the printer took in ~190 bytes a second whatever they
 * were. The full 75 x 75 mm page is 45,072 bytes - four minutes per label - and
 * the app said "sent" after one second, so labels came out minutes late and
 * behind every other one that had been pressed meanwhile.
 *
 * These pin the two promises that make sending less safe: nothing is lost, and
 * it is never *more* than before.
 */
class TsplBitmapTest {

    private val white = 0xFF.toByte()

    // -- pages ---------------------------------------------------------------

    private class Page(val widthBytes: Int, val height: Int) {
        val bytes = ByteArray(widthBytes * height) { 0xFF.toByte() }

        /** Ink in a rectangle, as bars: every other byte column is black, the rest a dotted byte. */
        fun ink(xByte: Int, y: Int, w: Int, h: Int, pattern: Int = 0x55): Page {
            for (row in y until y + h) for (col in xByte until xByte + w) {
                bytes[row * widthBytes + col] = (if ((col - xByte) % 2 == 0) 0x00 else pattern).toByte()
            }
            return this
        }
    }

    /**
     * What a printer draws from the commands: a white label, with each BITMAP
     * written at its x,y in dots. Written to know nothing about how the commands
     * were made - it parses the text, reads the binary, and checks the CR LF.
     */
    private fun redraw(commands: ByteArray, widthBytes: Int, height: Int, x: Int, y: Int): ByteArray {
        val canvas = ByteArray(widthBytes * height) { white }
        var p = 0
        while (p < commands.size) {
            val xDots: Int
            val yDots: Int
            val wBytes: Int
            val rows: Int
            val data: ByteArray
            if (commands[p] == '!'.code.toByte()) {
                // TSC's compressed bitmap: "!S", four 16-bit little-endian
                // fields, padding to 16 bytes, LZSS data, CR LF.
                assertEquals('S'.code.toByte(), commands[p + 1])
                fun le(at: Int) = (commands[p + at].toInt() and 0xFF) or ((commands[p + at + 1].toInt() and 0xFF) shl 8)
                xDots = le(2); yDots = le(4); wBytes = le(6); rows = le(8)
                for (pad in 10 until 16) assertEquals("header padding", 0, commands[p + pad].toInt())
                val decoded = LzssDecoder.decode(commands, p + 16, wBytes * rows)
                data = decoded.bytes
                p = decoded.end
            } else {
                val prefix = "BITMAP "
                assertEquals(prefix, String(commands, p, prefix.length, Charsets.US_ASCII))
                p += prefix.length
                val fields = IntArray(5)
                for (f in 0 until 5) {
                    val start = p
                    while (commands[p] != ','.code.toByte()) p++
                    fields[f] = String(commands, start, p - start, Charsets.US_ASCII).toInt()
                    p++
                }
                val (xd, yd, wb, rs, mode) = fields
                assertEquals("mode 0 overwrites", 0, mode)
                xDots = xd; yDots = yd; wBytes = wb; rows = rs
                data = commands.copyOfRange(p, p + wb * rs)
                p += wb * rs
            }
            assertEquals("a block must start on a byte boundary of the page", 0, (xDots - x) % 8)
            val xByte = (xDots - x) / 8
            for (r in 0 until rows) {
                System.arraycopy(data, r * wBytes, canvas, (yDots - y + r) * widthBytes + xByte, wBytes)
            }
            assertEquals('\r'.code.toByte(), commands[p])
            assertEquals('\n'.code.toByte(), commands[p + 1])
            p += 2
        }
        return canvas
    }

    private fun redrawOf(page: Page, x: Int = 0, y: Int = 0, compress: Boolean = false) =
        redraw(
            TsplBitmap.encode(page.bytes, page.widthBytes, page.height, x, y, compress),
            page.widthBytes, page.height, x, y,
        )

    // -- nothing is lost -------------------------------------------------------

    @Test
    fun `what the printer draws is exactly the page that went in, on pages of every shape`() {
        // The property the whole change rests on. Random rectangles of ink on
        // random page sizes, including one byte wide and one row high, and a
        // width that is not a multiple of anything convenient.
        val random = Random(20261026)
        repeat(400) { round ->
            val widthBytes = random.nextInt(1, 81)
            val height = random.nextInt(1, 130)
            val page = Page(widthBytes, height)
            repeat(random.nextInt(0, 9)) {
                val w = random.nextInt(1, widthBytes + 1)
                val h = random.nextInt(1, height + 1)
                page.ink(
                    random.nextInt(0, widthBytes - w + 1), random.nextInt(0, height - h + 1), w, h,
                    pattern = random.nextInt(0, 255),
                )
            }
            assertArrayEquals(
                "page $round (${widthBytes}x$height) was altered",
                page.bytes, redrawOf(page),
            )
        }
    }

    @Test
    fun `the page's own offset on the label is added to every block`() {
        // x is in dots and a block starts on a byte column, so a block at byte
        // column 3 of a page placed at x=16 starts at dot 40.
        val page = Page(20, 60).ink(3, 10, 4, 5).ink(12, 40, 3, 6)

        val commands = TsplBitmap.encode(page.bytes, 20, 60, x = 16, y = 100)

        assertArrayEquals(page.bytes, redraw(commands, 20, 60, 16, 100))
        val text = String(commands, Charsets.ISO_8859_1)
        assertTrue("block at byte 3 should start at dot 40, row 110: $text", "BITMAP 40,110," in text)
        assertTrue("block at byte 12 should start at dot 112, row 140", "BITMAP 112,140," in text)
    }

    @Test
    fun `a blank page sends no bitmap at all`() {
        val page = Page(75, 599)

        assertEquals(0, TsplBitmap.encode(page.bytes, 75, 599, 0, 0).size)
        assertEquals(emptyList<InkBlock>(), InkBlocks.find(page.bytes, 75, 599))
    }

    // -- it is never more than before --------------------------------------------

    @Test
    fun `a page with ink everywhere is one block, byte for byte what was sent before`() {
        // A photo has no blank gap to cut at. It must cost what it always did.
        val page = Page(75, 599).ink(0, 0, 75, 599)

        val blocks = InkBlocks.find(page.bytes, 75, 599)

        assertEquals(listOf(InkBlock(0, 0, 75, 599)), blocks)
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0)
        assertEquals(75 * 599 + "BITMAP 0,0,75,599,0,".length + 2, commands.size)
    }

    @Test
    fun `it is never more than one box around everything`() {
        // A cut adds a command, so it has to take out more than that command
        // costs. Held across random pages, against the model the cut uses.
        val random = Random(77)
        repeat(300) {
            val widthBytes = random.nextInt(1, 81)
            val height = random.nextInt(1, 130)
            val page = Page(widthBytes, height)
            repeat(random.nextInt(1, 9)) {
                val w = random.nextInt(1, widthBytes + 1)
                val h = random.nextInt(1, height + 1)
                page.ink(random.nextInt(0, widthBytes - w + 1), random.nextInt(0, height - h + 1), w, h)
            }
            val blocks = InkBlocks.find(page.bytes, widthBytes, height)
            val whole = tightBox(page)

            val cost = blocks.sumOf { it.widthBytes * it.height + InkBlocks.COMMAND_COST }
            val single = whole.widthBytes * whole.height + InkBlocks.COMMAND_COST
            assertTrue("$cost > $single for ${blocks.size} blocks on ${widthBytes}x$height", cost <= single)
        }
    }

    @Test
    fun `a gap too small to pay for another command is left in`() {
        // Two rows of nothing across five bytes is ten bytes. A second BITMAP
        // header costs more than that, so cutting there would send more.
        val page = Page(5, 40).ink(0, 5, 5, 6).ink(0, 13, 5, 6)

        val blocks = InkBlocks.find(page.bytes, 5, 40)

        assertEquals(1, blocks.size)
        assertEquals(InkBlock(0, 5, 5, 14), blocks[0])
    }

    @Test
    fun `a gap wide enough to pay for itself is cut out`() {
        val page = Page(75, 100).ink(0, 5, 75, 6).ink(0, 40, 75, 6)

        val blocks = InkBlocks.find(page.bytes, 75, 100)

        assertEquals(listOf(InkBlock(0, 5, 75, 6), InkBlock(0, 40, 75, 6)), blocks)
    }

    // -- the labels this was written for ---------------------------------------

    @Test
    fun `a bin barcode label is a small fraction of the full page`() {
        // 75 x 75 mm at 203 dpi: 75 bytes x 599 rows. A barcode mid-label with
        // its number underneath, as the WMS bin label is.
        val page = Page(75, 599).ink(16, 230, 44, 115).ink(27, 345, 22, 18)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 15 / 100)
    }

    @Test
    fun `a card with a border, a barcode and text lines is cut into its parts`() {
        // The WMS gate pass: a dotted border down both edges, a header line, a
        // barcode with its number, and rows of label and value, with the lower
        // part of the label empty. The border alone spans the whole width, so
        // a single box around the ink would still be most of the page.
        val page = Page(75, 599)
            .ink(3, 20, 1, 350).ink(71, 20, 1, 350)
            .ink(9, 65, 28, 25)
            .ink(9, 125, 56, 90)
            .ink(19, 232, 36, 25)
            .ink(9, 296, 12, 18).ink(38, 296, 27, 22)
            .ink(9, 345, 12, 18).ink(55, 345, 10, 22)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 30 / 100)
        assertEquals(
            setOf(
                InkBlock(3, 20, 1, 350), InkBlock(71, 20, 1, 350), InkBlock(9, 65, 28, 25),
                InkBlock(9, 125, 56, 90), InkBlock(19, 232, 36, 25), InkBlock(9, 296, 12, 18),
                InkBlock(38, 296, 27, 22), InkBlock(9, 345, 12, 18), InkBlock(55, 345, 10, 22),
            ),
            InkBlocks.find(page.bytes, 75, 599).toSet(),
        )
    }

    // -- the command stream stays sane ------------------------------------------

    @Test
    fun `blocks stay inside the page and never overlap`() {
        val random = Random(5)
        repeat(200) {
            val widthBytes = random.nextInt(2, 81)
            val height = random.nextInt(2, 130)
            val page = Page(widthBytes, height)
            repeat(random.nextInt(1, 9)) {
                val w = random.nextInt(1, widthBytes + 1)
                val h = random.nextInt(1, height + 1)
                page.ink(random.nextInt(0, widthBytes - w + 1), random.nextInt(0, height - h + 1), w, h)
            }
            val blocks = InkBlocks.find(page.bytes, widthBytes, height)

            for (b in blocks) {
                assertTrue("outside the page: $b", b.xByte >= 0 && b.y >= 0 &&
                    b.xByte + b.widthBytes <= widthBytes && b.y + b.height <= height)
                assertTrue("empty block: $b", b.widthBytes > 0 && b.height > 0)
            }
            for (i in blocks.indices) for (j in i + 1 until blocks.size) {
                val a = blocks[i]
                val b = blocks[j]
                val apart = a.xByte + a.widthBytes <= b.xByte || b.xByte + b.widthBytes <= a.xByte ||
                    a.y + a.height <= b.y || b.y + b.height <= a.y
                assertTrue("overlap: $a and $b", apart)
            }
        }
    }

    @Test
    fun `a page of fine speckle does not become a hundred commands`() {
        // Isolated single bytes with blank space between: every one is a "gap"
        // at the smallest threshold, and each cut would add a header.
        val page = Page(75, 599)
        for (row in 0 until 599 step 4) for (col in 0 until 75 step 4) {
            page.bytes[row * 75 + col] = 0x00
        }

        val blocks = InkBlocks.find(page.bytes, 75, 599)

        assertTrue("${blocks.size} blocks", blocks.size <= InkBlocks.MAX_BLOCKS)
        assertArrayEquals(page.bytes, redrawOf(page))
    }

    // -- frames ---------------------------------------------------------------------

    /** A closed border: rules along all four edges of the page. */
    private fun Page.frame(thickRows: Int, thickBytes: Int): Page {
        ink(0, 0, widthBytes, thickRows)
        ink(0, height - thickRows, widthBytes, thickRows)
        ink(0, 0, thickBytes, height)
        ink(widthBytes - thickBytes, 0, thickBytes, height)
        return this
    }

    @Test
    fun `a closed frame round a barcode no longer sends the whole page`() {
        // The case blank-gap cutting alone could not do anything with: a rule
        // across the top joined to one down the side is ink in every row and
        // every column, so there is no gap to cut at. Measured before peeling:
        // this page was one block, 100 % of the full bitmap.
        val page = Page(75, 599).ink(16, 230, 44, 115).ink(27, 345, 22, 18).frame(3, 1)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 20 / 100)
    }

    @Test
    fun `a frame round a card of text lines and a barcode leaves the card cut into its parts`() {
        val page = Page(75, 599)
            .ink(9, 65, 28, 25).ink(9, 125, 56, 90).ink(19, 232, 36, 25)
            .ink(9, 296, 12, 18).ink(38, 296, 27, 22)
            .frame(2, 1)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 30 / 100)
    }

    @Test
    fun `a frame inside a frame is peeled twice`() {
        val page = Page(75, 599).ink(20, 250, 30, 60).frame(2, 1)
        // An inner border, well inside the outer one.
        page.ink(8, 40, 59, 2).ink(8, 520, 59, 2).ink(8, 40, 1, 482).ink(66, 40, 1, 482)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 25 / 100)
    }

    @Test
    fun `a frame round dense ink is left as one block, because peeling it saves nothing`() {
        // Every row here is a "rule", so the peel finds strips - and then finds
        // an interior with nothing to cut, and the strips are extra commands.
        // It has to refuse: never more than one box.
        val page = Page(75, 100).ink(0, 0, 75, 100)

        assertEquals(listOf(InkBlock(0, 0, 75, 100)), InkBlocks.find(page.bytes, 75, 100))
    }

    @Test
    fun `pages with frames of every thickness are redrawn exactly, with no block overlapping another`() {
        val random = Random(31)
        repeat(300) { round ->
            val widthBytes = random.nextInt(3, 81)
            val height = random.nextInt(3, 130)
            val page = Page(widthBytes, height)
            repeat(random.nextInt(0, 6)) {
                val w = random.nextInt(1, widthBytes + 1)
                val h = random.nextInt(1, height + 1)
                page.ink(random.nextInt(0, widthBytes - w + 1), random.nextInt(0, height - h + 1), w, h, pattern = random.nextInt(0, 255))
            }
            if (random.nextBoolean()) page.frame(random.nextInt(1, minOf(5, height / 2 + 1)), random.nextInt(1, minOf(3, widthBytes / 2 + 1)))

            assertArrayEquals("page $round (${widthBytes}x$height)", page.bytes, redrawOf(page))
            assertArrayEquals("page $round compressed", page.bytes, redrawOf(page, compress = true))

            val blocks = InkBlocks.find(page.bytes, widthBytes, height)
            for (i in blocks.indices) for (j in i + 1 until blocks.size) {
                val a = blocks[i]; val b = blocks[j]
                val apart = a.xByte + a.widthBytes <= b.xByte || b.xByte + b.widthBytes <= a.xByte ||
                    a.y + a.height <= b.y || b.y + b.height <= a.y
                assertTrue("page $round: overlap $a and $b", apart)
            }
        }
    }

    // -- the edges of a box -------------------------------------------------------

    @Test
    fun `ink on the last row and last column of a box is not lost`() {
        // An underline joining two columns, and a rule down the right-hand side:
        // the ink that sits on the final row or column of the region being cut.
        // A scan that stops one short drops it, and a random page rarely lands
        // on it, so these are fixed.
        val underline = Page(48, 40).ink(0, 0, 4, 30).ink(41, 0, 4, 30).ink(0, 30, 45, 1)
        val rule = Page(40, 48).ink(0, 0, 30, 4).ink(0, 41, 30, 4).ink(30, 0, 1, 45)

        assertArrayEquals(underline.bytes, redrawOf(underline))
        assertArrayEquals(rule.bytes, redrawOf(rule))
    }

    // -- the block limit ------------------------------------------------------------

    @Test
    fun `too many blocks at the smallest gap is retried with a wider one, not given up on`() {
        // Nine clusters, each a stack of eight 16-byte-wide, 4-row stripes with 2
        // blank rows between. A 2-row gap across 16 bytes is 32 bytes - more than
        // a command costs - so at the smallest gap every stripe is its own block:
        // 72 in all, over the limit. The next rung ignores gaps of 2 and 3, so
        // each cluster stays whole and the page is nine blocks, not one box
        // around everything.
        val page = Page(75, 599)
        for (cx in intArrayOf(0, 28, 56)) for (cy in intArrayOf(0, 60, 120)) {
            for (stripe in 0 until 8) page.ink(cx, cy + stripe * 6, 16, 4)
        }

        val blocks = InkBlocks.find(page.bytes, 75, 599)

        assertEquals(9, blocks.size)
        assertTrue("every block is a whole cluster: $blocks", blocks.all { it.widthBytes == 16 && it.height == 46 })
        assertArrayEquals(page.bytes, redrawOf(page))
    }

    @Test
    fun `a page that needs more blocks than allowed even at the widest gap is one box`() {
        // 3 x 3 byte blobs on a 20-row, 20-byte grid: 120 of them, each separated
        // from the next by 17 blank rows or bytes - wider than any rung of the
        // ladder - and wide enough that every cut between them pays for its
        // command. (One byte wide they would not: a cut inside a 1-byte strip
        // saves under a command's cost, so the page would stay as a few strips.)
        val page = Page(75, 599)
        for (row in 0 until 580 step 20) for (col in 0 until 64 step 20) page.ink(col, row, 3, 3)

        val blocks = InkBlocks.find(page.bytes, 75, 599)

        assertEquals(listOf(tightBox(page)), blocks)
        assertArrayEquals(page.bytes, redrawOf(page))
    }

    // -- compressed ---------------------------------------------------------------

    @Test
    fun `compressed, what the printer draws is still exactly the page that went in`() {
        // The same property as above with the compressed command switched on:
        // random pages of every shape, redrawn by a decoder that knows nothing
        // about the encoder.
        val random = Random(20261027)
        repeat(300) { round ->
            val widthBytes = random.nextInt(1, 81)
            val height = random.nextInt(1, 130)
            val page = Page(widthBytes, height)
            repeat(random.nextInt(0, 9)) {
                val w = random.nextInt(1, widthBytes + 1)
                val h = random.nextInt(1, height + 1)
                page.ink(
                    random.nextInt(0, widthBytes - w + 1), random.nextInt(0, height - h + 1), w, h,
                    pattern = random.nextInt(0, 255),
                )
            }
            assertArrayEquals(
                "page $round (${widthBytes}x$height) was altered",
                page.bytes, redrawOf(page, compress = true),
            )
        }
    }

    @Test
    fun `the compressed command is laid out as the official utility sends it`() {
        // "!S", then x, y, width in bytes and height as 16-bit little-endian,
        // zero-padded to 16 bytes. x and y here are above 255, so a byte-order
        // slip shows.
        val page = Page(60, 400).ink(40, 300, 6, 20)   // x = 40 bytes = 320 dots; y = 300

        val commands = TsplBitmap.encode(page.bytes, 60, 400, x = 0, y = 0, compress = true)

        assertEquals('!'.code.toByte(), commands[0])
        assertEquals('S'.code.toByte(), commands[1])
        fun le(at: Int) = (commands[at].toInt() and 0xFF) or ((commands[at + 1].toInt() and 0xFF) shl 8)
        assertEquals("x in dots", 320, le(2))
        assertEquals("y", 300, le(4))
        assertEquals("width in bytes", 6, le(6))
        assertEquals("height", 20, le(8))
        for (pad in 10 until 16) assertEquals(0, commands[pad].toInt())
        assertEquals('\r'.code.toByte(), commands[commands.size - 2])
        assertEquals('\n'.code.toByte(), commands[commands.size - 1])
    }

    @Test
    fun `a block that does not shrink is sent as plain BITMAP even with compression on`() {
        // Noise cannot be compressed, and the compressed form would be bigger.
        // The plain command is understood by every TSPL printer, so it is the
        // one to fall back to - and a printer in a mixed stream decodes both.
        val random = Random(8)
        val page = Page(40, 60)
        for (i in page.bytes.indices) page.bytes[i] = random.nextInt(256).toByte()

        val commands = TsplBitmap.encode(page.bytes, 40, 60, 0, 0, compress = true)

        assertTrue(String(commands, 0, 7, Charsets.US_ASCII) == "BITMAP ")
        assertArrayEquals(page.bytes, redraw(commands, 40, 60, 0, 0))
    }

    @Test
    fun `compression off sends no compressed command at all`() {
        // The switch must mean something: a printer that does not understand the
        // command reads it as noise.
        val page = Page(75, 599).ink(16, 230, 44, 115)

        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0, compress = false)

        assertEquals("BITMAP ", String(commands, 0, 7, Charsets.US_ASCII))
        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
    }

    @Test
    fun `compressed, a bin label is a few percent of the full page`() {
        val page = Page(75, 599).ink(16, 230, 44, 115).ink(27, 345, 22, 18)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0, compress = true)
        val plain = TsplBitmap.encode(page.bytes, 75, 599, 0, 0, compress = false)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 8 / 100)
        assertTrue("compressed ${commands.size} should beat plain ${plain.size}", commands.size < plain.size)
    }

    @Test
    fun `compressed, a card with a closed frame is no longer the whole page`() {
        // The case blank-gap cutting cannot help with: a frame round the whole
        // card leaves no gap to cut at. Compression does not need one.
        val page = Page(75, 599)
            .ink(0, 0, 75, 2).ink(0, 597, 75, 2).ink(0, 0, 1, 599).ink(74, 0, 1, 599)
            .ink(9, 65, 28, 25).ink(9, 125, 56, 90).ink(19, 232, 36, 25)

        val full = 75 * 599
        val commands = TsplBitmap.encode(page.bytes, 75, 599, 0, 0, compress = true)

        assertArrayEquals(page.bytes, redraw(commands, 75, 599, 0, 0))
        assertTrue("${commands.size} of $full", commands.size < full * 35 / 100)
    }

    // -- helpers ----------------------------------------------------------------

    private fun tightBox(page: Page): InkBlock {
        var top = Int.MAX_VALUE; var bottom = -1; var left = Int.MAX_VALUE; var right = -1
        for (y in 0 until page.height) for (x in 0 until page.widthBytes) {
            if (page.bytes[y * page.widthBytes + x] != white) {
                top = minOf(top, y); bottom = maxOf(bottom, y)
                left = minOf(left, x); right = maxOf(right, x)
            }
        }
        return InkBlock(left, top, right - left + 1, bottom - top + 1)
    }
}
