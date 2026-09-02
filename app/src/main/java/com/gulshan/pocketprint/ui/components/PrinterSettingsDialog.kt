package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer

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
) {
    var name by remember { mutableStateOf(printer.displayName) }
    var language by remember {
        mutableStateOf(printer.capabilities.languages.firstOrNull() ?: PrintLanguage.ESC_POS)
    }
    var media by remember {
        mutableStateOf(printer.capabilities.mediaSizes.firstOrNull() ?: MediaSize.LABEL_4X6)
    }
    var dpi by remember {
        mutableStateOf(printer.capabilities.resolutionsDpi.firstOrNull() ?: 203)
    }
    var widthDots by remember {
        mutableStateOf((printer.capabilities.rasterWidthDots ?: 576).toString())
    }
    var exposeToSystem by remember { mutableStateOf(printer.exposeToSystem) }

    fun edited(): Printer = printer.copy(
        displayName = name.ifBlank { printer.displayName },
        exposeToSystem = exposeToSystem,
        capabilities = printer.capabilities.copy(
            languages = listOf(language),
            mediaSizes = listOf(media) +
                printer.capabilities.mediaSizes.filterNot { it.id == media.id },
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
                    items = MediaSize.ALL,
                    selected = media,
                    label = { it.label },
                    onSelect = { media = it },
                )

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
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
