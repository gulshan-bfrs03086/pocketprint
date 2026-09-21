package com.gulshan.pocketprint

import com.gulshan.pocketprint.ipp.PwgMedia
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every MediaSize id is a PWG 5101.1 self-describing name, which means the
 * dimensions are written into the id itself. PwgMedia.parse reads them back
 * without looking at the constant, so it is an independent check that the
 * number the app prints with is the number the name promises.
 *
 * This exists because LABEL_4X6 once carried 100 x 150 mm under a 4 x 6 inch
 * name, and a label printer told so fed until it faulted. The name was right
 * and the microns were wrong; nothing compared them.
 */
class MediaSizeTest {

    @Test
    fun `every built-in size is exactly what its id declares`() {
        for (size in MediaSize.ALL) {
            val declared = PwgMedia.parse(size.id)
            assertNotNull("${size.id} is not a self-describing PWG name", declared)
            assertEquals("${size.id} width", declared!!.widthMicrons, size.widthMicrons)
            assertEquals("${size.id} height", declared.heightMicrons, size.heightMicrons)
        }
    }

    @Test
    fun `ids are unique, labels are present, and byId finds each one`() {
        assertEquals(MediaSize.ALL.size, MediaSize.ALL.map { it.id }.toSet().size)
        for (size in MediaSize.ALL) {
            assertTrue(size.id, size.label.isNotBlank())
            assertEquals(size, MediaSize.byId(size.id))
        }
    }

    @Test
    fun `the industry-standard rolls are offered, at the size they are sold as`() {
        val expected = listOf(
            MediaSize.LABEL_100X100 to (100 to 100),
            MediaSize.LABEL_100X75 to (100 to 75),
            MediaSize.LABEL_100X50 to (100 to 50),
            MediaSize.LABEL_100X25 to (100 to 25),
            MediaSize.LABEL_75X50 to (75 to 50),
            MediaSize.LABEL_60X40 to (60 to 40),
            MediaSize.LABEL_50X40 to (50 to 40),
            MediaSize.LABEL_50X30 to (50 to 30),
            MediaSize.LABEL_50X25 to (50 to 25),
            MediaSize.LABEL_38X25 to (38 to 25),
            MediaSize.LABEL_25X25 to (25 to 25),
        )
        for ((size, mm) in expected) {
            val (width, height) = mm
            assertEquals("${size.id} width", width * 1_000, size.widthMicrons)
            assertEquals("${size.id} height", height * 1_000, size.heightMicrons)
            assertTrue("${size.id} is not in the picker", size in MediaSize.LABEL_ROLLS)
        }
    }

    /**
     * The trap this whole file exists for, in its least visible form. 0.8 mm
     * across and 0.4 mm down reads as rounding, and it is not: they are two
     * rolls, sold separately, and a printer told the wrong one hunts for the
     * gap in the wrong place.
     */
    @Test
    fun `50 x 25 mm and 2 x 1 in are different stock, and both are offered`() {
        assertEquals(50_000, MediaSize.LABEL_50X25.widthMicrons)
        assertEquals(25_000, MediaSize.LABEL_50X25.heightMicrons)
        assertEquals(2 * 25_400, MediaSize.LABEL_2X1.widthMicrons)
        assertEquals(1 * 25_400, MediaSize.LABEL_2X1.heightMicrons)

        assertTrue(MediaSize.LABEL_50X25.id != MediaSize.LABEL_2X1.id)
        assertTrue(MediaSize.LABEL_50X25 in MediaSize.LABEL_ROLLS)
        assertTrue(MediaSize.LABEL_2X1 in MediaSize.LABEL_ROLLS)

        // Small enough that the firmware has to draw the barcode.
        assertEquals(400, MediaSize.LABEL_50X25.dotsWide(203))
        assertEquals(200, MediaSize.LABEL_50X25.dotsHigh(203))
    }

