package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.model.asSavedAfterSetup
import com.gulshan.pocketprint.model.connectedThrough
import com.gulshan.pocketprint.model.linkAfterSettings
import com.gulshan.pocketprint.model.keepingSettingsOf
import com.gulshan.pocketprint.model.loadedWith
import com.gulshan.pocketprint.model.setupLink
import com.gulshan.pocketprint.model.setupSensing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup builds its printer from a freshly discovered device and saves it over
 * the saved one of the same id. Everything the user had set came back to its
 * default, and the one that does damage is the stock: a printer on continuous
 * paper went back to "look for a gap, 3 mm", the next job told the printer so,
 * and the printer went hunting for a gap and stopped with a paper fault.
 */
class KeepSettingsTest {

    private val mac = "DC:0D:30:56:8B:3E"
    private val id = "bt:$mac"

    private val continuous = LabelStock(
        sensing = MediaSensing.CONTINUOUS,
        gapMm = 3f,
        darkness = 12,
        speedIps = 6,
    )

    private fun printer(
        stock: LabelStock = LabelStock(),
        compressed: Boolean = false,
        link: BtLink = BtLink.AUTO,
        mediaSizes: List<MediaSize> = listOf(MediaSize.LABEL_75X75),
        language: PrintLanguage = PrintLanguage.TSPL,
        raster: Int? = 599,
        model: String? = "PS-568B3E",
        confirmed: Boolean = false,
        macAddress: String = mac,
        id: String = this.id,
    ) = Printer(
        id = id,
        displayName = "PS-568B3E",
        address = PrinterAddress.Bluetooth(mac = macAddress, link = link),
        makeAndModel = model,
        capabilities = PrinterCapabilities(
            languages = listOf(language),
            mediaSizes = mediaSizes,
            resolutionsDpi = listOf(203),
            rasterWidthDots = raster,
            compressedBitmaps = compressed,
        ),
        saved = true,
        stock = stock,
        testPrintConfirmed = confirmed,
    )

    // -- what is carried across ---------------------------------------------

    @Test
    fun `a printer saved as continuous is still continuous after setup builds it again`() {
        val saved = printer(stock = continuous)
        val fromSetup = printer() // defaults: gap, 3 mm

        val result = fromSetup.keepingSettingsOf(saved)

        assertEquals(MediaSensing.CONTINUOUS, result.stock.sensing)
    }

    @Test
    fun `the whole stock comes across, not only the sensing`() {
        val result = printer().keepingSettingsOf(printer(stock = continuous))

        assertEquals(continuous, result.stock)
    }

    @Test
    fun `compressed images stay on`() {
        val result = printer(compressed = false).keepingSettingsOf(printer(compressed = true))

        assertTrue(result.capabilities.compressedBitmaps)
    }

    @Test
    fun `compressed images stay off when they were off`() {
        val result = printer(compressed = true).keepingSettingsOf(printer(compressed = false))

        assertFalse(result.capabilities.compressedBitmaps)
    }

    @Test
    fun `the bluetooth link somebody chose is kept for the same device`() {
        val saved = printer(link = BtLink.CLASSIC)

        val result = printer(link = BtLink.AUTO).keepingSettingsOf(saved)

        assertEquals(
            BtLink.CLASSIC,
            (result.address as PrinterAddress.Bluetooth).link,
        )
    }

    @Test
    fun `a link is not borrowed from a different device that happens to share the id`() {
        // Ids are derived from the address, so this should not happen; if it
        // does, the printer being set up is not the one that was saved.
        val saved = printer(link = BtLink.CLASSIC, macAddress = "AA:BB:CC:DD:EE:FF")

        val result = printer(link = BtLink.AUTO).keepingSettingsOf(saved)

        assertEquals(BtLink.AUTO, (result.address as PrinterAddress.Bluetooth).link)
    }

    @Test
    fun `a size typed into printer settings is kept`() {
        val custom = MediaSize.custom(60f, 35f)
        val saved = printer(mediaSizes = listOf(MediaSize.LABEL_75X75, custom))

        val result = printer(mediaSizes = listOf(MediaSize.LABEL_75X75, MediaSize.LABEL_4X6))
            .keepingSettingsOf(saved)

        assertTrue(custom in result.capabilities.mediaSizes)
        assertEquals(
            "setup's own list, in its order, with the custom size after it",
            listOf(MediaSize.LABEL_75X75, MediaSize.LABEL_4X6, custom),
            result.capabilities.mediaSizes,
        )
    }

