package com.gulshan.pocketprint.print

import java.util.concurrent.ConcurrentHashMap

/**
 * The one thing a TSPL printer will tell you about a job: whether it is busy.
 *
 * ## Why this exists
 *
 * Every Bluetooth job used to end the moment the last byte was handed to the
 * radio, and be reported as sent. On an RE310 that is minutes before it is
 * true. Measured with non-printing payloads: the printer takes in about 190
 * bytes a second, Bluetooth buffers the whole job in about one second, and the
 * label comes out when the printer has worked through it. The app said "sent",
 * the system print service said "completed", and the person pressed Print
 * again - queueing another few minutes behind the first.
 *
 * ## What the printer says
 *
 * TSC's real-time status command, ESC ! ?, is answered with one byte. Observed
 * on this printer around a job:
 *
 *  - before any job: `00` within ~150 ms, every time;
 *  - right after the bytes are sent: one early `00`;
 *  - then nothing at all, for as long as it is taking the job in - it ignores
 *    this query and the model-name query alike;
 *  - when it has the job: `20` (printing), then `00` (ready) again.
 *
 * So a status that goes silent and then answers ready is a printer saying it
 * has finished with what it was given, which is about as much as any serial
 * printer can say. Two ready answers in a row with no silence between them say
 * only that it is idle - possibly because it has not started, or finished
 * faster than we asked - and are not taken for a finished job.
 *
 * A fault bit is the other thing worth having: out of paper, a jam or an open
 * head would otherwise be discovered by a person at the printer, long after the
 * job was reported as done.
 *
 * ## The early answer is not guaranteed
 *
 * That one early `00` is the only proof, once the job is out, that the printer
 * answers at all - and it does not always come. On one run the first three
 * queries after the bytes were sent got nothing, from a printer that had
 * answered on the earlier runs of the same job. That reads exactly like a
 * printer that cannot answer. So the
 * question is asked once *before* the job, while the printer is idle and
 * answers within ~150 ms. A printer that answered then is busy when it goes
 * quiet, never "silent".
 */
internal object TsplStatus {

    /** ESC ! ? */
    val QUERY = byteArrayOf(0x1B, 0x21, 0x3F)

    const val HEAD_OPEN = 0x01
    const val PAPER_JAM = 0x02
    const val PAPER_EMPTY = 0x04
    const val PRINTING = 0x20

    /**
     * The ribbon bit, which a direct-thermal printer may set with nothing wrong:
     * not a fault, and not a reason to think the printer is not ready either.
     */
    const val RIBBON = 0x08

    enum class Fault { PAPER_EMPTY, PAPER_JAM, HEAD_OPEN }

    /**
     * The faults that stop a label coming out. The ribbon, pause and "other"
     * bits are not reported: a direct-thermal printer may set the ribbon bit
     * with nothing wrong, and a failure that is not one costs a reprint.
     */
    fun fault(status: Int): Fault? = when {
        status and PAPER_EMPTY != 0 -> Fault.PAPER_EMPTY
        status and PAPER_JAM != 0 -> Fault.PAPER_JAM
        status and HEAD_OPEN != 0 -> Fault.HEAD_OPEN
        else -> null
    }
}

/** How waiting for the printer to finish a job ended. */
internal sealed interface Ending {
    /** It went busy with the job and has said it is ready again, with no fault. */
    data class Finished(val waitedMs: Long) : Ending

    /** It answered ready every time and was never seen busy: idle, but not shown to have taken the job. */
    data class ReadyButNeverSeenBusy(val waitedMs: Long) : Ending

    /** It reports something that stops a label coming out. */
    data class Fault(val fault: TsplStatus.Fault) : Ending

    /**
     * It keeps answering with something that is neither ready, printing nor a
     * fault: paused, or an "other error", or a bit nobody has documented. It may
     * be holding the label rather than printing it, so this is not Finished.
     */
    data class NeedsAttention(val status: Int) : Ending

    /**
     * It never answered a status query at all - not before the job and not
     * since - so nothing can be said either way.
     */
    data object NoAnswer : Ending

    /**
     * The link went while asking. That says nothing about whether the printer
     * answers status queries, so unlike [NoAnswer] it is not remembered.
     */
    data object LinkLost : Ending

    /** Still busy when the time allowed ran out. The label may well still come out. */
    data class TimedOut(val waitedMs: Long) : Ending
}

