package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.model.labelSizesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Labels screen used to offer the fixed catalogue and nothing else, so a
 * size typed into printer settings could be configured for a printer and then
 * not chosen for a label. These pin the row that screen is built from.
 */
class LabelSizesTest {

    private fun printer(vararg sizes: MediaSize) = Printer(
        id = "p",
        displayName = "TE244",
        address = PrinterAddress.Raw("192.168.1.9", 9100),
        capabilities = PrinterCapabilities(
            languages = listOf(PrintLanguage.TSPL),
            mediaSizes = sizes.toList(),
        ),
    )

    @Test
    fun `with no printer selected the row is the catalogue`() {
        assertEquals(MediaSize.LABELS, labelSizesFor(null))
    }

    @Test
    fun `a size typed into printer settings is offered, ahead of the catalogue`() {
        val custom = MediaSize.custom(60f, 35f)

        val offered = labelSizesFor(printer(MediaSize.LABEL_4X6, custom))

        assertEquals(custom, offered.first())
        assertEquals(MediaSize.LABELS, offered.drop(1))
    }

    @Test
    fun `a printer with no custom sizes adds nothing to the catalogue`() {
        val offered = labelSizesFor(printer(MediaSize.LABEL_4X6, MediaSize.LABEL_100X150))

        assertEquals(MediaSize.LABELS, offered)
    }

    @Test
    fun `paper sizes in the printer's list are not offered as label stock`() {
        // A printer can be told to default to A4, which puts it in mediaSizes.
        val offered = labelSizesFor(printer(MediaSize.A4, MediaSize.LETTER, MediaSize.PHOTO_4X6))

        assertFalse(MediaSize.A4 in offered)
        assertFalse(MediaSize.LETTER in offered)
        assertEquals(MediaSize.LABELS, offered)
    }

    @Test
    fun `a custom size that is a catalogue size is not offered twice`() {
        // The only way to print a 50 x 25 roll before it was in the catalogue.
        val typedIn = MediaSize.custom(50f, 25f)
        // 2 x 1 in, typed as its millimetres.
        val typedInAsInches = MediaSize.custom(50.8f, 25.4f)

        val offered = labelSizesFor(printer(typedIn, typedInAsInches))

        assertEquals(MediaSize.LABELS, offered)
        assertEquals(1, offered.count { it.widthMicrons == 50_000 && it.heightMicrons == 25_000 })
    }

    @Test
    fun `a custom size listed twice is offered once`() {
        val custom = MediaSize.custom(60f, 35f)

        val offered = labelSizesFor(printer(custom, custom))

        assertEquals(1, offered.count { it.id == custom.id })
    }

    @Test
    fun `several custom sizes all come through, in the order they were added`() {
        val a = MediaSize.custom(60f, 35f)
        val b = MediaSize.custom(45f, 20f)

        val offered = labelSizesFor(printer(a, b))

        assertEquals(listOf(a, b), offered.take(2))
    }

    /**
     * Picking a printer with no custom sizes drops another printer's from the
     * row. The selected size is still what would print, so it has to stay in
     * the row and stay selected - not vanish and leave a label about to be
     * printed at a size nothing on screen mentions.
     */
    @Test
    fun `the selected size is kept when the printer no longer offers it`() {
        val fromAnotherPrinter = MediaSize.custom(60f, 35f)

        val offered = labelSizesFor(printer(MediaSize.LABEL_4X6), selected = fromAnotherPrinter)

        assertTrue(fromAnotherPrinter in offered)
        assertEquals(MediaSize.LABELS + fromAnotherPrinter, offered)
    }

    @Test
    fun `a selection that is already offered is not added again`() {
        val custom = MediaSize.custom(60f, 35f)

        val offered = labelSizesFor(printer(custom), selected = custom)

        assertEquals(1, offered.count { it.id == custom.id })
        assertEquals(1 + MediaSize.LABELS.size, offered.size)
    }

    @Test
    fun `a catalogue selection changes nothing`() {
        assertEquals(
            MediaSize.LABELS,
            labelSizesFor(null, selected = MediaSize.LABEL_50X25),
        )
    }
}