    @Test
    fun `a custom size setup already lists is not listed twice`() {
        val typedIn = MediaSize.custom(50f, 25f) // the catalogue now carries 50 x 25
        val saved = printer(mediaSizes = listOf(MediaSize.LABEL_75X75, typedIn))

        val result = printer(mediaSizes = listOf(MediaSize.LABEL_75X75, MediaSize.LABEL_50X25))
            .keepingSettingsOf(saved)

        assertEquals(
            1,
            result.capabilities.mediaSizes.count {
                it.widthMicrons == 50_000 && it.heightMicrons == 25_000
            },
        )
    }

    @Test
    fun `the label size setup was asked for stays first`() {
        // The person chose 4 x 6 on the card this time; the saved printer was
        // on 75 x 75. This setup's answer is the one that is theirs to give now.
        val saved = printer(mediaSizes = listOf(MediaSize.LABEL_75X75))

        val result = printer(mediaSizes = listOf(MediaSize.LABEL_4X6, MediaSize.LABEL_75X75))
            .keepingSettingsOf(saved)

        assertEquals(MediaSize.LABEL_4X6, result.capabilities.mediaSizes.first())
    }

    @Test
    fun `sizes that are not custom are not carried across`() {
        // A receipt printer's list holds receipt rolls. Re-detected as a label
        // printer, setup's own list has none of them, and they must not come back.
        val saved = printer(mediaSizes = listOf(MediaSize.RECEIPT_80, MediaSize.RECEIPT_58))

        val result = printer(mediaSizes = listOf(MediaSize.LABEL_75X75)).keepingSettingsOf(saved)

        assertEquals(listOf(MediaSize.LABEL_75X75), result.capabilities.mediaSizes)
    }

    @Test
    fun `a custom size that only matches one dimension of a listed size is kept`() {
        // 50 x 30 is not 50 x 25: both dimensions have to agree to be the same roll.
        val typedIn = MediaSize.custom(50f, 30f)
        val saved = printer(mediaSizes = listOf(MediaSize.LABEL_75X75, typedIn))

        val result = printer(mediaSizes = listOf(MediaSize.LABEL_75X75, MediaSize.LABEL_50X25))
            .keepingSettingsOf(saved)

        assertTrue(typedIn in result.capabilities.mediaSizes)
    }

    // -- what is not -------------------------------------------------------

    @Test
    fun `what setup has just measured is not overwritten by what was saved`() {
        val saved = printer(
            language = PrintLanguage.ZPL,
            raster = 400,
            model = "old model",
            confirmed = true,
        )

        val result = printer(
            language = PrintLanguage.TSPL,
            raster = 599,
            model = "RE310",
            confirmed = false,
        ).keepingSettingsOf(saved)

        assertEquals(listOf(PrintLanguage.TSPL), result.capabilities.languages)
        assertEquals(599, result.capabilities.rasterWidthDots)
        assertEquals("RE310", result.makeAndModel)
        assertFalse(
            "the test label about to be printed has not been looked at yet",
            result.testPrintConfirmed,
        )
    }

    @Test
    fun `a printer saved for the first time is returned as it is`() {
        val fresh = printer()

        assertSame(fresh, fresh.keepingSettingsOf(null))
    }

    @Test
    fun `a record with another id is not this printer's and is ignored`() {
        val fresh = printer()
        val other = printer(stock = continuous, compressed = true, id = "bt:AA:BB:CC:DD:EE:FF")

        assertSame(fresh, fresh.keepingSettingsOf(other))
    }

    @Test
    fun `merging twice gives the same printer as merging once`() {
        // Setup merges when it starts and again when it saves.
        val saved = printer(stock = continuous, compressed = true, link = BtLink.CLASSIC)
        val once = printer().keepingSettingsOf(saved)

        assertEquals(once, once.keepingSettingsOf(saved))
    }

    // -- what the card shows ------------------------------------------------

    private fun candidate(printer: Printer) = printer.copy(saved = false, stock = LabelStock())

    @Test
    fun `a choice made on the card is what it shows`() {
        val saved = listOf(printer(stock = continuous))

        assertEquals(
            MediaSensing.GAP,
            setupSensing(MediaSensing.GAP, saved, listOf(candidate(printer()))),
        )
    }

    @Test
    fun `untouched, with one printer to set up, it shows what that printer is`() {
        // Anything else would be a card that says Gap over a printer that Set up
        // is about to leave on Continuous.
        val saved = listOf(printer(stock = continuous))

        assertEquals(
            MediaSensing.CONTINUOUS,
            setupSensing(null, saved, listOf(candidate(printer()))),
        )
    }

    @Test
    fun `untouched, a printer that has never been saved shows gap, which is what it will get`() {
        assertEquals(
            MediaSensing.GAP,
            setupSensing(null, emptyList(), listOf(candidate(printer()))),
        )
    }

