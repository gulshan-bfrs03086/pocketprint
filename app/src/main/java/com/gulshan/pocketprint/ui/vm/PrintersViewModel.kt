package com.gulshan.pocketprint.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gulshan.pocketprint.ServiceLocator
import com.gulshan.pocketprint.data.AppSettings
import com.gulshan.pocketprint.label.EscPos
import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.label.Zpl
import com.gulshan.pocketprint.model.ConnectionKind
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.JobState
import com.gulshan.pocketprint.model.PrintJobRecord
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.model.SourceDocument
import com.gulshan.pocketprint.print.JobListener
import com.gulshan.pocketprint.print.PrintForegroundService
import com.gulshan.pocketprint.print.PrinterAutoSetup
import com.gulshan.pocketprint.print.SetupProgress
import com.gulshan.pocketprint.render.Spool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveryState(
    val scanning: Boolean = false,
    val network: List<Printer> = emptyList(),
    val bluetooth: List<Printer> = emptyList(),
    val usb: List<Printer> = emptyList(),
    val message: String? = null,
) {
    val all: List<Printer> get() = network + bluetooth + usb
}

class PrintersViewModel(app: Application) : AndroidViewModel(app) {

    private val printerRepo = ServiceLocator.printerRepository(app)
    private val jobRepo = ServiceLocator.jobRepository(app)
    private val settingsRepo = ServiceLocator.settingsRepository(app)
    private val engine = ServiceLocator.printEngine(app)

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    private val _selectedDocument = MutableStateFlow<SourceDocument?>(null)
    val selectedDocument: StateFlow<SourceDocument?> = _selectedDocument.asStateFlow()

    private val _labelStatus = MutableStateFlow<String?>(null)
    val labelStatus: StateFlow<String?> = _labelStatus.asStateFlow()

    private val _options = MutableStateFlow(PrintOptions())
    val options: StateFlow<PrintOptions> = _options.asStateFlow()

    private var scanJob: Job? = null

    private val _setup = MutableStateFlow<SetupProgress?>(null)
    val setup: StateFlow<SetupProgress?> = _setup.asStateFlow()

    /** Label stock for auto-setup. Wrong size = the printer hunts for a gap and stalls. */
    private val _setupStock = MutableStateFlow(MediaSize.LABEL_4X6)
    val setupStock: StateFlow<MediaSize> = _setupStock.asStateFlow()

