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
