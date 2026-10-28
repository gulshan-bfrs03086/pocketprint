package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.EscPos
import com.gulshan.pocketprint.label.MonoPage
import com.gulshan.pocketprint.render.escPosPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A receipt page is mostly white, and the bands that are entirely white are not
 * sent: the paper is fed over them instead. What matters is that nothing moves,
 * so most of these read the stream back the way a printer would, as paper, and
 * compare it with the stream that sends every band.
 */
class EscPosBlankBandsTest {

    private val bytesPerRow = 72 // 80 mm paper, 576 dots
    private val dpi = 203

    /** A page with a solid row of ink on each of the given rows. */
    private fun page(height: Int, vararg inkRows: Int): ByteArray {
        val packed = ByteArray(bytesPerRow * height)
        for (row in inkRows) for (b in 0 until bytesPerRow) packed[row * bytesPerRow + b] = 0xFF.toByte()
        return packed
    }

    private fun stream(packed: ByteArray, skipDpi: Int, band: Int = 128): ByteArray =
        EscPos(576).initialize()
            .rasterBands(packed, bytesPerRow, packed.size / bytesPerRow, band, skipDpi)
            .cut()
            .build()

    /** What a printer does with the stream, reduced to what lands on the paper. */
    private class Paper(val rows: List<List<Byte>>, val unit: Pair<Int, Int>?, val feeds: List<Int>, val cutFeed: Int, val unitCommands: Int)

    private fun read(s: ByteArray): Paper {
        val rows = ArrayList<List<Byte>>()
        val feeds = ArrayList<Int>()
        var unit: Pair<Int, Int>? = null
        var cutFeed = -1
        var unitCommands = 0
        var i = 0
        fun u(at: Int) = s[at].toInt() and 0xFF
        while (i < s.size) {
            val b = u(i)
            when {
                b == 0x1B && u(i + 1) == 0x40 -> i += 2
                b == 0x1D && u(i + 1) == 0x50 -> { unit = u(i + 2) to u(i + 3); unitCommands++; i += 4 }
                b == 0x1B && u(i + 1) == 0x4A -> {
                    feeds += u(i + 2)
                    repeat(u(i + 2)) { rows += List(bytesPerRow) { 0.toByte() } }
                    i += 3
                }
                b == 0x1B && u(i + 1) == 0x64 -> { cutFeed = u(i + 2); i += 3 }
                b == 0x1D && u(i + 1) == 0x56 -> i += 4
                b == 0x1D && u(i + 1) == 0x76 -> {
                    val w = u(i + 4) or (u(i + 5) shl 8)
                    val h = u(i + 6) or (u(i + 7) shl 8)
                    assertEquals(bytesPerRow, w)
                    for (r in 0 until h) rows += s.slice(i + 8 + r * w until i + 8 + (r + 1) * w)
                    i += 8 + w * h
                }
                else -> throw AssertionError("unexpected byte $b at $i")
            }
        }
        return Paper(rows, unit, feeds, cutFeed, unitCommands)
    }

    private fun assertSamePaper(packed: ByteArray, band: Int = 128) {
        val old = read(stream(packed, 0, band))
        val new = read(stream(packed, dpi, band))
        assertEquals("the same length of paper", old.rows.size, new.rows.size)
        assertEquals("every row where it was", old.rows, new.rows)
        assertEquals("the feed before the cut", old.cutFeed, new.cutFeed)
        assertEquals(packed.size / bytesPerRow, new.rows.size)
    }

    // -- what is sent --------------------------------------------------------

    @Test
    fun `an all-blank page sends no image at all, only the feed`() {
        val s = stream(page(600), dpi)

        assertFalse("no GS v 0", s.toList().windowed(3).any { it == listOf<Byte>(0x1D, 0x76, 0x30) })
        assertEquals(listOf(255, 255, 90), read(s).feeds)
        assertTrue("a few bytes, not 43 KB", s.size < 30)
        assertSamePaper(page(600))
    }