/**
 * Polls the printer's status until the job is over, or until nothing more can be
 * learned.
 *
 * @param ask sends the status query and returns the byte that came back, or null
 *   if nothing did within the transport's short wait. Returning null is how a
 *   busy printer looks.
 * @param pause waits between polls. Suspending, so a cancelled job stops waiting.
 * @param onBusy told roughly how long it has been waiting, each time the printer
 *   does not answer, for a progress message.
 * @param answeredBefore the printer answered a status query before the job was
 *   sent. Silence after that is a printer busy with the job, however soon after
 *   the last byte it starts. It says nothing about the job itself: it is not
 *   "seen busy", and a printer that then answers ready every time is still only
 *   [Ending.ReadyButNeverSeenBusy].
 */
internal suspend fun awaitTsplEnd(
    ask: suspend () -> Int?,
    maxWaitMs: Long,
    now: () -> Long,
    pause: suspend (Long) -> Unit,
    onBusy: (waitedMs: Long) -> Unit = {},
    firstAnswerWithinPolls: Int = 3,
    readyPollsBeforeGivingUp: Int = 3,
    attentionPollsBeforeGivingUp: Int = 3,
    answeredBefore: Boolean = false,
): Ending {
    val started = now()
    var polls = 0
    var answeredEver = answeredBefore
    var sawBusy = false
    var readyInARow = 0
    var attentionInARow = 0

    while (true) {
        val waited = now() - started
        if (waited >= maxWaitMs) return Ending.TimedOut(waited)

        val status = ask()
        polls++

        if (status == null) {
            // A printer that has never answered, before the job or since, is one
            // that does not do this at all; one that has answered and now does
            // not is working.
            if (!answeredEver && polls >= firstAnswerWithinPolls) return Ending.NoAnswer
            if (answeredEver) {
                sawBusy = true
                readyInARow = 0
                attentionInARow = 0
                onBusy(now() - started)
            }
        } else {
            answeredEver = true
            TsplStatus.fault(status)?.let { return Ending.Fault(it) }

            // Three kinds of answer that are not a fault. Printing is busy. Ready
            // is nothing at all, ignoring the ribbon bit, which is noise on a
            // direct-thermal printer. Anything else - paused, "other error", a bit
            // nobody has documented - is neither: counting it as ready would report
            // a printer that is holding the label as having finished with it.
            val meaningful = status and TsplStatus.RIBBON.inv()
            when {
                meaningful and TsplStatus.PRINTING.inv() != 0 -> {
                    readyInARow = 0
                    // A stray byte is not a verdict; the same one three times is.
                    if (++attentionInARow >= attentionPollsBeforeGivingUp) {
                        return Ending.NeedsAttention(status)
                    }
                }
                meaningful and TsplStatus.PRINTING != 0 -> {
                    sawBusy = true
                    readyInARow = 0
                    attentionInARow = 0
                    onBusy(now() - started)
                }
                else -> {
                    attentionInARow = 0
                    if (sawBusy) return Ending.Finished(now() - started)
                    readyInARow++
                    if (readyInARow >= readyPollsBeforeGivingUp) {
                        return Ending.ReadyButNeverSeenBusy(now() - started)
                    }
                }
            }
        }

        // Each query sits in the printer's input behind the job, so asking
        // often makes a slow job slower. Start brisk, and back off.
        pause(minOf(1_000L + polls * 250L, 5_000L))
    }
}

/**
 * Printers that did not answer a status query, so the next job skips the wait.
 *
 * In memory and per process, on purpose: it is a shortcut to avoid asking a
 * printer that cannot answer, not a fact worth saving. A printer that is
 * replaced or reconfigured is asked again the next time the app starts.
 */
internal object TsplStatusMemory {
    private val silent = ConcurrentHashMap.newKeySet<String>()
    private val answered = ConcurrentHashMap.newKeySet<String>()

    fun answers(printerKey: String): Boolean = printerKey !in silent

    /**
     * The printer has answered a status query at some point in this process.
     *
     * After that, a job that gets no answer is a printer that was busy - still
     * taking in an earlier job, say, which the status probe before a job cannot
     * see past - and not one that cannot answer.
     */
    fun markAnswered(printerKey: String) {
        answered.add(printerKey)
    }

    /** A printer that has answered before is never marked: see [markAnswered]. */
    fun markSilent(printerKey: String) {
        if (printerKey !in answered) silent.add(printerKey)
    }
}
