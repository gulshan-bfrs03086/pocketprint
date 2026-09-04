package com.gulshan.pocketprint.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.ConnectionKind
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.ui.components.AutoSetupCard
import com.gulshan.pocketprint.ui.components.AutoSetupDialog
import com.gulshan.pocketprint.ui.components.AutoSetupPicker
import com.gulshan.pocketprint.ui.components.ChipRow
import com.gulshan.pocketprint.ui.components.InfoBanner
import com.gulshan.pocketprint.ui.components.PrinterSettingsDialog
import com.gulshan.pocketprint.ui.components.SectionHeader
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersScreen(viewModel: PrintersViewModel) {
    val context = LocalContext.current
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val saved by viewModel.savedPrinters.collectAsStateWithLifecycle()
    val document by viewModel.selectedDocument.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Printer?>(null) }
    var pickingForSetup by remember { mutableStateOf(false) }
    val setupProgress by viewModel.setup.collectAsStateWithLifecycle()
    val setupStock by viewModel.setupStock.collectAsStateWithLifecycle()

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.selectDocument(it) } }

    LaunchedEffect(Unit) { viewModel.refreshLocalPrinters() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            AutoSetupCard(
                candidateCount = discovery.bluetooth.size,
                stock = setupStock,
                onStockChange = { viewModel.setSetupStock(it) },
                onStart = {
                    val candidates = viewModel.autoSetupCandidates()
                    if (candidates.size == 1) {
                        viewModel.startAutoSetup(candidates.first())
                    } else {
                        pickingForSetup = true
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            SectionHeader("Document")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        document?.displayName ?: "No document selected",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    document?.let {
                        Text(
                            it.mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { pickDocument.launch(arrayOf("*/*")) }) {
                            Text("Choose file")
                        }
                        if (document != null) {
                            OutlinedButton(onClick = { viewModel.setDocument(null) }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Paper")
            ChipRow(
                items = MediaSize.ALL,
                selected = options.mediaSize,
                label = { it.label },
                onSelect = { size -> viewModel.updateOptions { it.copy(mediaSize = size) } },
            )
        }

        item {
            SectionHeader("Options")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {
                    viewModel.updateOptions {
                        it.copy(copies = (it.copies - 1).coerceAtLeast(1))
                    }
                }) { Text("-") }
                Text("${options.copies} ${if (options.copies == 1) "copy" else "copies"}")
                OutlinedButton(onClick = {
                    viewModel.updateOptions { it.copy(copies = it.copies + 1) }
                }) { Text("+") }
            }
            ChipRow(
                items = listOf(ColorMode.MONOCHROME, ColorMode.COLOR),
                selected = options.colorMode,
                label = { if (it == ColorMode.COLOR) "Colour" else "Black & white" },
                onSelect = { mode -> viewModel.updateOptions { it.copy(colorMode = mode) } },
                modifier = Modifier.padding(top = 8.dp),
            )
            ChipRow(
                items = listOf(150, 203, 300, 600),
                selected = options.dpi,
                label = { "$it dpi" },
                onSelect = { dpi -> viewModel.updateOptions { it.copy(dpi = dpi) } },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SAVED PRINTERS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add printer manually")
                }
            }
        }

        if (saved.isEmpty()) {
            item { InfoBanner("No saved printers yet. Scan below, or add one by IP address.") }
        }

        items(saved, key = { it.id }) { printer ->
            PrinterRow(
                printer = printer,
                trailing = {
                    Row {
                        IconButton(onClick = { editing = printer }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Printer settings")
                        }
                        IconButton(onClick = { viewModel.removePrinter(printer.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                },
                enabled = document != null,
                onClick = { viewModel.print(printer) },
                subtitleOverride = if (document == null) {
                    "Choose a document first"
                } else {
                    "Tap to print"
                },
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DISCOVERED",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (discovery.scanning) {
                    CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.startScan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Scan")
                    }
                }
            }
        }

        discovery.message?.let { item { InfoBanner(it) } }

        val discovered = discovery.all.filter { found -> saved.none { it.id == found.id } }
        if (discovered.isEmpty() && !discovery.scanning) {
            item {
                InfoBanner(
                    "Nothing found yet. Wi-Fi printers must be on the same network; " +
                        "Bluetooth printers must already be paired in Android Settings.",
                )
            }
        }

        items(discovered, key = { it.id }) { printer ->
            PrinterRow(
                printer = printer,
                trailing = {
                    TextButton(onClick = { viewModel.savePrinter(printer) }) { Text("Save") }
                },
                enabled = true,
                onClick = { viewModel.savePrinter(printer) },
            )
        }

        item {
            SectionHeader("System printing")
            InfoBanner(
                "To print from other apps, turn PocketPrint on under " +
                    "Settings > Connected devices > Printing. Saved printers appear " +
                    "in every app's print dialog, including Bluetooth ones.",
            )
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent("android.settings.ACTION_PRINT_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            ) { Text("Open print settings") }
        }
    }

    if (pickingForSetup) {
        AutoSetupPicker(
            candidates = viewModel.autoSetupCandidates(),
            onDismiss = { pickingForSetup = false },
            onPick = {
                pickingForSetup = false
                viewModel.startAutoSetup(it)
            },
        )
    }

    setupProgress?.let { progress ->
        AutoSetupDialog(
            progress = progress,
            onDismiss = { viewModel.dismissSetup() },
            onOutcome = { viewModel.recordTestLabelOutcome(it) },
            alternateDialect = progress.printer?.let { viewModel.alternateDialect(it)?.name },
            onRetestOtherDialect = { viewModel.retestWithOtherDialect() },
        )
    }

    editing?.let { target ->
        PrinterSettingsDialog(
            printer = target,
            onDismiss = { editing = null },
            onSave = {
                viewModel.updatePrinter(it)
                editing = null
            },
            onTestPage = { viewModel.printTestPage(it) },
        )
    }

    if (showAddDialog) {
        AddPrinterDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, host, port, kind ->
                viewModel.addManualPrinter(name, host, port, kind)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun PrinterRow(
    printer: Printer,
    trailing: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    subtitleOverride: String? = null,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (printer.kind) {
                    ConnectionKind.BLUETOOTH -> Icons.Filled.Bluetooth
                    ConnectionKind.USB -> Icons.Filled.Usb
                    else -> Icons.Filled.Wifi
                },
                contentDescription = printer.kind.name,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    printer.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleOverride ?: printer.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Worth showing, because for a Bluetooth or USB printer this is
                // the only evidence that exists that it prints at all.
                if (printer.testPrintConfirmed) {
                    Text(
                        "Confirmed by a test label",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32),
                        maxLines = 1,
                    )
                }
            }
            trailing()
        }
    }
}

@Composable
private fun AddPrinterDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, host: String, port: Int, kind: ConnectionKind) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ConnectionKind.IPP) }
    var port by remember { mutableStateOf("631") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add printer by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP address or hostname") },
                    singleLine = true,
                )
                ChipRow(
                    items = listOf(ConnectionKind.IPP, ConnectionKind.RAW9100),
                    selected = kind,
                    label = { if (it == ConnectionKind.IPP) "IPP / AirPrint" else "Raw 9100" },
                    onSelect = {
                        kind = it
                        port = if (it == ConnectionKind.IPP) "631" else "9100"
                    },
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && port.isNotBlank(),
                onClick = { onAdd(name, host.trim(), port.toIntOrNull() ?: 631, kind) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
