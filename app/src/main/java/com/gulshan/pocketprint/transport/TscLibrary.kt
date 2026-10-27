package com.gulshan.pocketprint.transport

/**
 * The handful of calls we use from TSC's own Android library (`tscsdk.jar`,
 * class `com.example.tscdll.TSCActivity`), as a seam.
 *
 * The real one is [TscSdk.library], which exists only in a build that was given
 * the jar. Everything else - [TscSdkTransport], the status mapping, the tests -
 * talks to this, so none of it needs the library to compile, and a test can
 * stand in for it.
 *
 * Every call blocks, and the library reports failure as the string `"-1"`
 * rather than an exception and with no reason, so callers run these off the
 * main thread and turn `"-1"` into something a person can act on.
 */
interface TscLibrary {

    /** Opens a secure RFCOMM connection. `"1"` on success, `"-1"` on any failure. */
    fun openport(mac: String): String

    /** One plain write of [bytes]. `"1"` on success, `"-1"` on failure. */
    fun sendcommand(bytes: ByteArray): String

    /**
     * The printer's own status, asked with `ESC ! S` and decoded by the library
     * to a word (see [TscStatusWords]).
     *
     * Two different non-answers, and the difference matters. `""` is a printer
     * that said nothing in [waitMs] - which is how one busy taking in a job
     * looks. `"-1"` is the *connection* failing: the library gets it only when
     * its streams are gone or writing or reading threw, and it comes back at
     * once. Treating `"-1"` as silence would read a dropped link as a busy
     * printer.
     *
     * Waits the whole of [waitMs] when the link is up, answered or not.
     */
    fun status(waitMs: Int): String

    /** The model, asked with `~!T`: raw reply text, `""`-ish when silent, `"-1"` for a dead link. */
    fun printername(waitMs: Int): String

    /**
     * Throws away whatever the printer has sent that nobody has read.
     *
     * The library never does this itself: it writes its question and reads
     * whatever is waiting, so a reply that arrived late for an earlier question
     * is returned as the answer to the next. The plain Bluetooth transport clears
     * the socket before every question for exactly that reason; this is the same
     * for the library, and is best-effort - see [TscSdk].
     */
    fun discardUnread()

    /** Closes the connection after waiting [delayMs]. */
    fun closeport(delayMs: Int): String
}

/**
 * What TSC's library calls each answer to `ESC ! S`, and the one-byte status
 * `ESC ! ?` gives that the job-completion logic already reads.
 *
 * The words are the library's, read out of its own code (TSCActivity.status):
 * `ESC ! S` answers a six-byte frame, `02 b1 b2 b3 b4 03`, and the library
 * decodes it - `@@@@` is Ready, `P@@@` is Printing Batch, `E@@A` is No Paper,
 * and so on.
 *
 * The byte is the shape that logic wants: 0x00 ready, 0x20 busy printing,
 * 0x01 head open, 0x02 paper jam, 0x04 paper out, 0x08 ribbon. The states that
 * are neither ready, busy nor a fault - paused, waiting for a key or for the
 * label to be taken - are 0x10, which that logic treats as needing attention
 * and not as finished.
 *
 * Anything else, including silence, is null. Silence must stay silence: the
 * completion logic reads a printer that goes quiet as one busy with the job,
 * and turning "no answer" into a status would turn it into "ready".
 */
object TscStatusWords {

    /** What the library returns when the connection itself has failed, as opposed to the printer being quiet. */
    const val LINK_FAILED = "-1"

    fun toStatusByte(word: String): Int? = when (word) {
        "Ready" -> READY
        "Printing Batch", "Cutting" -> PRINTING
        "Pause", "Waiting to Press Print Key", "Waiting to Take Label" -> ATTENTION
        "Head Open" -> HEAD_OPEN
        "Paper Jam" -> PAPER_JAM
        "No Paper", "Paper Empty" -> PAPER_EMPTY
        "Ribbon Empty", "Ribbon Jam" -> RIBBON
        // "" is a quiet printer, and an unknown word is one this table does not
        // know: neither is a status. (LINK_FAILED never gets here - the transport
        // throws on it, because a lost connection is not the printer being quiet.)
        else -> null
    }

    private const val READY = 0x00
    private const val PRINTING = 0x20
    private const val ATTENTION = 0x10
    private const val HEAD_OPEN = 0x01
    private const val PAPER_JAM = 0x02
    private const val PAPER_EMPTY = 0x04
    private const val RIBBON = 0x08
}
