package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.ui.MediaSizeSaver
import com.gulshan.pocketprint.ui.enumSaver

/**
 * Lets the user correct what the app guessed about a printer.
 *
 * The command dialect for a Bluetooth printer is inferred from its advertised
 * name, which is a heuristic that will always be wrong for some models. Without
 * a way to override it, a mis-guessed printer is permanently unusable: it will
 * happily accept the bytes and print pages of literal command text.
 */
@Composable
fun PrinterSettingsDialog(
    printer: Printer,
    onDismiss: () -> Unit,
    onSave: (Printer) -> Unit,
    onTestPage: (Printer) -> Unit,
    onCopyReport: () -> Unit,
    onCalibrate: (Printer) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(printer.displayName) }
    var language by rememberSaveable(stateSaver = enumSaver(PrintLanguage.entries.toList())) {
        mutableStateOf(printer.capabilities.languages.firstOrNull() ?: PrintLanguage.ESC_POS)
    }
    var media by rememberSaveable(stateSaver = MediaSizeSaver) {
        mutableStateOf(printer.capabilities.mediaSizes.firstOrNull() ?: MediaSize.LABEL_4X6)
    }
    var dpi by rememberSaveable {
        mutableStateOf(printer.capabilities.resolutionsDpi.firstOrNull() ?: 203)
    }
    var widthDots by rememberSaveable {
        mutableStateOf((printer.capabilities.rasterWidthDots ?: 576).toString())
    }
    var exposeToSystem by rememberSaveable { mutableStateOf(printer.exposeToSystem) }

    // Sizes the user has typed in, kept alongside the built-in list. 50x30 and
    // 60x40 are two of the most common rolls on the market and neither is one
    // of the five sizes this app shipped with.
    var extraSizes by remember {
        mutableStateOf(printer.capabilities.mediaSizes.filter { it.isCustom })
    }
    var customWidth by rememberSaveable { mutableStateOf("") }
    var customHeight by rememberSaveable { mutableStateOf("") }

    var sensing by rememberSaveable(stateSaver = enumSaver(MediaSensing.entries.toList())) {
        mutableStateOf(printer.stock.sensing)
    }
    var gapMm by rememberSaveable { mutableStateOf(printer.stock.gapMm.toString()) }
    var darkness by rememberSaveable { mutableStateOf(printer.stock.darkness) }
    var speed by rememberSaveable { mutableStateOf(printer.stock.speedIps) }

    val isLabelPrinter = language == PrintLanguage.TSPL || language == PrintLanguage.ZPL
    val sizes = (MediaSize.ALL + extraSizes).distinctBy { it.id }

    fun stock() = LabelStock(
        sensing = sensing,
        gapMm = gapMm.toFloatOrNull()?.coerceIn(0f, 50f) ?: printer.stock.gapMm,
        offsetMm = printer.stock.offsetMm,
        darkness = darkness,
        speedIps = speed,
    )

    fun edited(): Printer = printer.copy(
        displayName = name.ifBlank { printer.displayName },
        exposeToSystem = exposeToSystem,
        stock = stock(),
        capabilities = printer.capabilities.copy(
            languages = listOf(language),
            mediaSizes = (listOf(media) + extraSizes + printer.capabilities.mediaSizes)
                .distinctBy { it.id },
            resolutionsDpi = listOf(dpi),
            rasterWidthDots = widthDots.toIntOrNull()?.takeIf { it > 0 },
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Printer settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Command language", style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    items = listOf(
                        PrintLanguage.TSPL,
                        PrintLanguage.ZPL,
                        PrintLanguage.ESC_POS,
                        PrintLanguage.PCL,
                        PrintLanguage.PDF,
                    ),
                    selected = language,
                    label = {
                        when (it) {
                            PrintLanguage.TSPL -> "TSPL"
                            PrintLanguage.ZPL -> "ZPL"
                            PrintLanguage.ESC_POS -> "ESC/POS"
                            PrintLanguage.PCL -> "PCL"
                            else -> "PDF"
                        }
                    },
                    onSelect = { language = it },
                )
                Text(
                    "If the printer spits out pages of readable commands instead of " +
                        "your label, this is the setting that is wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Default stock", style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    items = sizes,
                    selected = media,
                    label = { it.label },
                    onSelect = { media = it },
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customWidth,
                        onValueChange = { customWidth = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Width mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customHeight,
                        onValueChange = {
                            customHeight = it.filter { c -> c.isDigit() || c == '.' }
                        },
                        label = { Text("Height mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val w = customWidth.toFloatOrNull()
                            val h = customHeight.toFloatOrNull()
                            if (w != null && h != null && w > 0f && h > 0f) {
                                val size = MediaSize.custom(w, h)
                                extraSizes = (extraSizes + size).distinctBy { it.id }
                                media = size
                                customWidth = ""
                                customHeight = ""
                            }
                        },
                    ) { Text("Add") }
                }

                if (isLabelPrinter) {
                    Text("Label stock", style = MaterialTheme.typography.labelMedium)
                    ChipRow(
                        items = MediaSensing.entries.toList(),
                        selected = sensing,
                        label = {
                            when (it) {
                                MediaSensing.GAP -> "Gap"
                                MediaSensing.BLACK_MARK -> "Black mark"
                                MediaSensing.CONTINUOUS -> "Continuous"
                            }
                        },
                        onSelect = { sensing = it },
                    )
                    Text(
                        "How the printer finds the top of the next label. Set to look " +
                            "for a gap that is not there, it feeds forward hunting for " +
                            "one and stops with a paper fault. Nothing in the protocol " +
                            "can tell you which is right, which is why it is a setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (sensing != MediaSensing.CONTINUOUS) {
                        OutlinedTextField(
                            value = gapMm,
                            onValueChange = { gapMm = it.filter { c -> c.isDigit() || c == '.' } },
                            label = {
                                Text(
                                    if (sensing == MediaSensing.GAP) {
                                        "Gap height in mm"
                                    } else {
                                        "Black mark height in mm"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Stepper("Darkness", darkness, 0..15) { darkness = it }
                    Text(
                        "The most common adjustment there is. Too light and barcodes " +
                            "scan intermittently or not at all; too dark and thin bars " +
                            "bleed together and also stop scanning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Stepper("Speed (ips)", speed, 1..12) { speed = it }

                    TextButton(onClick = { onCalibrate(edited()) }) {
                        Text("Calibrate media sensor")
                    }
                    Text(
                        "Feeds a few labels while the printer works out where the " +
                            "gaps are. Worth doing when printing drifts onto the join " +
                            "between two labels.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Resolution", style = MaterialTheme.typography.labelMedium)
                ChipRow(
                    items = listOf(180, 203, 300, 600),
                    selected = dpi,
                    label = { "$it dpi" },
                    onSelect = { dpi = it },
                )

                OutlinedTextField(
                    value = widthDots,
                    onValueChange = { widthDots = it.filter(Char::isDigit).take(5) },
                    label = { Text("Print head width in dots") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "203 dpi heads: 384 for 58 mm, 576 for 80 mm, 812 for 4 inch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(onClick = { onTestPage(edited()) }) {
                    Text("Send test page with these settings")
                }

                // "It doesn't print" is not something anyone can act on. This
                // is: the dialect, the head width, the ink the rasteriser put
                // on the page, and the bytes that reached the printer.
                TextButton(onClick = onCopyReport) {
                    Text("Copy printer report")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A bounded integer with two buttons, for the handful of small numeric knobs. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = value > range.first,
        ) { Text("-") }
        OutlinedButton(
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}