    @Test
    fun `ink at the top keeps its band and feeds the blank rest as one run`() {
        val packed = page(600, 3)
        val p = read(stream(packed, dpi))

        assertEquals(listOf(255, 217), p.feeds) // 472 rows, in two
        assertEquals(472, p.feeds.sum())
        assertSamePaper(packed)
    }

    @Test
    fun `ink in the middle is fed to and sent where it was`() {
        val packed = page(640, 300)
        val p = read(stream(packed, dpi))

        // Two blank bands, the inked one, two more: each side of it is its own feed.
        assertEquals(listOf(255, 1, 255, 1), p.feeds)
        assertEquals(listOf<Byte>(-1), p.rows[300].distinct())
        assertSamePaper(packed)
    }

    @Test
    fun `ink at the bottom is preceded by the whole blank page as one feed`() {
        val packed = page(512, 511)

        assertEquals(384, read(stream(packed, dpi)).feeds.sum())
        assertSamePaper(packed)
    }

    @Test
    fun `a single inked row sends one band, however tall the page`() {
        val packed = page(1280, 640)
        val s = stream(packed, dpi)
        val bands = s.toList().windowed(4).count { it == listOf<Byte>(0x1D, 0x76, 0x30, 0x00) }

        assertEquals(1, bands)
        assertSamePaper(packed)
    }

    @Test
    fun `a page that is not a whole number of bands ends on its short band`() {
        // 300 rows is 128 + 128 + 44. Ink in the short one, then not.
        assertSamePaper(page(300, 299))
        assertSamePaper(page(300, 0))
        assertSamePaper(page(300))
        assertSamePaper(page(129, 128))
        assertSamePaper(page(1, 0))
        assertSamePaper(page(1))
    }

    @Test
    fun `a blank tail is fed before the cut, not dropped`() {
        val packed = page(300, 5)
        val p = read(stream(packed, dpi))

        assertEquals(300, p.rows.size)
        assertEquals(172, p.feeds.sum())
        assertEquals(4, p.cutFeed)
    }

    @Test
    fun `a blank run over 255 dots is split, and no feed is empty or over a byte`() {
        val packed = page(2000, 1999)
        val p = read(stream(packed, dpi))

        assertEquals(1920, p.feeds.sum()) // 15 blank bands
        assertTrue(p.feeds.all { it in 1..255 })
        assertEquals(listOf(255, 255, 255, 255, 255, 255, 255, 135), p.feeds)
        assertSamePaper(packed)
    }

    @Test
    fun `a run of exactly 255 is one feed with nothing after it`() {
        val packed = page(255 + 1, 255)

        assertEquals(listOf(255), read(stream(packed, dpi, band = 255)).feeds)
        assertSamePaper(packed, band = 255)
    }

    @Test
    fun `blank bands separated by ink are fed separately, not merged across it`() {
        val packed = page(640, 0, 639)
        val p = read(stream(packed, dpi))

        // Bands 1..3 are blank between the two inked ones: 384 dots, one run.
        assertEquals(listOf(255, 129), p.feeds)
        assertSamePaper(packed)
    }

    @Test
    fun `one dot of ink anywhere in a band keeps the band`() {
        // The first and the last byte of a band, and the last byte of the page.
        for (at in listOf(0, 128 * bytesPerRow - 1, 128 * bytesPerRow, 256 * bytesPerRow - 1)) {
            val packed = ByteArray(bytesPerRow * 256)
            packed[at] = 1
            assertSamePaper(packed)
            assertTrue("byte $at is sent", read(stream(packed, dpi)).feeds.sum() == 128)
        }
    }

    @Test
    fun `an odd band height still lands every row`() {
        assertSamePaper(page(1000, 10, 500, 999), band = 37)
    }

    // -- the unit ESC J counts in --------------------------------------------

    @Test
    fun `the motion unit is pinned to the head resolution before the first feed`() {
        val s = stream(page(600, 3), dpi)

        val gsP = s.toList().windowed(4).indexOf(listOf<Byte>(0x1D, 0x50, dpi.toByte(), dpi.toByte()))
        val escJ = s.toList().windowed(2).indexOf(listOf<Byte>(0x1B, 0x4A))
        assertTrue("GS P is there", gsP >= 0)
        assertTrue("and first", gsP < escJ)
        assertEquals(dpi to dpi, read(s).unit)
    }

