package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.model.defaultLabelSize
import com.gulshan.pocketprint.model.labelSizesFor
import com.gulshan.pocketprint.model.sizeWhenPrinterSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // -- what a printer is loaded with -------------------------------------

    @Test
    fun `a printer set up for 4 x 6 defaults to 4 x 6`() {
        val loaded = printer(MediaSize.LABEL_4X6, MediaSize.LABEL_100X150, MediaSize.LABEL_100X50)

        assertEquals(MediaSize.LABEL_4X6, defaultLabelSize(loaded))
    }

    /** First means first: the rest are what else it can take, not what it has. */
    @Test
    fun `the printer's first size wins over the ones that follow`() {
        val loaded = printer(MediaSize.LABEL_100X50, MediaSize.LABEL_4X6)

        assertEquals(MediaSize.LABEL_100X50, defaultLabelSize(loaded))
    }

    @Test
    fun `a custom size the printer was set to is its default`() {
        val custom = MediaSize.custom(60f, 35f)

        assertEquals(custom, defaultLabelSize(printer(custom, MediaSize.LABEL_4X6)))
    }

    /** A printer can be defaulted to A4, which no label screen offers. */
    @Test
    fun `paper is skipped on the way to the first label size`() {
        val loaded = printer(MediaSize.A4, MediaSize.LABEL_100X150)

        assertEquals(MediaSize.LABEL_100X150, defaultLabelSize(loaded))
    }

    /**
     * The same physical roll under two names. Returning the printer's own
     * PHOTO_4X6 would be a size the Labels row does not contain, so the
     * default is the catalogue entry with the same dimensions.
     */
    @Test
    fun `a photo 4 x 6 default resolves to the 4 x 6 label`() {
        assertEquals(MediaSize.LABEL_4X6, defaultLabelSize(printer(MediaSize.PHOTO_4X6)))
    }

    @Test
    fun `a typed-in size that the catalogue now carries resolves to the catalogue entry`() {
        val typedIn = MediaSize.custom(50f, 25f)

        val default = defaultLabelSize(printer(typedIn))

        assertEquals(MediaSize.LABEL_50X25, default)
        assertTrue(default in labelSizesFor(printer(typedIn)))
    }

    @Test
    fun `whatever the default is, the row contains it`() {
        val cases = listOf(
            printer(MediaSize.LABEL_4X6),
            printer(MediaSize.PHOTO_4X6),
            printer(MediaSize.A4, MediaSize.custom(60f, 35f)),
            printer(MediaSize.custom(50f, 25f)),
            printer(MediaSize.custom(45f, 20f), MediaSize.LABEL_100X50),
        )
        for (case in cases) {
            val default = defaultLabelSize(case)!!
            assertTrue("$default is not in the row", default in labelSizesFor(case))
        }
    }

    @Test
    fun `a printer with no label size has no default`() {
        assertNull(defaultLabelSize(printer(MediaSize.A4, MediaSize.LETTER)))
        assertNull(defaultLabelSize(printer()))
    }

    // -- when the screen adopts it -----------------------------------------

    @Test
    fun `selecting a printer moves an untouched screen onto its size`() {
        val loaded = printer(MediaSize.LABEL_4X6)

        assertEquals(
            MediaSize.LABEL_4X6,
            sizeWhenPrinterSelected(MediaSize.LABEL_100X50, chosenByUser = false, printer = loaded),
        )
    }

    /**
     * The size row is above the printer row, so picking a size and then a
     * printer is the ordinary order. A deliberate choice must not be replaced
     * because the printer chip was tapped afterwards.
     */
    @Test
    fun `a size the user chose is not replaced by selecting a printer`() {
        val loaded = printer(MediaSize.LABEL_4X6)

        assertEquals(
            MediaSize.LABEL_50X25,
            sizeWhenPrinterSelected(MediaSize.LABEL_50X25, chosenByUser = true, printer = loaded),
        )
    }

    @Test
    fun `with no printer selected the size is left alone`() {
        assertEquals(
            MediaSize.LABEL_100X50,
            sizeWhenPrinterSelected(MediaSize.LABEL_100X50, chosenByUser = false, printer = null),
        )
    }

    @Test
    fun `a printer with no label size does not move the screen`() {
        assertEquals(
            MediaSize.LABEL_100X50,
            sizeWhenPrinterSelected(
                MediaSize.LABEL_100X50,
                chosenByUser = false,
                printer = printer(MediaSize.A4),
            ),
        )
    }
}
