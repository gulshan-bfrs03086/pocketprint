package com.gulshan.pocketprint.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.ConnectionKind
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.discovery.CompanionPairing
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.permissions.AppHealth
import com.gulshan.pocketprint.permissions.AppPermissions
import com.gulshan.pocketprint.permissions.PermissionStatus
import com.gulshan.pocketprint.permissions.PrintServiceState
import com.gulshan.pocketprint.permissions.rememberPermissionRequester
import com.gulshan.pocketprint.ui.enumSaver
import com.gulshan.pocketprint.ui.components.AutoSetupCard
import com.gulshan.pocketprint.ui.components.AutoSetupDialog
import com.gulshan.pocketprint.ui.components.AutoSetupPicker
import com.gulshan.pocketprint.ui.components.ChipRow
import com.gulshan.pocketprint.ui.components.InfoBanner
import com.gulshan.pocketprint.ui.components.PrintPreviewDialog
import com.gulshan.pocketprint.ui.components.PrinterSettingsDialog
import com.gulshan.pocketprint.ui.components.SectionHeader
import com.gulshan.pocketprint.ui.components.WarningBanner
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintersScreen(viewModel: PrintersViewModel) {
    val context = LocalContext.current
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val saved by viewModel.savedPrinters.collectAsStateWithLifecycle()
    val document by viewModel.selectedDocument.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    // By id, like the label screen: a Printer is not Parcelable, and resolving
    // it from the saved list means an open settings dialog closes cleanly if
    // that printer is deleted rather than editing a ghost.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = saved.firstOrNull { it.id == editingId }
    var pickingForSetup by rememberSaveable { mutableStateOf(false) }
    val setupProgress by viewModel.setup.collectAsStateWithLifecycle()
    val storageProblems by viewModel.storageProblems.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // Asked for where they are used, not on the way in. onAsked is what lets
    // the app later tell "never asked" from "refused for good".
    val permissions = rememberPermissionRequester(onAsked = { viewModel.rememberAsked(it) })

    // Permission state and the print-service switch both change outside this
    // app, in Settings, so they are re-read whenever the screen comes back.
    var systemEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activity = context as? Activity
    val bluetoothStatus = remember(systemEpoch, settings.askedForBluetooth) {
        activity?.let {
            AppPermissions.status(it, AppPermissions.bluetooth, settings.askedForBluetooth)
        } ?: PermissionStatus.ASKABLE
    }
    val printServiceStatus = remember(systemEpoch) { PrintServiceState.status(context) }
    val hibernation = remember(systemEpoch) { AppHealth.hibernation(context) }
    val setupStock by viewModel.setupStock.collectAsStateWithLifecycle()

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.selectDocument(it) } }

    // The system picker hands back an IntentSender rather than an Intent, so it
    // is launched through the sender contract; the device comes back in the
    // result data.
    val pairLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        CompanionPairing.printerFrom(result.data)?.let { printer ->
            pickingForSetup = false
            viewModel.adoptPairedPrinter(printer)
        }
    }

    fun pairNewPrinter() {
        CompanionPairing.request(
            context = context,
            onPicker = { sender ->
                runCatching {
                    pairLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            },
            onUnavailable = { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
        )
    }

    LaunchedEffect(Unit) { viewModel.refreshLocalPrinters() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(storageProblems.entries.toList(), key = { it.key }) { problem ->
            WarningBanner(problem.value, Modifier.padding(top = 16.dp))
        }

        // Nobody can be warned about this when it happens, because when it
        // happens they are not here - the whole point of the app is that it
        // stops being opened. So it is said whenever they do open it.
        if (hibernation == AppHealth.Hibernation.WILL_HIBERNATE) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    WarningBanner(
                        stringResource(R.string.hibernation_warning),
                    )
                    Button(
                        onClick = { AppHealth.openHibernationSettings(context) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.action_open_app_info)) }
                }
            }
        }

        // The state the old first-frame request left people in, with no way to
        // find out and no way out. Android will not ask again after two
        // refusals; app settings is the only route.
        if (bluetoothStatus == PermissionStatus.BLOCKED) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    WarningBanner(
                        stringResource(R.string.bluetooth_blocked_warning),
                    )
                    Button(
                        onClick = { AppPermissions.openAppSettings(context) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.action_open_app_settings)) }
                }
            }
        }

        item {
            AutoSetupCard(
                candidateCount = discovery.bluetooth.size,
                stock = setupStock,
                onStockChange = { viewModel.setSetupStock(it) },
                onStart = {
                    // The dialog now arrives immediately after somebody taps
                    // "set up my printer", which is a dialog about the thing
                    // they just asked for rather than about nothing.
                    permissions.ensure(AppPermissions.bluetooth) { granted ->
                        if (!granted) return@ensure
                        val candidates = viewModel.autoSetupCandidates()
                        if (candidates.size == 1) {
                            viewModel.startAutoSetup(candidates.first())
                        } else {
                            pickingForSetup = true
                        }
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            SectionHeader(stringResource(R.string.printers_document))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        document?.displayName ?: stringResource(R.string.printers_no_document),
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
                            Text(stringResource(R.string.printers_choose_file))
                        }
                        if (document != null) {
                            OutlinedButton(onClick = { viewModel.setDocument(null) }) {
                                Text(stringResource(R.string.action_clear))
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.printers_paper))
            ChipRow(
                items = MediaSize.ALL,
                selected = options.mediaSize,
                label = { it.label },
                onSelect = { size -> viewModel.updateOptions { it.copy(mediaSize = size) } },
            )
        }

        item {
            SectionHeader(stringResource(R.string.printers_options))
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
                Text(
                    pluralStringResource(
                        R.plurals.printers_copies,
                        options.copies,
                        options.copies,
                    ),
                )
                OutlinedButton(onClick = {
                    viewModel.updateOptions { it.copy(copies = it.copies + 1) }
                }) { Text("+") }
            }
            ChipRow(
                items = listOf(ColorMode.MONOCHROME, ColorMode.COLOR),
                selected = options.colorMode,
                label = {
                    context.getString(
                        if (it == ColorMode.COLOR) {
                            R.string.printers_colour
                        } else {
                            R.string.printers_mono
                        },
                    )
                },
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
                    stringResource(R.string.printers_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.printers_add_manually))
                }
            }
        }

        if (saved.isEmpty()) {
            item { InfoBanner(stringResource(R.string.printers_none_saved)) }
        }

        items(saved, key = { it.id }) { printer ->
            PrinterRow(
                printer = printer,
                trailing = {
                    Row {
                        // Tapping the row prints, which on a thermal printer
                        // consumes a label. Looking first should be one tap
                        // away from that, not buried.
                        IconButton(
                            onClick = { viewModel.previewOn(printer) },
                            enabled = document != null,
                        ) {
                            Icon(Icons.Filled.Visibility, contentDescription = stringResource(R.string.printers_preview))
                        }
                        IconButton(onClick = { editingId = printer.id }) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.printers_settings))
                        }
                        IconButton(onClick = { viewModel.removePrinter(printer.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.printers_remove))
                        }
                    }
                },
                enabled = document != null,
                onClick = {
                    // Asked for here because this is when a job is about to run
                    // in the background, and refused or not the job still goes:
                    // the notification is how progress and Cancel are shown, not
                    // something printing depends on.
                    permissions.ensure(AppPermissions.notifications) {
                        viewModel.print(printer)
                    }
                },
                subtitleOverride = if (document == null) {
                    stringResource(R.string.printers_choose_document_first)
                } else {
                    stringResource(R.string.printers_tap_to_print)
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
                    stringResource(R.string.printers_discovered),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (discovery.scanning) {
                    CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = {
                            permissions.ensure(AppPermissions.bluetooth) { granted ->
                                if (granted) viewModel.startScan()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.printers_scan))
                    }
                }
            }
        }

        discovery.message?.let { item { InfoBanner(it) } }

        val discovered = discovery.all.filter { found -> saved.none { it.id == found.id } }
        if (discovered.isEmpty() && !discovery.scanning) {
            item {
                InfoBanner(
                    stringResource(R.string.printers_nothing_found),
                )
            }
        }

        items(discovered, key = { it.id }) { printer ->
            PrinterRow(
                printer = printer,
                trailing = {
                    TextButton(onClick = { viewModel.savePrinter(printer) }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                enabled = true,
                onClick = { viewModel.savePrinter(printer) },
            )
        }

        item {
            SectionHeader(stringResource(R.string.printers_system_printing))
            when (printServiceStatus) {
                PrintServiceState.Status.ENABLED -> InfoBanner(
                    stringResource(R.string.print_service_enabled),
                )
                PrintServiceState.Status.DISABLED -> WarningBanner(
                    stringResource(R.string.print_service_disabled),
                )
                // Reading that switch is not permitted on every Android
                // version, and claiming it is off when it might be on would
                // send people to fix something already working.
                PrintServiceState.Status.UNKNOWN -> InfoBanner(
                    stringResource(R.string.print_service_unknown),
                )
            }
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
            ) { Text(stringResource(R.string.action_open_print_settings)) }
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
            onPairNew = if (CompanionPairing.isSupported(context)) {
                { pairNewPrinter() }
            } else {
                null
            },
        )
    }

    preview?.let { state ->
        PrintPreviewDialog(state = state, onDismiss = { viewModel.dismissPreview() })
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
            onDismiss = { editingId = null },
            onSave = {
                viewModel.updatePrinter(it)
                editingId = null
            },
            onTestPage = { viewModel.printTestPage(it) },
            onCalibrate = {
                viewModel.updatePrinter(it)
                viewModel.calibrate(it)
                editingId = null
            },
            onCopyReport = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(
                        context.getString(R.string.report_clip_label),
                        viewModel.printerReport(target),
                    ),
                )
                // From Android 13 the system shows its own copy confirmation,
                // and a Toast on top of it is just clutter.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(
                        context,
                        R.string.report_copied,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
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
                        stringResource(R.string.printers_confirmed),
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
    // For the ChipRow label, which is a plain lambda rather than a composable.
    val context = LocalContext.current

    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable(stateSaver = enumSaver(ConnectionKind.entries.toList())) {
        mutableStateOf(ConnectionKind.IPP)
    }
    var port by rememberSaveable { mutableStateOf("631") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_printer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.add_printer_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.add_printer_host)) },
                    singleLine = true,
                )
                ChipRow(
                    items = listOf(ConnectionKind.IPP, ConnectionKind.RAW9100),
                    selected = kind,
                    label = {
                        context.getString(
                            if (it == ConnectionKind.IPP) {
                                R.string.add_printer_ipp
                            } else {
                                R.string.add_printer_raw
                            },
                        )
                    },
                    onSelect = {
                        kind = it
                        port = if (it == ConnectionKind.IPP) "631" else "9100"
                    },
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.add_printer_port)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && port.isNotBlank(),
                onClick = { onAdd(name, host.trim(), port.toIntOrNull() ?: 631, kind) },
            ) { Text(stringResource(R.string.add_printer_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
