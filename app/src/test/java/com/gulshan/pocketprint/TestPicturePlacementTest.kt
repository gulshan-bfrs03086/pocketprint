package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compressed test picture goes top right on a short label, beside the
 * heading. On a label narrower than about 400 dots that is where the heading
 * already is, and it was printed over it.
 */
class TestPicturePlacementTest {

    private val dpi = 203

    /** The heading's box on the test page: font 3 is 16 dots a character, from x = 20, y = 20, 24 high. */
    private val headingRight = 20 + Tspl.TEST_HEADING.length * 16
    private val headingBottom = 20 + 24

    private fun place(media: MediaSize) = Tspl.testPicturePlacement(media.dotsWide(dpi), media.dotsHigh(dpi))

    private fun overlapsHeading(x: Int, y: Int): Boolean {
        val right = x + Tspl.TEST_PICTURE_BYTES * 8
        val bottom = y + Tspl.TEST_PICTURE_HEIGHT
        return x < headingRight && right > 20 && y < headingBottom && bottom > 20
    }

    @Test
    fun `the narrow labels it used to land on the heading of get no picture on top of it`() {
        for (media in listOf(MediaSize.LABEL_38X25, MediaSize.LABEL_25X25, MediaSize.LABEL_50X30)) {
            val at = place(media)
            assertTrue(
                "${media.label}: picture at $at over a heading ending at x=$headingRight",
                at == null || !overlapsHeading(at.first, at.second),
            )
        }
    }

    @Test
    fun `a label with no clear spot gets none rather than a crowded one`() {
        assertNull(place(MediaSize.LABEL_38X25))
        assertNull(place(MediaSize.LABEL_25X25))
    }

    @Test
    fun `a short label wide enough has the picture top right, clear of the heading`() {
        val at = place(MediaSize.LABEL_100X25)

        assertNotNull(at)
        assertEquals(20, at!!.second)
        assertTrue(at.first >= headingRight)
        // And inside the frame the page draws 10 dots in from the edge.
        assertTrue(at.first + Tspl.TEST_PICTURE_BYTES * 8 <= MediaSize.LABEL_100X25.dotsWide(dpi) - 10)
    }

    @Test
    fun `a tall label keeps the picture under the barcode`() {
        assertEquals(20 to 255, place(MediaSize.LABEL_4X6))
        assertEquals(20 to 255, place(MediaSize.LABEL_100X100))
    }

    @Test
    fun `no label in the catalogue gets a picture over its heading`() {
        for (media in MediaSize.LABEL_ROLLS) {
            val at = place(media) ?: continue
            assertTrue("${media.label}: picture at $at", at.second > 20 || !overlapsHeading(at.first, at.second))
        }
    }

    @Test
    fun `50 x 30 is one of the labels that loses the picture, since it would sit 8 dots from the heading`() {
        assertNull(place(MediaSize.LABEL_50X30))
    }

    @Test
    fun `the picture keeps 24 dots clear of the heading, to the dot`() {
        // x = width - 96 - 20, and the heading ends at headingRight.
        val needed = headingRight + 24 + Tspl.TEST_PICTURE_BYTES * 8 + 20

        assertNull("one dot short", Tspl.testPicturePlacement(needed - 1, 240))
        assertEquals(headingRight + 24 to 20, Tspl.testPicturePlacement(needed, 240))
    }

    @Test
    fun `320 dots is the first height that is tall enough for the picture under the barcode`() {
        // Wide enough for either, so the height alone decides.
        assertEquals(20 to 255, Tspl.testPicturePlacement(800, 320))
        assertEquals(800 - Tspl.TEST_PICTURE_BYTES * 8 - 20 to 20, Tspl.testPicturePlacement(800, 319))
    }
}

/**
 * The test page is what a person reads to find out whether the guessed command
 * language is right, so what goes on it is pinned as printed: the line above the
 * picture belongs to the tall label's layout and to no other.
 */
class TsplTestPageTest {

    private val stock = com.gulshan.pocketprint.model.LabelStock()

    private fun page(media: MediaSize, compressed: Boolean = true, noRoom: () -> Unit = {}) = String(
        Tspl.testPage(media, 203, media.dotsWide(203), compressed, stock, noRoom),
        Charsets.ISO_8859_1,
    )

    @Test
    fun `a tall label captions the picture under its barcode`() {
        assertTrue(page(MediaSize.LABEL_4X6).contains("TEXT 20,225,\"2\",0,1,1,\"${Tspl.TEST_PICTURE_CAPTION}\""))
    }

    @Test
    fun `a short label has the picture beside the heading and no caption`() {
        assertFalse(page(MediaSize.LABEL_100X25).contains(Tspl.TEST_PICTURE_CAPTION))
    }

    @Test
    fun `a label with no room says so and prints no picture or caption`() {
        var told = 0
        val text = page(MediaSize.LABEL_38X25) { told++ }

        assertEquals(1, told)
        assertFalse(text.contains(Tspl.TEST_PICTURE_CAPTION))
    }

    @Test
    fun `a printer without compressed bitmaps is never asked about the picture`() {
        var told = 0
        val text = page(MediaSize.LABEL_4X6, compressed = false) { told++ }

        assertEquals(0, told)
        assertFalse(text.contains(Tspl.TEST_PICTURE_CAPTION))
    }
}