    val savedPrinters: StateFlow<List<Printer>> = printerRepo.saved
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val jobs: StateFlow<List<PrintJobRecord>> = jobRepo.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch { _options.value = settingsRepo.current().toPrintOptions() }
    }

    // ---- discovery ----------------------------------------------------------

    fun startScan() {
        scanJob?.cancel()
        _discovery.value = DiscoveryState(scanning = true)

        scanJob = viewModelScope.launch {
            val app = getApplication<Application>()

            // Bluetooth and USB resolve immediately from system state.
            val bt = ServiceLocator.bluetoothDiscovery(app)
            _discovery.value = _discovery.value.copy(
                bluetooth = bt.bondedDevices(),
                usb = ServiceLocator.usbDiscovery(app).attachedPrinters(),
                message = when {
                    !bt.isAvailable -> "No Bluetooth adapter on this device"
                    !bt.isEnabled -> "Bluetooth is off, so paired printers are hidden"
                    !bt.hasPermission() -> "Grant the Bluetooth permission to see paired printers"
                    else -> null
                },
            )

            // mDNS needs to sit on the network for a while to hear everything.
            withTimeoutOrNull(8_000) {
                ServiceLocator.mdnsDiscovery(app).discover()
                    .onEach { found ->
                        val current = _discovery.value.network
                        if (current.none { it.id == found.id }) {
                            _discovery.value =
                                _discovery.value.copy(network = mergeNetwork(current, found))
                        }
                    }
                    .launchIn(this)
            }

            // Ask each IPP printer what it can actually do.
            val probed = _discovery.value.network.map { engine.probe(it) }
            _discovery.value = _discovery.value.copy(network = probed, scanning = false)
        }
    }

    /**
     * Most AirPrint printers advertise the same queue over both _ipp._tcp and
     * _ipps._tcp. Listing both is confusing, and the TLS entry cannot currently
     * be used because printers present self-signed certificates that the device
     * trust store rejects, so a plain entry wins when the pair names the same
     * host and path.
     */
    private fun mergeNetwork(current: List<Printer>, found: Printer): List<Printer> {
        val incoming = found.address as? PrinterAddress.Ipp ?: return current + found

        val duplicate = current.firstOrNull { existing ->
            val a = existing.address as? PrinterAddress.Ipp ?: return@firstOrNull false
            a.host == incoming.host && a.path == incoming.path && a.secure != incoming.secure
        }

        return when {
            duplicate == null -> current + found
            incoming.secure -> current
            else -> current.filterNot { it.id == duplicate.id } + found
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _discovery.value = _discovery.value.copy(scanning = false)
    }

    fun refreshLocalPrinters() {
        val app = getApplication<Application>()
        _discovery.value = _discovery.value.copy(
            bluetooth = ServiceLocator.bluetoothDiscovery(app).bondedDevices(),
            usb = ServiceLocator.usbDiscovery(app).attachedPrinters(),
        )
    }

    // ---- printer management -------------------------------------------------

    fun savePrinter(printer: Printer) = viewModelScope.launch {
        // A USB printer is unusable until the user grants access, and the system
        // dialog only appears if we ask for it.
        if (printer.address is PrinterAddress.Usb) {
            ServiceLocator.usbDiscovery(getApplication()).requestPermission(printer)
        }
        val probed = engine.probe(printer)
        printerRepo.save(probed)
    }

    fun removePrinter(printerId: String) = viewModelScope.launch {
        printerRepo.remove(printerId)
    }

    fun updatePrinter(printer: Printer) = viewModelScope.launch { printerRepo.save(printer) }

    /**
     * Sends a minimal, self-describing page in the printer's configured dialect.
     * This is the fastest way to find out whether the guessed command language
     * is right: a correct guess prints a small labelled box, a wrong one prints
     * readable command text or nothing at all.
     */
    fun printTestPage(printer: Printer) = viewModelScope.launch {
        val caps = printer.capabilities
        val dpi = caps.resolutionsDpi.firstOrNull() ?: 203
        val media = caps.mediaSizes.firstOrNull() ?: MediaSize.LABEL_4X6
        val width = caps.rasterWidthDots ?: 576
        val language = caps.languages.firstOrNull() ?: PrintLanguage.ESC_POS

        val bytes = when (language) {
            PrintLanguage.TSPL -> Tspl(media, dpi).apply {
                setup(density = _options.value.density)
                text(20, 20, "POCKETPRINT TEST", font = "3")
                text(20, 70, "TSPL  ${media.label}", font = "2")
                text(20, 105, "${dpi} dpi  ${width} dots", font = "2")
                barcode(20, 145, "POCKETPRINT", height = 60)
                box(10, 10, media.dotsWide(dpi) - 10, media.dotsHigh(dpi) - 10, 3)
                print(sets = 1, copies = 1)
            }.build()

            PrintLanguage.ZPL -> Zpl(media, dpi).apply {
                start(density = _options.value.density)
                text(20, 20, "POCKETPRINT TEST", height = 36, width = 36)
                text(20, 70, "ZPL  ${media.label}", height = 26, width = 26)
                text(20, 105, "${dpi} dpi  ${width} dots", height = 26, width = 26)
                barcode128(20, 145, "POCKETPRINT", height = 70)
                end(copies = 1)
            }.build()

            else -> EscPos(width).apply {
                initialize()
                align(EscPos.Align.CENTER)
                textSize(2, 2); bold(true)
                line("POCKETPRINT")
                bold(false); textSize(1, 1)
                line("ESC/POS test page")
                line("${width} dots wide")
                separator('-', 32)
                align(EscPos.Align.LEFT)
                line("If this is readable, the")
                line("command language is correct.")
                feed(1)
                align(EscPos.Align.CENTER)
                barcode128("POCKETPRINT")
                cut()
            }.build()
        }

        printRawLabel(printer, bytes, "Test page").join()
    }

    fun addManualPrinter(
        name: String,
        host: String,
        port: Int,
        kind: ConnectionKind,
        path: String = "/ipp/print",
    ) = viewModelScope.launch {
        val address = when (kind) {
            ConnectionKind.RAW9100 -> PrinterAddress.Raw(host, port)
            else -> PrinterAddress.Ipp(host, port, path)
        }
        val id = when (address) {
            is PrinterAddress.Ipp -> "ipp:$host:$port$path"
            else -> "raw:$host:$port"
        }
        val printer = Printer(
            id = id,
            displayName = name.ifBlank { host },
            address = address,
            capabilities = if (kind == ConnectionKind.RAW9100) {
                PrinterCapabilities(
                    languages = listOf(com.gulshan.pocketprint.model.PrintLanguage.PCL),
                )
            } else {
                PrinterCapabilities.UNKNOWN_NETWORK
            },
            saved = true,
        )
        printerRepo.save(engine.probe(printer))
    }

    // ---- documents and printing ---------------------------------------------

    fun selectDocument(uri: Uri) = viewModelScope.launch {
        _selectedDocument.value = Spool.describe(getApplication(), uri)
    }

    fun setDocument(document: SourceDocument?) { _selectedDocument.value = document }

    /**
     * Shared plain text and links carry no content URI, so a link is kept as a
     * URL document and free text is spooled to a file the pipeline can open.
     * Previously both produced a document with an empty uri, which failed later
     * with a confusing "cannot open" error.
     */
    fun setSharedText(text: String, subject: String?) = viewModelScope.launch {
        val trimmed = text.trim()
        _selectedDocument.value = if (
            trimmed.startsWith("http://") || trimmed.startsWith("https://")
        ) {
            SourceDocument(
                uri = trimmed,
                displayName = subject?.takeIf { it.isNotBlank() } ?: trimmed,
                mimeType = SourceDocument.MIME_URL,
            )
        } else {
            val file = withContext(Dispatchers.IO) {
                Spool.newFile(getApplication(), ".txt").apply { writeText(trimmed) }
            }
            SourceDocument(
                uri = Uri.fromFile(file).toString(),
                displayName = subject?.takeIf { it.isNotBlank() } ?: "Shared text",
                mimeType = "text/plain",
                sizeBytes = file.length(),
            )
        }
    }

    fun updateOptions(transform: (PrintOptions) -> PrintOptions) {
        _options.value = transform(_options.value)
    }

    fun print(printer: Printer) = viewModelScope.launch {
        val document = _selectedDocument.value ?: return@launch

        if (printer.address is PrinterAddress.Usb) {
            val usb = ServiceLocator.usbDiscovery(getApplication())
            if (!usb.hasPermission(printer) && !usb.requestPermission(printer)) {
                _labelStatus.value = "USB access was denied for ${printer.displayName}"
                return@launch
            }
        }

        PrintForegroundService.start(
            getApplication(), printer.id, document, _options.value,
        )
    }

    /**
     * Sends prebuilt printer commands and reports the real outcome. The status
     * is published only after the transport has finished, so a printer that is
     * switched off no longer shows as a success.
     */
    fun printRawLabel(printer: Printer, bytes: ByteArray, name: String) = viewModelScope.launch {
        _labelStatus.value = "Sending ${bytes.size} bytes to ${printer.displayName}..."
        val jobId = java.util.UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        val result = engine.printRaw(
            printer, bytes, name, _options.value,
            JobListener(
                onStatus = { status -> _labelStatus.value = "${printer.displayName}: $status" },
            ),
        )

        _labelStatus.value = when (result) {
            is PrintResult.Completed ->
                "${printer.displayName} printed ${result.bytesSent} bytes"
            is PrintResult.Sent ->
                "Sent ${result.bytesSent} bytes. ${result.reason}"
            is PrintResult.Failure -> "Failed: ${result.message}"
        }

        jobRepo.upsert(
            PrintJobRecord(
                id = jobId,
                printerId = printer.id,
                printerName = printer.displayName,
                documentName = name,
                state = when (result) {
                    is PrintResult.Completed -> JobState.COMPLETED
                    is PrintResult.Sent -> JobState.SENT
                    is PrintResult.Failure -> JobState.FAILED
                },
                createdAtEpochMs = startedAt,
                finishedAtEpochMs = System.currentTimeMillis(),
                bytesSent = (result as? PrintResult.Delivered)?.bytesSent ?: 0L,
                error = (result as? PrintResult.Failure)?.message,
                note = (result as? PrintResult.Sent)?.reason,
            ),
        )
    }

    fun clearLabelStatus() { _labelStatus.value = null }

    /**
     * One-tap bring-up: pair, connect, probe for the command language, configure,
     * test, save. The printer is persisted only once the sequence finishes, so a
     * printer that could not be reached does not clutter the saved list.
     */
    fun startAutoSetup(printer: Printer, stock: MediaSize = _setupStock.value) =
        viewModelScope.launch {
        PrinterAutoSetup(getApplication()).run(printer, stock).collect { progress ->
            _setup.value = progress
            progress.printer?.let { printerRepo.save(it) }
        }
    }

    fun dismissSetup() { _setup.value = null }

    fun setSetupStock(media: MediaSize) { _setupStock.value = media }

    /** Paired Bluetooth devices are the only sensible auto-setup targets. */
    fun autoSetupCandidates(): List<Printer> =
        ServiceLocator.bluetoothDiscovery(getApplication()).bondedDevices()

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        settingsRepo.update(transform)
    }

    fun clearJobs() = viewModelScope.launch { jobRepo.clear() }
}
