package com.gulshan.pocketprint.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gulshan.pocketprint.label.EscPos
import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.label.Zpl
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.ui.components.ChipRow
import com.gulshan.pocketprint.ui.components.InfoBanner
import com.gulshan.pocketprint.ui.components.SectionHeader
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

/**
 * Builds a label from fields and sends the printer's own commands directly,
 * skipping the PDF round trip. Text stays crisp because the printer renders it
 * with its internal fonts rather than us rasterizing an image.
 */
@Composable
fun LabelScreen(viewModel: PrintersViewModel) {
    val saved by viewModel.savedPrinters.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    var title by remember { mutableStateOf("SAMPLE LABEL") }
    var line2 by remember { mutableStateOf("") }
    var line3 by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("TEST1234") }
    var selectedPrinter by remember { mutableStateOf<Printer?>(null) }
    var language by remember { mutableStateOf(PrintLanguage.TSPL) }
    var media by remember { mutableStateOf(MediaSize.LABEL_100X50) }
    val status by viewModel.labelStatus.collectAsStateWithLifecycle()

    val allLanguages = listOf(PrintLanguage.TSPL, PrintLanguage.ZPL, PrintLanguage.ESC_POS)
    val languageChoices = selectedPrinter?.capabilities?.languages
        ?.filter { it in allLanguages }
        ?.takeIf { it.isNotEmpty() }
        ?: allLanguages

    val labelPrinters = saved.filter {
        it.capabilities.languages.any { l -> l.isRaster && l != PrintLanguage.PWG_RASTER }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Label content")

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Heading") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = line2,
            onValueChange = { line2 = it },
            label = { Text("Line 2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = line3,
            onValueChange = { line3 = it },
            label = { Text("Line 3") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = barcode,
            onValueChange = { barcode = it },
            label = { Text("Barcode / QR data") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader("Label size")
        ChipRow(
            items = MediaSize.LABELS,
            selected = media,
            label = { it.label },
            onSelect = { media = it },
        )

        SectionHeader("Command language")
        ChipRow(
            items = languageChoices,
            selected = language,
            label = {
                when (it) {
                    PrintLanguage.TSPL -> "TSPL (TSC)"
                    PrintLanguage.ZPL -> "ZPL (Zebra)"
                    else -> "ESC/POS"
                }
            },
            onSelect = { language = it },
        )

        SectionHeader("Printer")
        if (labelPrinters.isEmpty()) {
            InfoBanner(
                "No label or receipt printers saved yet. Pair one over Bluetooth, " +
                    "then save it on the Print tab.",
            )
        } else {
            ChipRow(
                items = labelPrinters,
                selected = selectedPrinter,
                label = { it.displayName },
                onSelect = { selectedPrinter = it },
            )
        }

        // Sending TSPL to a Zebra prints a page of literal command text, so the
        // dialect follows whatever the chosen printer advertises.
        LaunchedEffect(selectedPrinter) {
            selectedPrinter?.capabilities?.languages
                ?.firstOrNull { it.isRaster && it != PrintLanguage.PWG_RASTER }
                ?.let { language = it }
            viewModel.clearLabelStatus()
        }

        Button(
            enabled = selectedPrinter != null,
            onClick = {
                val printer = selectedPrinter ?: return@Button
                val dpi = printer.capabilities.resolutionsDpi.firstOrNull() ?: 203
                val bytes = buildLabel(
                    language = language,
                    media = media,
                    dpi = dpi,
                    density = options.density,
                    copies = options.copies,
                    headline = title,
                    line2 = line2,
                    line3 = line3,
                    barcodeData = barcode,
                    receiptWidthDots = printer.capabilities.rasterWidthDots ?: 576,
                )
                viewModel.printRawLabel(printer, bytes, "Label")
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) { Text("Print label") }

        status?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        InfoBanner(
            "Commands go straight to the printer, so the barcode is generated by " +
                "the printer's own firmware rather than as an image. That keeps it " +
                "sharp and scannable at small sizes.",
        )
        Column(Modifier.padding(bottom = 24.dp)) {}
    }
}

private fun buildLabel(
    language: PrintLanguage,
    media: MediaSize,
    dpi: Int,
    density: Int,
    copies: Int,
    headline: String,
    line2: String,
    line3: String,
    barcodeData: String,
    receiptWidthDots: Int,
): ByteArray = when (language) {

    PrintLanguage.TSPL -> Tspl(media, dpi).apply {
        setup(density = density)
        text(24, 24, headline, font = "3")
        if (line2.isNotBlank()) text(24, 72, line2, font = "2")
        if (line3.isNotBlank()) text(24, 110, line3, font = "2")
        if (barcodeData.isNotBlank()) barcode(24, 150, barcodeData, height = 70)
        print(sets = 1, copies = copies.coerceAtLeast(1))
    }.build()

    PrintLanguage.ZPL -> Zpl(media, dpi).apply {
        start(density = density)
        text(24, 24, headline, height = 40, width = 40)
        if (line2.isNotBlank()) text(24, 80, line2, height = 28, width = 28)
        if (line3.isNotBlank()) text(24, 120, line3, height = 28, width = 28)
        if (barcodeData.isNotBlank()) barcode128(24, 165, barcodeData, height = 80)
        end(copies = copies.coerceAtLeast(1))
    }.build()

    else -> EscPos(receiptWidthDots).apply {
        initialize()
        align(EscPos.Align.CENTER)
        textSize(2, 2)
        bold(true)
        line(headline)
        bold(false)
        textSize(1, 1)
        if (line2.isNotBlank()) line(line2)
        if (line3.isNotBlank()) line(line3)
        if (barcodeData.isNotBlank()) {
            feed(1)
            barcode128(barcodeData)
        }
        cut()
    }.build()
}
