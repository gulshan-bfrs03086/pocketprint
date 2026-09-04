package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.label.Zpl
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media sensing is the setting that decides whether a label printer works at
 * all. Told to look for a gap that is not there, it feeds forward hunting for
 * one and stops with a paper fault — and nothing in the protocol can tell the
 * app which mode is right, so the commands have to be exactly right for the
 * mode the user picked.
 */
class MediaControlTest {

    private val media = MediaSize.custom(50f, 30f)

    private fun tspl(stock: LabelStock): List<String> =
        String(Tspl(media, 203).setup(stock).build(), Charsets.ISO_8859_1).split("\r\n")

    private fun zpl(stock: LabelStock): String = Zpl(media, 203).start(stock).build()
        .toString(Charsets.UTF_8)

    @Test
    fun `a custom size is named by its dimensions, so entering it twice is one entry`() {
        val fifty = MediaSize.custom(50f, 30f)
        assertEquals("om_custom_50x30mm", fifty.id)
        assertEquals("50 x 30 mm", fifty.label)
        assertEquals(50_000, fifty.widthMicrons)
        assertEquals(30_000, fifty.heightMicrons)
        assertTrue(fifty.isCustom)
        assertEquals(fifty.id, MediaSize.custom(50f, 30f).id)
    }

    @Test
    fun `a fractional size keeps its fraction`() {
        assertEquals("101.6 x 152.4 mm", MediaSize.custom(101.6f, 152.4f).label)
    }

    @Test
    fun `built-in sizes are not custom`() {
        assertFalse(MediaSize.LABEL_4X6.isCustom)
    }

    @Test
    fun `TSPL gap sensing asks for the gap`() {
        val lines = tspl(LabelStock(sensing = MediaSensing.GAP, gapMm = 3f))
        assertTrue(lines.contains("SIZE 50 mm, 30 mm"))
        assertTrue(lines.contains("GAP 3 mm, 0 mm"))
    }

    @Test
    fun `TSPL black-mark sensing uses BLINE, not GAP`() {
        val lines = tspl(LabelStock(sensing = MediaSensing.BLACK_MARK, gapMm = 2.5f))
        assertTrue(lines.contains("BLINE 2.5 mm, 0 mm"))
        assertFalse(lines.any { it.startsWith("GAP ") })
    }

    @Test
    fun `TSPL continuous stock is told there is no gap to find`() {
        // The failure this prevents: the printer feeds label after label
        // looking for a gap that a continuous roll does not have.
        val lines = tspl(LabelStock(sensing = MediaSensing.CONTINUOUS, gapMm = 3f))
        assertTrue(lines.contains("GAP 0 mm, 0 mm"))
        assertFalse(lines.any { it.startsWith("BLINE") })
    }

    @Test
    fun `TSPL darkness goes out on its own scale`() {
        assertTrue(tspl(LabelStock(darkness = 12)).contains("DENSITY 12"))
        assertTrue(tspl(LabelStock(darkness = 0)).contains("DENSITY 0"))
        // Out of range is clamped rather than sent through to be rejected.
        assertTrue(tspl(LabelStock(darkness = 99)).contains("DENSITY 15"))
    }

    @Test
    fun `ZPL darkness is doubled onto its own 0 to 30 scale`() {
        // Absolute (~SD), not relative (^MD), so the same label prints the same
        // on the next unit rather than depending on that unit's dial.
        assertTrue(zpl(LabelStock(darkness = 8)).startsWith("~SD16"))
        assertTrue(zpl(LabelStock(darkness = 15)).startsWith("~SD30"))
        assertTrue(zpl(LabelStock(darkness = 0)).startsWith("~SD00"))
    }

    @Test
    fun `ZPL sensing maps to the media tracking command`() {
        assertTrue(zpl(LabelStock(sensing = MediaSensing.GAP)).contains("^MNY"))
        assertTrue(zpl(LabelStock(sensing = MediaSensing.BLACK_MARK)).contains("^MNM"))
        assertTrue(zpl(LabelStock(sensing = MediaSensing.CONTINUOUS)).contains("^MNN"))
    }

    @Test
    fun `calibration asks the printer to find whichever mark it is looking for`() {
        val gap = String(
            Tspl(media, 203).calibrate(LabelStock(sensing = MediaSensing.GAP)).build(),
            Charsets.ISO_8859_1,
        )
        assertTrue(gap.contains("GAPDETECT"))

        val mark = String(
            Tspl(media, 203).calibrate(LabelStock(sensing = MediaSensing.BLACK_MARK)).build(),
            Charsets.ISO_8859_1,
        )
        assertTrue(mark.contains("BLINEDETECT"))

        // Continuous stock has nothing to detect, so it must not be asked to.
        val continuous = String(
            Tspl(media, 203).calibrate(LabelStock(sensing = MediaSensing.CONTINUOUS)).build(),
            Charsets.ISO_8859_1,
        )
        assertFalse(continuous.contains("DETECT"))

        assertTrue(
            Zpl(media, 203).calibrate(LabelStock()).build().toString(Charsets.UTF_8)
                .contains("~JC"),
        )
    }
}
