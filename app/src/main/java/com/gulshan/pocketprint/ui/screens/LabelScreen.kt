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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.label.EscPos
import com.gulshan.pocketprint.label.LabelText
import com.gulshan.pocketprint.ui.MediaSizeSaver
import com.gulshan.pocketprint.ui.enumSaver
import com.gulshan.pocketprint.model.LabelStock
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
    val context = LocalContext.current
    val saved by viewModel.savedPrinters.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    // Saveable, not remembered. Android 16 already ignores orientation locks on
    // large screens and 17 removes the opt-out, so a rotation is not something
    // this form gets to avoid - and losing everything typed on one is the
    // oldest bug in Android.
    var title by rememberSaveable {
        mutableStateOf(context.getString(R.string.label_default_heading))
    }
    var line2 by rememberSaveable { mutableStateOf("") }
    var line3 by rememberSaveable { mutableStateOf("") }
    var barcode by rememberSaveable {
        mutableStateOf(context.getString(R.string.label_default_barcode))
    }
    // By id: a Printer is not Parcelable, and the saved list is the truth anyway.
    var selectedPrinterId by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable(stateSaver = enumSaver(PrintLanguage.entries.toList())) {
        mutableStateOf(PrintLanguage.TSPL)
    }
    var media by rememberSaveable(stateSaver = MediaSizeSaver) {
        mutableStateOf(MediaSize.LABEL_100X50)
    }
    val status by viewModel.labelStatus.collectAsStateWithLifecycle()

    // Resolved from the saved list each time, so a printer deleted while this
    // screen was in the background simply deselects rather than lingering as a
    // stale copy of a printer that no longer exists.
    val selectedPrinter = saved.firstOrNull { it.id == selectedPrinterId }

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
        SectionHeader(stringResource(R.string.label_content))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.label_heading)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = line2,
            onValueChange = { line2 = it },
            label = { Text(stringResource(R.string.label_line2)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = line3,
            onValueChange = { line3 = it },
            label = { Text(stringResource(R.string.label_line3)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = barcode,
            onValueChange = { barcode = it },
            label = { Text(stringResource(R.string.label_barcode)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(stringResource(R.string.label_size))
        ChipRow(
            items = MediaSize.LABELS,
            selected = media,
            label = { it.label },
            onSelect = { media = it },
        )

        SectionHeader(stringResource(R.string.label_language))
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

        SectionHeader(stringResource(R.string.label_printer))
        if (labelPrinters.isEmpty()) {
            InfoBanner(
                stringResource(R.string.label_no_printers),
            )
        } else {
            ChipRow(
                items = labelPrinters,
                selected = selectedPrinter,
                label = { it.displayName },
                onSelect = { selectedPrinterId = it.id },
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
                    stock = printer.stock,
                    copies = options.copies,
                    headline = title,
                    line2 = line2,
                    line3 = line3,
                    barcodeData = barcode,
                    receiptWidthDots = printer.capabilities.rasterWidthDots ?: 576,
                )
                viewModel.printRawLabel(printer, bytes, context.getString(R.string.label_job_name))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) { Text(stringResource(R.string.label_print)) }

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
            stringResource(R.string.label_help),
        )
        Column(Modifier.padding(bottom = 24.dp)) {}
    }
}

/** Left and right margin on a label, in dots. */
private const val LABEL_MARGIN = 24

/**
 * The text block, sized the way the printer-font path sizes it, so a Latin
 * label looks the same whichever route it takes.
 */
private fun textLines(headline: String, line2: String, line3: String) = listOf(
    LabelText.Line(headline, sizeDots = 40f, bold = true),
    LabelText.Line(line2, sizeDots = 28f),
    LabelText.Line(line3, sizeDots = 28f),
)

private fun buildLabel(
    language: PrintLanguage,
    media: MediaSize,
    dpi: Int,
    stock: LabelStock,
    copies: Int,
    headline: String,
    line2: String,
    line3: String,
    barcodeData: String,
    receiptWidthDots: Int,
): ByteArray {
    // One decision for the whole label rather than one per line. A label with a
    // Latin heading and a Hindi body would otherwise mix a printer-resident
    // font with a rendered one, at different weights and metrics, and look like
    // a mistake rather than a fallback.
    val printerFonts = LabelText.printerFontsCanCarry(listOf(headline, line2, line3))

    return when (language) {

        PrintLanguage.TSPL -> Tspl(media, dpi).apply {
            setup(stock)
            var y = LABEL_MARGIN
            if (printerFonts) {
                text(LABEL_MARGIN, y, headline, font = "3"); y += 48
                if (line2.isNotBlank()) { text(LABEL_MARGIN, y, line2, font = "2"); y += 38 }
                if (line3.isNotBlank()) { text(LABEL_MARGIN, y, line3, font = "2"); y += 40 }
            } else {
                val block = LabelText.render(
                    textLines(headline, line2, line3),
                    media.dotsWide(dpi) - LABEL_MARGIN * 2,
                )
                if (block != null) {
                    image(LABEL_MARGIN, y, block)
                    y += block.height + 16
                }
            }
            // Always a native command, never part of the bitmap: the printer's
            // own barcode generator lands the bars on exact dot boundaries,
            // which is what keeps it scannable at these sizes.
            if (barcodeData.isNotBlank()) barcode(LABEL_MARGIN, y, barcodeData, height = 70)
            print(sets = 1, copies = copies.coerceAtLeast(1))
        }.build()

        PrintLanguage.ZPL -> Zpl(media, dpi).apply {
            start(stock)
            var y = LABEL_MARGIN
            if (printerFonts) {
                text(LABEL_MARGIN, y, headline, height = 40, width = 40); y += 56
                if (line2.isNotBlank()) {
                    text(LABEL_MARGIN, y, line2, height = 28, width = 28); y += 40
                }
                if (line3.isNotBlank()) {
                    text(LABEL_MARGIN, y, line3, height = 28, width = 28); y += 45
                }
            } else {
                val block = LabelText.render(
                    textLines(headline, line2, line3),
                    media.dotsWide(dpi) - LABEL_MARGIN * 2,
                )
                if (block != null) {
                    image(LABEL_MARGIN, y, block)
                    y += block.height + 16
                }
            }
            if (barcodeData.isNotBlank()) barcode128(LABEL_MARGIN, y, barcodeData, height = 80)
            end(copies = copies.coerceAtLeast(1))
        }.build()

        else -> EscPos(receiptWidthDots).apply {
            initialize()
            if (printerFonts) {
                align(EscPos.Align.CENTER)
                textSize(2, 2)
                bold(true)
                line(headline)
                bold(false)
                textSize(1, 1)
                if (line2.isNotBlank()) line(line2)
                if (line3.isNotBlank()) line(line3)
            } else {
                // A receipt printer's resident font is the same dead end: the
                // characters simply are not in it.
                LabelText.render(textLines(headline, line2, line3), receiptWidthDots)
                    ?.let { image(it) }
            }
            if (barcodeData.isNotBlank()) {
                align(EscPos.Align.CENTER)
                feed(1)
                barcode128(barcodeData)
            }
            cut()
        }.build()
    }
}
