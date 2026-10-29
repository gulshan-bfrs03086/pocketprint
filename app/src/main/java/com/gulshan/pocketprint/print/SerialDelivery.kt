package com.gulshan.pocketprint.print

import android.util.Log
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.transport.PrinterTransport
import com.gulshan.pocketprint.transport.guardAgainstStall
import kotlinx.coroutines.delay
import java.io.InputStream

private const val TAG = "SerialDelivery"

/** The longest to wait for a printer to finish a job: ten minutes. */
internal const val TSPL_WAIT_CAP_MS = 10 * 60_000L

/** What came of writing a job down a serial transport and, for TSPL, waiting on it. */
internal class SerialOutcome(
    val sent: Long,
    /** Null when the printer was not asked: not TSPL, or one found not to answer. */
    val ending: Ending?,
    val answeredBefore: Boolean,
)

/**
 * Whether a job in [language] to the printer known as [printerKey] is worth
 * asking status questions about at all.
 *
 * Only TSPL has a query that says whether a job is done, and a printer already
 * found not to answer is remembered, so it costs one job and not every job.
 */
internal fun asksStatus(language: PrintLanguage?, printerKey: String): Boolean =
    language == PrintLanguage.TSPL && TsplStatusMemory.answers(printerKey)

/**
 * Writes [source] to an opened [transport] and, when the printer can say, waits
 * for it to finish - the one path every non-IPP job takes, whether it was
 * rendered from a document or built by the label designer, the test page or
 * calibration. They used to differ: a raw job was written and left Sent-only,
 * with no paper-empty, jam or head-open report.
 *
 * Closing the transport is what unblocks a write stuck in a syscall, for a stall
 * and for a cancellation alike; cancelling the coroutine on its own would not.
 * The status questions are writes too, to the same blocking socket, so they sit
 * under the same guard.
 *
 * Drains the transport before returning unless the printer has already said it
 * finished or reported a fault, in which case there is nothing in flight left to
 * wait for.
 */
internal suspend fun deliverSerial(
    transport: PrinterTransport,
    printerKey: String,
    language: PrintLanguage?,
    source: InputStream,
    stallMs: Long,
    onProgress: (written: Long) -> Unit = {},
    onBusy: (waitedMs: Long) -> Unit = {},
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    pause: suspend (Long) -> Unit = { delay(it) },
): SerialOutcome {
    val asks = asksStatus(language, printerKey)

    // Asked while the printer is idle: once the job is in it may stay silent for
    // minutes, and silence is all there would be to go on.
    val answeredBefore = asks && answeredBeforeJob(transport, printerKey, stallMs)

    val sent = guardAgainstStall(
        stallMs = stallMs,
        unblock = { runCatching { transport.close() } },
    ) { heartbeat ->
        transport.write(source) { written ->
            heartbeat()
            onProgress(written)
        }
    }

    // A TSPL printer says when it is busy, and that is the nearest thing to
    // "done" a serial printer has. Waiting for it also stops a second press of
    // Print queueing a second copy behind a first that is still on its way.
    val ending = if (asks) {
        waitForTsplEnd(transport, printerKey, sent, answeredBefore, stallMs, onBusy, now, pause)
    } else {
        null
    }
    if (ending !is Ending.Finished && ending !is Ending.Fault) transport.finish()
    return SerialOutcome(sent, ending, answeredBefore)
}

/**
 * Waits for a TSPL printer to finish what it was just sent, by asking it.
 *
 * Never throws except to cancel: a printer that drops the link while being
 * asked has told us nothing, and the job is still reported as sent.
 */
private suspend fun waitForTsplEnd(
    transport: PrinterTransport,
    printerKey: String,
    bytesSent: Long,
    answeredBefore: Boolean,
    stallMs: Long,
    onBusy: (waitedMs: Long) -> Unit,
    now: () -> Long,
    pause: suspend (Long) -> Unit,
): Ending {
    // Generous: assume it takes in 100 bytes a second, about half what was
    // measured, plus half a minute. A job that is still busy after that is
    // reported as such and not as lost.
    val allowed = (bytesSent * 10L + 30_000L).coerceAtMost(TSPL_WAIT_CAP_MS)

    val ending = try {
        // The whole wait is one guarded body, beaten on every question written.
        // Without the guard a query's write to a printer that has stopped
        // reading blocks for good, and cancelling the job could not reach it.
        guardAgainstStall(
            stallMs = stallMs,
            unblock = { runCatching { transport.close() } },
        ) { heartbeat ->
            awaitTsplEnd(
                ask = { askTsplStatus(transport, heartbeat)?.also { TsplStatusMemory.markAnswered(printerKey) } },
                maxWaitMs = allowed,
                now = now,
                pause = pause,
                onBusy = onBusy,
                answeredBefore = answeredBefore,
            )
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (t: Exception) {
        // The link went, or a question could not be written. Not remembered
        // against the printer: it says nothing about whether it answers.
        Log.i(TAG, "status wait ended early: ${t.message}")
        Ending.LinkLost
    }
    if (ending is Ending.NoAnswer) TsplStatusMemory.markSilent(printerKey)
    return ending
}

/**
 * Asks a TSPL printer for its status once, before the job is written, and says
 * whether it answered.
 *
 * An idle printer answers within ~150 ms; one already taking a job in does not,
 * and then cannot be told apart from one that never answers. So the answer
 * that matters is taken while there is still nothing queued ahead of the
 * question. Only *that* it answered is used: a fault bit here is left for the
 * wait to report, which asks again once the job is out and gives a failure the
 * same words whenever it is found.
 *
 * Never throws except to cancel. A link that is already dead is the write's to
 * report, with its own message, a moment later.
 */
private suspend fun answeredBeforeJob(
    transport: PrinterTransport,
    printerKey: String,
    stallMs: Long,
): Boolean = try {
    val answered = guardAgainstStall(
        stallMs = stallMs,
        unblock = { runCatching { transport.close() } },
    ) { heartbeat -> askTsplStatus(transport, heartbeat) != null }
    if (answered) TsplStatusMemory.markAnswered(printerKey)
    answered
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (t: Exception) {
    Log.i(TAG, "status before the job failed: ${t.message}")
    false
}

/** ESC ! ?, and the one byte that comes back; null if the printer says nothing. */
private suspend fun askTsplStatus(transport: PrinterTransport, heartbeat: () -> Unit): Int? {
    // Whatever an earlier query's answer left behind would be read as this one's.
    transport.readAvailable(20)
    transport.write(TsplStatus.QUERY)
    heartbeat()
    return transport.readAvailable(700).firstOrNull()?.toInt()?.and(0xFF)
}
