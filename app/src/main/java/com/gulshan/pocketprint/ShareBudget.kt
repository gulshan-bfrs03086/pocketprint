package com.gulshan.pocketprint

import com.gulshan.pocketprint.render.Spool

/**
 * What one shared batch of documents is allowed to consume.
 *
 * A single shared document has always been bounded, because the share target
 * is exported to every app on the device and is written on the assumption that
 * what arrives is hostile. Accepting several at once quietly removes that
 * bound: nothing stops a caller sending fifty URIs, and fifty times the
 * one-document limit is not a print job, it is a full disk.
 *
 * So the batch gets a budget of its own, and each document may take the
 * smaller of what one document is allowed and what is left of it. Both limits
 * apply; neither replaces the other.
 */
internal class ShareBudget(
    private val maxBatchBytes: Long = MAX_BATCH_BYTES,
    private val maxDocumentBytes: Long = Spool.MAX_DOCUMENT_BYTES,
) {

    private var used: Long = 0

    /** How much of the batch budget has been spent. */
    val usedBytes: Long get() = used

    /**
     * The most the next document may copy.
     *
     * Zero once the batch is spent, which is the signal to stop rather than a
     * limit to copy under: a zero-byte allowance would fail every remaining
     * document one at a time and report each one as too large.
     */
    fun allowance(): Long =
        (maxBatchBytes - used).coerceAtLeast(0L).coerceAtMost(maxDocumentBytes)

    /** Whether there is room to attempt another document at all. */
    val exhausted: Boolean get() = allowance() == 0L

    /** Records what a document actually cost, which is its copied size. */
    fun record(bytes: Long) {
        used += bytes.coerceAtLeast(0L)
    }

    companion object {
        /**
         * How many documents one share may stage.
         *
         * This bounds the work as well as the storage: every document costs a
         * content-resolver query and a copy before anything is known about it,
         * so the count has to be capped before the first URI is read rather
         * than as a consequence of running out of bytes.
         */
        const val MAX_DOCUMENTS = 20

        /** Roughly two documents at the single-document limit. */
        const val MAX_BATCH_BYTES: Long = 128L * 1024 * 1024
    }
}
