package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.print.setupTestLabel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label setup prints is the first job a printer gets, and a printer keeps
 * what a job tells it. It used to be built with default stock, so setting up a
 * printer on continuous paper told it to look for a gap - and everything printed
 * after that was a job to a printer hunting for one.
 */
class SetupTestLabelTest {

    private fun printer(stock: LabelStock) = Printer(
        id = "bt:DC:0D:30:56:8B:3E",
        displayName = "PS-568B3E",
        address = PrinterAddress.Bluetooth("DC:0D:30:56:8B:3E"),
        saved = true,
        stock = stock,
    )

    private fun tspl(stock: LabelStock): String = String(
        setupTestLabel(printer(stock), PrintLanguage.TSPL, MediaSize.LABEL_75X75, 599, 203),
        Charsets.ISO_8859_1,
    )

    private fun zpl(stock: LabelStock): String = String(
        setupTestLabel(printer(stock), PrintLanguage.ZPL, MediaSize.LABEL_75X75, 599, 203),
        Charsets.ISO_8859_1,
    )

    @Test
    fun `a continuous printer's test label tells it there are no gaps`() {
        val label = tspl(LabelStock(sensing = MediaSensing.CONTINUOUS))

        assertTrue(label, "GAP 0 mm, 0 mm" in label)
        assertFalse("it still asks for a 3 mm gap:\n$label", "GAP 3 mm" in label)
    }

    @Test
    fun `a gap printer's test label looks for the gap it was told about`() {
        val label = tspl(LabelStock(sensing = MediaSensing.GAP, gapMm = 4f))

        assertTrue(label, "GAP 4 mm, 0 mm" in label)
    }

    @Test
    fun `a black mark printer's test label looks for the mark`() {
        val label = tspl(LabelStock(sensing = MediaSensing.BLACK_MARK, gapMm = 2f))

        assertTrue(label, "BLINE 2 mm, 0 mm" in label)
        assertFalse(label, "GAP " in label)
    }

    @Test
    fun `the test label burns at the printer's own darkness and speed`() {
        val stock = LabelStock(darkness = 12, speedIps = 6)
        val label = tspl(stock)

        assertTrue(label, "DENSITY ${stock.darknessForTspl}" in label)
        assertTrue(label, "SPEED 6" in label)
    }

    @Test
    fun `it is still a test label`() {
        val label = tspl(LabelStock(sensing = MediaSensing.CONTINUOUS))

        assertTrue(label, "POCKETPRINT READY" in label)
        assertTrue(label, "PRINT 1,1" in label)
    }

    @Test
    fun `a continuous zpl printer's test label turns media tracking off`() {
        val label = zpl(LabelStock(sensing = MediaSensing.CONTINUOUS))

        assertTrue(label, "^MNN" in label)
        assertFalse(label, "^MNY" in label)
    }

    @Test
    fun `a gap zpl printer's test label tracks the gaps`() {
        val label = zpl(LabelStock(sensing = MediaSensing.GAP))

        assertTrue(label, "^MNY" in label)
    }
}
