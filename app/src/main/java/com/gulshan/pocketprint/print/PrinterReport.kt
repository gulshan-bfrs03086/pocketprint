package com.gulshan.pocketprint.print

import android.os.Build
import com.gulshan.pocketprint.BuildConfig
import com.gulshan.pocketprint.model.PrintJobRecord
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything worth knowing about one printer, as text somebody can paste.
 *
 * "It doesn't print" is not a report anybody can act on. What makes a printer
 * bug tractable is knowing the dialect, the head width, how much ink the
 * rasteriser put on the page and how many bytes came out the other end — and
 * all of that already exists, it was just going to logcat on a developer's
 * machine. This is the same information, addressed to the person who has the
 * printer.
 */
object PrinterReport {

    private const val RECENT_JOBS = 10

    fun build(printer: Printer, jobs: List<PrintJobRecord>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        val capabilities = printer.capabilities
        val media = capabilities.mediaSizes.firstOrNull()

        return buildString {
            appendLine("PocketPrint printer report")
            appendLine("Generated $stamp")
            appendLine()
            appendLine(
                "Read this before pasting it anywhere: it names the documents you " +
                    "printed recently.",
            )
            appendLine()

            appendLine("App        ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "${BuildConfig.BUILD_TYPE} build")
            appendLine("Device     ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()

            appendLine("Printer    ${printer.displayName}")
            printer.makeAndModel?.let { appendLine("Model      $it") }
            appendLine("Transport  ${describe(printer.address)}")
            appendLine("Language   ${capabilities.languages.joinToString().ifBlank { "unknown" }}")
            appendLine(
                "Media      " + (media?.let { "${it.id} (${it.widthMm}x${it.heightMm} mm)" }
                    ?: "unknown"),
            )
            appendLine(
                "Head       ${capabilities.resolutionsDpi.joinToString()} dpi, " +
                    "${capabilities.rasterWidthDots ?: "unknown"} dots wide",
            )
            appendLine(
                "Confirmed  " + if (printer.testPrintConfirmed) {
                    "yes, somebody looked at a test label and said it was right"
                } else {
                    "no - no correct label has been confirmed by eye on this printer"
                },
            )
            appendLine()

            val mine = jobs.filter { it.printerId == printer.id }.take(RECENT_JOBS)
            appendLine("Recent jobs (${mine.size})")
            if (mine.isEmpty()) appendLine("  none")
            mine.forEach { job ->
                appendLine(
                    "  ${clock.format(Date(job.createdAtEpochMs))}  " +
                        "${job.state}  ${job.bytesSent} bytes  ${job.documentName}",
                )
                // The reason a job could not be confirmed, or why it failed, is
                // the part that actually points at a cause.
                (job.error ?: job.note)?.let { appendLine("      $it") }
            }
            appendLine()

            val trail = Diagnostics.recent()
            appendLine("Diagnostics (${trail.size})")
            if (trail.isEmpty()) {
                appendLine("  nothing recorded yet - print something and copy this again")
            }
            trail.forEach { appendLine("  $it") }

            if (!BuildConfig.DEBUG) {
                appendLine()
                appendLine(
                    "A debug build records more: per-page dark-pixel counts, and a copy " +
                        "of the exact command stream for byte-level inspection.",
                )
            }
        }
    }

    /**
     * The vendor half of a MAC is the useful half — it says who made the
     * printer. The device half identifies one particular unit and belongs to
     * whoever owns it, so it does not go into something meant to be pasted into
     * a public bug report.
     */
    private fun describe(address: PrinterAddress): String = when (address) {
        is PrinterAddress.Bluetooth ->
            "Bluetooth ${address.link}, ${redactMac(address.mac)}"
        is PrinterAddress.Usb ->
            "USB %04x:%04x".format(address.vendorId, address.productId)
        is PrinterAddress.Raw -> "Raw port ${address.port}"
        is PrinterAddress.Ipp -> "IPP${if (address.secure) "S" else ""} port ${address.port}"
    }

    private fun redactMac(mac: String): String {
        val parts = mac.split(':')
        if (parts.size != 6) return "address hidden"
        return (parts.take(3) + listOf("xx", "xx", "xx")).joinToString(":") +
            "  (device half removed)"
    }
}
