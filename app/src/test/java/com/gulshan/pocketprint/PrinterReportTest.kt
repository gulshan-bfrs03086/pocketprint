package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.JobState
import com.gulshan.pocketprint.model.PrintJobRecord
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.print.Diagnostics
import com.gulshan.pocketprint.permissions.AppHealth
import com.gulshan.pocketprint.print.PrinterReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The report is written to be pasted into a public bug report, so what it
 * leaves out matters as much as what it carries.
 */
class PrinterReportTest {

    private val mac = "AC:4D:16:9F:2B:7E"

    private val printer = Printer(
        id = "p1",
        displayName = "4B-2044PA",
        address = PrinterAddress.Bluetooth(mac = mac, link = BtLink.CLASSIC),
        makeAndModel = "4BARCODE 4B-2044PA",
        capabilities = PrinterCapabilities(
            languages = listOf(PrintLanguage.ZPL),
            resolutionsDpi = listOf(203),
            rasterWidthDots = 812,
        ),
    )

    @Before
    fun clearTrail() = Diagnostics.clear()

    private fun job(
        id: String,
        printerId: String = "p1",
        name: String = "label.pdf",
        state: JobState = JobState.SENT,
        note: String? = null,
        error: String? = null,
    ) = PrintJobRecord(
        id = id,
        printerId = printerId,
        printerName = "4B-2044PA",
        documentName = name,
        state = state,
        createdAtEpochMs = 1_757_000_000_000L,
        bytesSent = 107_357,
        note = note,
        error = error,
    )

    @Test
    fun `the device half of a MAC never reaches the report`() {
        val report = PrinterReport.build(printer, emptyList())
        assertFalse("the full MAC must not appear", report.contains(mac))
        assertFalse(report.contains("9F:2B:7E"))
        // The vendor half is the useful half and is kept: it says who made it.
        assertTrue(report.contains("AC:4D:16"))
    }

    @Test
    fun `an unconfirmed printer says so, in as many words`() {
        val report = PrinterReport.build(printer, emptyList())
        assertTrue(report.contains("no correct label has been confirmed"))

        val confirmed = PrinterReport.build(printer.copy(testPrintConfirmed = true), emptyList())
        assertTrue(confirmed.contains("somebody looked at a test label"))
    }

    @Test
    fun `the reason a job could not be confirmed is carried, not just its state`() {
        val report = PrinterReport.build(
            printer,
            listOf(job("j1", note = "Bluetooth printing carries no acknowledgement.")),
        )
        assertTrue(report.contains("SENT"))
        assertTrue(report.contains("Bluetooth printing carries no acknowledgement."))
    }

    @Test
    fun `only this printer's jobs are included`() {
        val report = PrinterReport.build(
            printer,
            listOf(job("j1", name = "mine.pdf"), job("j2", printerId = "other", name = "theirs.pdf")),
        )
        assertTrue(report.contains("mine.pdf"))
        assertFalse(report.contains("theirs.pdf"))
    }

    @Test
    fun `the diagnostic trail is carried through`() {
        Diagnostics.record("TsplDiag", "inkBits=1024 of 8192 (12.50%)")
        val report = PrinterReport.build(printer, emptyList())
        assertTrue(report.contains("inkBits=1024 of 8192 (12.50%)"))
    }

    @Test
    fun `a hibernating app says so, because it explains failures that look like the printer`() {
        // A hibernated app has had its Bluetooth permission taken away without
        // anybody touching a setting, and every symptom points at the printer.
        val report = PrinterReport.build(
            printer,
            emptyList(),
            PrinterReport.Health(
                hibernation = AppHealth.Hibernation.WILL_HIBERNATE,
                ignoresBatteryOptimisation = false,
            ),
        )
        assertTrue(report.contains("three months"))
        assertTrue(report.contains("optimised"))

        val healthy = PrinterReport.build(
            printer,
            emptyList(),
            PrinterReport.Health(AppHealth.Hibernation.EXEMPT, true),
        )
        assertTrue(healthy.contains("Hibernation exempt"))
        assertTrue(healthy.contains("unrestricted"))
    }

    @Test
    fun `the report warns before it is pasted anywhere`() {
        // It carries document names, which are the user's business.
        val report = PrinterReport.build(printer, listOf(job("j1", name = "tax-return.pdf")))
        assertTrue(report.contains("names the documents you"))
        assertTrue(report.contains("tax-return.pdf"))
    }
}