    @Test
    fun `it is the printer being set up that counts, not another one that is saved`() {
        val other = printer(stock = continuous, id = "bt:AA:BB:CC:DD:EE:FF", macAddress = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            MediaSensing.GAP,
            setupSensing(null, listOf(other), listOf(candidate(printer()))),
        )
    }

    @Test
    fun `with several printers to choose from nothing is shown as selected`() {
        val two = listOf(candidate(printer()), candidate(printer(id = "bt:AA:BB:CC:DD:EE:FF")))

        assertNull(setupSensing(null, listOf(printer(stock = continuous)), two))
    }

    @Test
    fun `with no printer paired yet nothing is shown as selected`() {
        assertNull(setupSensing(null, emptyList(), emptyList()))
    }

    // -- what setup saves ---------------------------------------------------

    /**
     * A printer saved as Gap - the state the original bug leaves it in - and the
     * person taps Continuous and sets it up. The test label goes out Continuous;
     * what is saved has to be Continuous too, or the first real job is a gap
     * hunt again. The card's choice used to be applied before the saved stock
     * was carried across, which put Gap back.
     */
    @Test
    fun `choosing continuous for a printer saved as gap saves it as continuous`() {
        val saved = printer(stock = LabelStock(sensing = MediaSensing.GAP))

        val result = printer().asSavedAfterSetup(saved, MediaSensing.CONTINUOUS)

        assertEquals(MediaSensing.CONTINUOUS, result.stock.sensing)
    }

    @Test
    fun `choosing gap for a printer saved as continuous saves it as gap`() {
        val result = printer().asSavedAfterSetup(printer(stock = continuous), MediaSensing.GAP)

        assertEquals(MediaSensing.GAP, result.stock.sensing)
    }

    @Test
    fun `without a choice the saved paper stands`() {
        val result = printer().asSavedAfterSetup(printer(stock = continuous), null)

        assertEquals(continuous, result.stock)
    }

    @Test
    fun `a choice changes the paper and nothing else about the saved stock`() {
        val saved = printer(stock = LabelStock(gapMm = 4f, darkness = 11, speedIps = 5))

        val result = printer().asSavedAfterSetup(saved, MediaSensing.CONTINUOUS)

        assertEquals(
            LabelStock(
                sensing = MediaSensing.CONTINUOUS,
                gapMm = 4f,
                darkness = 11,
                speedIps = 5,
            ),
            result.stock,
        )
    }

    @Test
    fun `a new printer takes the choice, and is gap when there was none`() {
        assertEquals(
            MediaSensing.CONTINUOUS,
            printer().asSavedAfterSetup(null, MediaSensing.CONTINUOUS).stock.sensing,
        )
        assertEquals(MediaSensing.GAP, printer().asSavedAfterSetup(null, null).stock.sensing)
    }

    @Test
    fun `setup saves the same thing when it runs the merge twice`() {
        // It runs when setup starts and again when the result is saved.
        val saved = printer(stock = continuous, compressed = true)
        val once = printer().asSavedAfterSetup(saved, MediaSensing.GAP)

        assertEquals(once, once.asSavedAfterSetup(saved, MediaSensing.GAP))
    }

    // -- applying the choice ------------------------------------------------

    @Test
    fun `loading a printer with continuous paper changes only the sensing`() {
        val before = printer(stock = LabelStock(gapMm = 4f, darkness = 11, speedIps = 5))

        val after = before.loadedWith(MediaSensing.CONTINUOUS)

        assertEquals(
            LabelStock(sensing = MediaSensing.CONTINUOUS, gapMm = 4f, darkness = 11, speedIps = 5),
            after.stock,
        )
    }

    @Test
    fun `no choice leaves the printer alone`() {
        val before = printer(stock = continuous)

        assertSame(before, before.loadedWith(null))
    }

    // -- the connection choice ----------------------------------------------

    private fun linkOf(printer: Printer) = (printer.address as PrinterAddress.Bluetooth).link

    @Test
    fun `a printer can be set to connect through the TSC library`() {
        assertEquals(BtLink.TSC_SDK, linkOf(printer(link = BtLink.AUTO).connectedThrough(BtLink.TSC_SDK)))
    }

    @Test
    fun `no choice leaves the connection alone`() {
        assertEquals(BtLink.CLASSIC, linkOf(printer(link = BtLink.CLASSIC).connectedThrough(null)))
    }

    @Test
    fun `a printer with no bluetooth link is not given one`() {
        val network = Printer(
            id = "raw:10.0.0.9:9100",
            displayName = "Kitchen",
            address = PrinterAddress.Raw("10.0.0.9", 9100),
        )

        assertSame(network, network.connectedThrough(BtLink.TSC_SDK))
    }

