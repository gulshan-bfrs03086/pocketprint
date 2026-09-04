package com.gulshan.pocketprint.print

import android.content.Context
import android.util.Log
import com.gulshan.pocketprint.label.EscPos
import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.label.Zpl
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import com.gulshan.pocketprint.transport.PrinterTransport
import com.gulshan.pocketprint.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class StepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class SetupStep(
    val id: String,
    val label: String,
    val state: StepState = StepState.PENDING,
    val detail: String? = null,
)

/**
 * What the person holding the printer saw after the test label.
 *
 * The three answers point at three different faults, and telling them apart is
 * the whole value of asking. Nothing at all is almost always the media - a
 * thermal printer marks only heat-sensitive stock, on one side, and reports
 * paper loaded and no error while feeding a blank label. Readable command text
 * or stray characters is the dialect. A correct label is the only confirmation
 * these transports can ever produce.
 */
enum class TestLabelOutcome {
    /** The label came out and looks right. */
    CORRECT,

    /** Something printed, but it is command text or garbage. */
    GARBLED,

    /** The label fed through blank, or nothing happened at all. */
    NOTHING,
}

data class SetupProgress(
    val steps: List<SetupStep>,
    val finished: Boolean = false,
    val printer: Printer? = null,
    val error: String? = null,
)

/**
 * One-tap bring-up for a thermal label or receipt printer.
 *
 * The awkward part of setting one of these up is that nothing about a Bluetooth
 * printer reliably says which command dialect it speaks. Rather than guessing
 * from the advertised name, this asks the printer directly: TSPL and ZPL both
 * have host-identification commands that return a model string, and a printer
 * that answers one of them has identified itself definitively.
 */
class PrinterAutoSetup(private val context: Context) {

    companion object {
        private const val TAG = "PrinterAutoSetup"

        /**
         * TSPL real-time status: one byte back (00 ready, 01 head open,
         * 04 out of paper). Answered only by a live TSPL interpreter.
         */
        private val PROBE_TSPL_STATUS = byteArrayOf(0x1B, 0x21, 0x3F) // ESC ! ?

        /**
         * ZPL host status: three STX/ETX framed lines. Answered only by a live
         * ZPL interpreter.
         */
        private val PROBE_ZPL_STATUS = byteArrayOf(0x7E, 0x48, 0x53, 0x0D, 0x0A) // ~HS CR LF

        /** TSPL host identification: returns the model name, e.g. "4B-2044PA". */
        private val PROBE_TSPL = byteArrayOf(0x7E, 0x21, 0x54, 0x0D, 0x0A) // ~!T CR LF

        /** ZPL host identification: returns "model,version,dpm,memory,...". */
        private val PROBE_ZPL = byteArrayOf(0x7E, 0x48, 0x49, 0x0D, 0x0A) // ~HI CR LF

        private const val STX = 0x02.toByte()

        /** Long enough for a status reply over RFCOMM without stalling setup. */
        private const val STATUS_WAIT_MS = 900L

        private const val STEP_CONNECT = "connect"
        private const val STEP_DETECT = "detect"
        private const val STEP_CONFIGURE = "configure"
        private const val STEP_SAVE = "save"

        /** Public because the UI only asks "did it print?" if this step ran. */
        const val STEP_TEST = "test"

        private const val ASSUMED_DPI = 203
    }

    private fun initialSteps() = listOf(
        SetupStep(STEP_CONNECT, "Pair and connect"),
        SetupStep(STEP_DETECT, "Ask the printer what it speaks"),
        SetupStep(STEP_CONFIGURE, "Work out label size and head width"),
        SetupStep(STEP_TEST, "Print a test label"),
        SetupStep(STEP_SAVE, "Save and offer to Android"),
    )