    @Test
    fun `the motion unit is set once however many runs are fed`() {
        // Four feeds in this page. A clone that prints GS P's parameters as text
        // would print them once per run if it were sent before each.
        val p = read(stream(page(640, 300), dpi))

        assertEquals(4, p.feeds.size)
        assertEquals(1, p.unitCommands)
    }

    @Test
    fun `a page with no blank band does not touch the motion unit`() {
        val s = stream(ByteArray(bytesPerRow * 256) { 1 }, dpi)

        assertEquals(null, read(s).unit)
        assertTrue(read(s).feeds.isEmpty())
    }

    @Test
    fun `the stream sets no line spacing for GS P to rescale`() {
        val s = stream(page(600, 3), dpi).toList()

        assertFalse("no ESC 3", s.windowed(2).any { it == listOf<Byte>(0x1B, 0x33) })
        assertEquals("the cut feed is still ESC d", 4, read(stream(page(600, 3), dpi)).cutFeed)
    }

    // -- off, and out of range ----------------------------------------------

    @Test
    fun `without a resolution every band is sent, as before`() {
        val packed = page(600)
        val s = stream(packed, 0)

        assertTrue(read(s).feeds.isEmpty())
        assertEquals(null, read(s).unit)
        assertTrue("43 KB of nothing", s.size > 43_000)
    }

    @Test
    fun `a resolution that does not fit GS P skips nothing`() {
        assertArrayEquals(stream(page(600, 3), 0), stream(page(600, 3), 300))
        assertArrayEquals(stream(page(600, 3), 0), stream(page(600, 3), -1))
    }

    // -- what it saves ------------------------------------------------------

    @Test
    fun `a typical receipt is a fraction of the bytes`() {
        // 80 mm, 2400 rows: a header, an item block, a total and a barcode, all
        // ink in the top third and a short block near the foot.
        val ink = (40..90) + (200..260) + (330..360) + (2100..2150)
        val packed = page(2400, *ink.toIntArray())
        val old = stream(packed, 0).size
        val new = stream(packed, dpi).size

        println("receipt 2400 rows: $old -> $new bytes")
        assertTrue("$old -> $new", new < old / 2)
        assertSamePaper(packed)
    }

    // -- the paths a receipt actually takes ---------------------------------
    //
    // Everything above calls rasterBands. A receipt goes through image(), from
    // the page the pipeline hands over, and a dpi dropped on either step turned
    // the whole feature off with every test above still green.

    private fun monoPage(packed: ByteArray) = MonoPage(packed, bytesPerRow * 8, packed.size / bytesPerRow)

    @Test
    fun `a page handed to image skips its blank bands at the resolution it is given`() {
        val packed = page(600, 3)

        val skipped = read(EscPos(576).initialize().image(monoPage(packed), skipBlankDpi = dpi).cut().build())
        val whole = read(EscPos(576).initialize().image(monoPage(packed)).cut().build())

        assertEquals(listOf(255, 217), skipped.feeds)
        assertEquals(dpi to dpi, skipped.unit)
        assertTrue("asking for no skipping still sends every band", whole.feeds.isEmpty())
        assertEquals(600, whole.rows.size)
    }

    @Test
    fun `a receipt page leaves the pipeline with its blank bands fed and not sent`() {
        val packed = page(600, 3)
        val s = escPosPage(576, dpi, monoPage(packed))
        val paper = read(s)

        assertEquals("the blank rest of the page is fed over", listOf(255, 217), paper.feeds)
        assertTrue("sent whole it would be 43 KB: ${s.size}", s.size < 10_000)
        assertEquals("every row where it was", read(stream(packed, 0)).rows, paper.rows)
        assertEquals(4, paper.cutFeed)
    }

    @Test
    fun `the head's own resolution is what the receipt pins the motion unit to`() {
        // Not a constant: a 180 dpi head must not be left at 203.
        assertEquals(180 to 180, read(escPosPage(576, 180, monoPage(page(600, 3)))).unit)
    }
}