    @Test
    fun `choosing the TSC library for a printer saved on automatic saves it on the library`() {
        val result = printer().asSavedAfterSetup(printer(link = BtLink.AUTO), null, BtLink.TSC_SDK)

        assertEquals(BtLink.TSC_SDK, linkOf(result))
    }

    @Test
    fun `setting up again without a choice keeps the TSC library`() {
        val result = printer(link = BtLink.AUTO).asSavedAfterSetup(printer(link = BtLink.TSC_SDK), null, null)

        assertEquals(BtLink.TSC_SDK, linkOf(result))
    }

    @Test
    fun `choosing automatic takes a printer off the TSC library`() {
        val result = printer().asSavedAfterSetup(printer(link = BtLink.TSC_SDK), null, BtLink.AUTO)

        assertEquals(BtLink.AUTO, linkOf(result))
    }

    @Test
    fun `the connection and the paper are chosen independently`() {
        val saved = printer(stock = continuous, link = BtLink.CLASSIC)

        val result = printer().asSavedAfterSetup(saved, null, BtLink.TSC_SDK)

        assertEquals(BtLink.TSC_SDK, linkOf(result))
        assertEquals(MediaSensing.CONTINUOUS, result.stock.sensing)
    }

    @Test
    fun `the connection chips show a choice that was made`() {
        assertEquals(BtLink.AUTO, setupLink(BtLink.AUTO, listOf(printer(link = BtLink.TSC_SDK)), listOf(candidate(printer()))))
    }

    @Test
    fun `untouched, they show how the one printer being set up is connected`() {
        val saved = listOf(printer(link = BtLink.TSC_SDK))

        assertEquals(BtLink.TSC_SDK, setupLink(null, saved, listOf(candidate(printer()))))
    }

    @Test
    fun `a printer that was never saved shows the link discovery found for it`() {
        assertEquals(BtLink.CLASSIC, setupLink(null, emptyList(), listOf(candidate(printer(link = BtLink.CLASSIC)))))
    }

    @Test
    fun `with several printers to choose from the connection shows nothing`() {
        val two = listOf(candidate(printer()), candidate(printer(id = "bt:AA:BB:CC:DD:EE:FF")))

        assertNull(setupLink(null, listOf(printer(link = BtLink.TSC_SDK)), two))
        assertNull(setupLink(null, emptyList(), emptyList()))
    }

    @Test
    fun `it is the printer being set up that counts for the connection, not another saved one`() {
        val other = printer(link = BtLink.TSC_SDK, id = "bt:AA:BB:CC:DD:EE:FF", macAddress = "AA:BB:CC:DD:EE:FF")

        assertEquals(
            BtLink.AUTO,
            setupLink(null, listOf(other), listOf(candidate(printer(link = BtLink.AUTO)))),
        )
    }

    // -- what saving the settings dialog does to the connection ---------------

    @Test
    fun `switching the library on in a build that has it`() {
        assertEquals(BtLink.TSC_SDK, linkAfterSettings(BtLink.AUTO, viaTscLibrary = true, libraryBundled = true))
        assertEquals(BtLink.TSC_SDK, linkAfterSettings(BtLink.CLASSIC, viaTscLibrary = true, libraryBundled = true))
    }

    @Test
    fun `switching the library off goes back to automatic, in any build`() {
        assertEquals(BtLink.AUTO, linkAfterSettings(BtLink.TSC_SDK, viaTscLibrary = false, libraryBundled = true))
        // The one a build without the library must still be able to do: a printer
        // carried there on the library would otherwise be stranded.
        assertEquals(BtLink.AUTO, linkAfterSettings(BtLink.TSC_SDK, viaTscLibrary = false, libraryBundled = false))
    }

    @Test
    fun `a build without the library keeps a printer on it until it is switched off`() {
        assertEquals(BtLink.TSC_SDK, linkAfterSettings(BtLink.TSC_SDK, viaTscLibrary = true, libraryBundled = false))
    }

    @Test
    fun `a build without the library cannot switch it on`() {
        assertEquals(BtLink.AUTO, linkAfterSettings(BtLink.AUTO, viaTscLibrary = true, libraryBundled = false))
    }

    @Test
    fun `classic and LE are left exactly as they were when the library is off`() {
        for (bundled in listOf(true, false)) {
            assertEquals(BtLink.CLASSIC, linkAfterSettings(BtLink.CLASSIC, false, bundled))
            assertEquals(BtLink.BLE, linkAfterSettings(BtLink.BLE, false, bundled))
        }
    }
}