    /**
     * Runs the whole sequence, emitting after every state change so the UI can
     * follow along. This never throws: a failure lands in the emitted progress
     * with the step that broke and why.
     */
    fun run(
        printer: Printer,
        stock: MediaSize? = null,
        printTestLabel: Boolean = true,
    ): Flow<SetupProgress> = flow {
        var steps = initialSteps()

        suspend fun push(
            finished: Boolean = false,
            result: Printer? = null,
            error: String? = null,
        ) = emit(SetupProgress(steps, finished, result, error))

        fun mark(id: String, state: StepState, detail: String? = null) {
            steps = steps.map {
                if (it.id == id) it.copy(state = state, detail = detail ?: it.detail) else it
            }
        }

        fun skipRest(afterId: String) {
            var seen = false
            steps = steps.map {
                when {
                    it.id == afterId -> { seen = true; it }
                    seen && it.state == StepState.PENDING -> it.copy(state = StepState.SKIPPED)
                    else -> it
                }
            }
        }

        push()

        // Connecting also drives pairing when the printer is not yet bonded.
        mark(STEP_CONNECT, StepState.RUNNING)
        push()

        var transport: PrinterTransport? = null
        try {
            transport = TransportFactory.create(context, printer)
            transport.open()
            mark(STEP_CONNECT, StepState.DONE, transport.description)
            push()
        } catch (t: Throwable) {
            runCatching { transport?.close() }
            mark(STEP_CONNECT, StepState.FAILED, t.message)
            skipRest(STEP_CONNECT)
            push(finished = true, error = t.message)
            return@flow
        }

        val open = transport

        try {
            mark(STEP_DETECT, StepState.RUNNING)
            push()

            val detected = runCatching { detectLanguage(open) }
                .onFailure { Log.w(TAG, "probe failed", it) }
                .getOrNull()

            val language = detected?.language ?: guessFromName(printer.displayName)
            mark(
                STEP_DETECT,
                StepState.DONE,
                if (detected != null) {
                    "${label(language)} confirmed by the printer" +
                        (detected.model?.let { " ($it)" } ?: "")
                } else {
                    "No reply, so ${label(language)} assumed from the name"
                },
            )
            push()

            mark(STEP_CONFIGURE, StepState.RUNNING)
            push()

            // The caller's choice wins: nothing in TSPL or ZPL reports what
            // stock is actually loaded, and a SIZE larger than the real label
            // makes the printer feed forward hunting for a gap that is not
            // there, then stop responding with a paper-out fault.
            val media = stock ?: defaultMediaFor(language, printer.displayName)
            val widthDots = media.dotsWide(ASSUMED_DPI)
            val configured = printer.copy(
                makeAndModel = detected?.model ?: printer.makeAndModel,
                exposeToSystem = true,
                saved = true,
                capabilities = PrinterCapabilities(
                    languages = listOf(language),
                    mediaSizes = listOf(media) +
                        stockFor(language).filterNot { it.id == media.id },
                    resolutionsDpi = listOf(ASSUMED_DPI),
                    supportsColor = false,
                    supportsDuplex = false,
                    rasterWidthDots = widthDots,
                ),
            )
            mark(
                STEP_CONFIGURE,
                StepState.DONE,
                "${media.label}, $ASSUMED_DPI dpi, $widthDots dots wide",
            )
            push()

            if (printTestLabel) {
                mark(STEP_TEST, StepState.RUNNING)
                push()
                try {
                    open.write(testPage(configured, language, media, widthDots))
                    // Explicit, rather than relying on the flow emissions below
                    // to happen to delay the close long enough.
                    open.finish()
                    mark(STEP_TEST, StepState.DONE, "Sent to the printer")
                } catch (t: Throwable) {
                    // A failed test page is worth reporting but does not
                    // invalidate the configuration we just worked out.
                    mark(STEP_TEST, StepState.FAILED, t.message)
                }
            } else {
                mark(STEP_TEST, StepState.SKIPPED)
            }
            push()

            mark(STEP_SAVE, StepState.RUNNING)
            push()
            mark(STEP_SAVE, StepState.DONE, "Now offered in every app's print dialog")
            push(finished = true, result = configured)
        } finally {
            runCatching { open.close() }
        }
    }.flowOn(Dispatchers.IO)

    private data class Detection(val language: PrintLanguage, val model: String?)

    /**
     * Works out which command language the printer is listening in.
     *
     * Identification is not enough. A dual-emulation printer answers the
     * identification queries of both languages while running only one of them,
     * so a ~!T reply says the printer exists, not that TSPL is live. Choosing
     * from that gives a printer that accepts every job and prints nothing: the
     * live interpreter discards commands it cannot parse and feeds the stock.
     *
     * Confirmed on a 4BARCODE 4B-2044PA, which answered ~!T with "4B-2044PA"
     * and ~!A with its memory sizes, stayed silent to the TSPL status command,
     * returned a full ZPL status, and then printed the ZPL test label and not
     * the TSPL one.
     *
     * So ask for status first, since only the running interpreter answers its
     * own, and keep identification for naming the model and as a fallback.
     * TSPL is checked first so that a printer answering both keeps the
     * behaviour it had before.
     *
     * A printer that understands nothing here prints a couple of stray
     * characters on one label, which is far cheaper than a mis-detected one.
     */
    private suspend fun detectLanguage(transport: PrinterTransport): Detection? {
        // Drain anything volunteered on connect so it is not read as a reply.
        runCatching { transport.readAvailable(150) }

        transport.write(PROBE_TSPL_STATUS)
        if (transport.readAvailable(STATUS_WAIT_MS).isNotEmpty()) {
            val model = identify(transport, PROBE_TSPL)
            Log.i(TAG, "TSPL interpreter is live (model=$model)")
            return Detection(PrintLanguage.TSPL, model)
        }

        transport.write(PROBE_ZPL_STATUS)
        if (transport.readAvailable(STATUS_WAIT_MS).any { it == STX }) {
            // ~HI answers "model,version,dpm,memory"; keep the model only.
            val model = identify(transport, PROBE_ZPL)?.substringBefore(',')
            Log.i(TAG, "ZPL interpreter is live (model=$model)")
            return Detection(PrintLanguage.ZPL, model)
        }

        // Neither status command answered. Fall back to identification, which
        // at least proves the printer understands one of the two families.
        identify(transport, PROBE_TSPL)?.let {
            Log.i(TAG, "no status reply; TSPL identified: $it")
            return Detection(PrintLanguage.TSPL, it)
        }
        identify(transport, PROBE_ZPL)?.let {
            Log.i(TAG, "no status reply; ZPL identified: $it")
            return Detection(PrintLanguage.ZPL, it.substringBefore(','))
        }

        Log.i(TAG, "no reply to any probe")
        return null
    }

