package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertEquals
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
}