    /**
     * Two names for one size is how a picker stops meaning anything - the user
     * picks one, it does not match the printer's stored id, and nothing says
     * why. PHOTO_4X6 and LABEL_4X6 are the one deliberate exception and live
     * in different lists, which is why this checks the roll list alone.
     */
    @Test
    fun `no two label rolls are the same size under different names`() {
        val dimensions = MediaSize.LABEL_ROLLS.map { it.widthMicrons to it.heightMicrons }
        assertEquals(dimensions.size, dimensions.toSet().size)
    }

    @Test
    fun `the label list is the rolls followed by the continuous stock`() {
        assertEquals(MediaSize.LABEL_ROLLS + MediaSize.RECEIPT_ROLLS, MediaSize.LABELS)
        assertTrue(MediaSize.RECEIPT_80 in MediaSize.RECEIPT_ROLLS)
        assertTrue(MediaSize.LABEL_ROLLS.none { it in MediaSize.RECEIPT_ROLLS })
    }

    @Test
    fun `a 4 x 6 label is four by six inches, not 100 by 150 millimetres`() {
        assertEquals(4 * 25_400, MediaSize.LABEL_4X6.widthMicrons)
        assertEquals(6 * 25_400, MediaSize.LABEL_4X6.heightMicrons)
        assertEquals("101.6 mm", "%.1f mm".format(MediaSize.LABEL_4X6.widthMm))
        // The metric stock is a different size, and is allowed to be.
        assertEquals(100_000, MediaSize.LABEL_100X150.widthMicrons)
    }

    @Test
    fun `the photo and label 4 x 6 are one physical size with two identities`() {
        assertEquals(MediaSize.PHOTO_4X6.widthMicrons, MediaSize.LABEL_4X6.widthMicrons)
        assertEquals(MediaSize.PHOTO_4X6.heightMicrons, MediaSize.LABEL_4X6.heightMicrons)
        assertTrue(MediaSize.PHOTO_4X6.id != MediaSize.LABEL_4X6.id)
        assertTrue(MediaSize.PHOTO_4X6 in MediaSize.PAPER && MediaSize.LABEL_4X6 in MediaSize.LABELS)
    }

    @Test
    fun `receipt widths are the stock, and the length is a page choice pinned to A4`() {
        assertEquals(80_000, MediaSize.RECEIPT_80.widthMicrons)
        assertEquals(58_000, MediaSize.RECEIPT_58.widthMicrons)
        // Changing this is allowed - but it changes where every receipt
        // document paginates, so it is a decision rather than a typo.
        assertEquals(MediaSize.A4.heightMicrons, MediaSize.RECEIPT_80.heightMicrons)
        assertEquals(MediaSize.A4.heightMicrons, MediaSize.RECEIPT_58.heightMicrons)
    }

    @Test
    fun `a typed-in size names itself, and the name reads back to the same size`() {
        for ((w, h) in listOf(50f to 30f, 60f to 40f, 101.6f to 152.4f, 30.3f to 20.7f)) {
            val custom = MediaSize.custom(w, h)
            assertTrue(custom.isCustom)
            val declared = PwgMedia.parse(custom.id)
            assertNotNull("${custom.id} does not parse", declared)
            assertEquals(custom.id, declared!!.widthMicrons, custom.widthMicrons)
            assertEquals(custom.id, declared.heightMicrons, custom.heightMicrons)
        }
    }

    @Test
    fun `dots follow from microns and dpi`() {
        // 4 x 6 inches at 203 dpi is 812 x 1218 dots, exactly.
        assertEquals(812, MediaSize.LABEL_4X6.dotsWide(203))
        assertEquals(1218, MediaSize.LABEL_4X6.dotsHigh(203))
        // A4 at 300 dpi.
        assertEquals(2480, MediaSize.A4.dotsWide(300))
        assertEquals(3508, MediaSize.A4.dotsHigh(300))
    }
}