    private suspend fun identify(transport: PrinterTransport, probe: ByteArray): String? {
        transport.write(probe)
        return readableReply(transport)
    }

    private suspend fun readableReply(transport: PrinterTransport): String? {
        val raw = transport.readAvailable(900)
        if (raw.isEmpty()) return null
        val text = String(raw, Charsets.US_ASCII).trim()
        if (text.isEmpty()) return null
        // Line noise is not an answer: require mostly printable characters.
        val printable = text.count { it.code in 32..126 }
        if (printable * 2 < text.length) return null
        return text.take(64)
    }

    private fun guessFromName(name: String): PrintLanguage {
        val n = name.lowercase()
        return when {
            n.contains("tsc") || n.contains("te2") || n.contains("ttp") ||
                n.startsWith("4b-") || n.contains("4barcode") || n.contains("argox") ->
                PrintLanguage.TSPL
            n.contains("zebra") || n.startsWith("zq") || n.startsWith("zd") ||
                n.startsWith("gk") || n.startsWith("gx") -> PrintLanguage.ZPL
            else -> PrintLanguage.ESC_POS
        }
    }

    private fun label(language: PrintLanguage) = when (language) {
        PrintLanguage.TSPL -> "TSPL"
        PrintLanguage.ZPL -> "ZPL"
        PrintLanguage.ESC_POS -> "ESC/POS"
        else -> language.name
    }

    private fun defaultMediaFor(language: PrintLanguage, name: String): MediaSize = when {
        language == PrintLanguage.ESC_POS ->
            if (name.contains("58")) MediaSize.RECEIPT_58 else MediaSize.RECEIPT_80
        // 4-inch stock is the common case for TSPL and ZPL desktop units.
        else -> MediaSize.LABEL_4X6
    }

    private fun stockFor(language: PrintLanguage): List<MediaSize> =
        if (language == PrintLanguage.ESC_POS) {
            listOf(MediaSize.RECEIPT_80, MediaSize.RECEIPT_58)
        } else {
            listOf(MediaSize.LABEL_4X6, MediaSize.LABEL_100X150, MediaSize.LABEL_100X50)
        }

    private fun testPage(
        printer: Printer,
        language: PrintLanguage,
        media: MediaSize,
        widthDots: Int,
    ): ByteArray = when (language) {
        PrintLanguage.TSPL -> Tspl(media, ASSUMED_DPI).apply {
            setup()
            text(20, 20, "POCKETPRINT READY", font = "3")
            text(20, 70, printer.displayName.take(28), font = "2")
            text(20, 105, "TSPL  " + media.label, font = "2")
            barcode(20, 145, "POCKETPRINT", height = 60)
            print(sets = 1, copies = 1)
        }.build()

        PrintLanguage.ZPL -> Zpl(media, ASSUMED_DPI).apply {
            start()
            text(20, 20, "POCKETPRINT READY", height = 36, width = 36)
            text(20, 70, printer.displayName.take(28), height = 26, width = 26)
            text(20, 105, "ZPL  " + media.label, height = 26, width = 26)
            barcode128(20, 150, "POCKETPRINT", height = 70)
            end(copies = 1)
        }.build()

        else -> EscPos(widthDots).apply {
            initialize()
            align(EscPos.Align.CENTER)
            textSize(2, 2)
            bold(true)
            line("POCKETPRINT")
            bold(false)
            textSize(1, 1)
            line("Ready")
            line(printer.displayName.take(30))
            separator('-', 32)
            barcode128("POCKETPRINT")
            cut()
        }.build()
    }
}

/** Auto-setup only makes sense for the Bluetooth thermal printers. */
fun Printer.supportsAutoSetup(): Boolean = address is PrinterAddress.Bluetooth
