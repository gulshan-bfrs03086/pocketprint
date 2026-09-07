package com.gulshan.pocketprint

import com.gulshan.pocketprint.render.Spool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share target is exported to every app on the device and treats what
 * arrives as hostile. Accepting several documents at once is where that stance
 * is easiest to lose: the single-document limit has always been there, and
 * multiplied by an unbounded number of documents it stops being a limit.
 *
 * These pin the arithmetic of the second bound - what one batch may consume in
 * total - including the case that matters most, which is that it runs out
 * rather than letting a caller keep going.
 */
class ShareBudgetTest {

    private val mb = 1024L * 1024

    @Test
    fun `a fresh batch lets one document take the whole per-document limit`() {
        val budget = ShareBudget(maxBatchBytes = 128 * mb, maxDocumentBytes = 64 * mb)

        assertEquals(64 * mb, budget.allowance())
        assertFalse(budget.exhausted)
    }

    @Test
    fun `the allowance shrinks to whatever is left of the batch`() {
        val budget = ShareBudget(maxBatchBytes = 128 * mb, maxDocumentBytes = 64 * mb)

        budget.record(100 * mb)

        // Not 64: there is only 28 MB of batch left, and that is the real limit
        // for the next document however big one document is allowed to be.
        assertEquals(28 * mb, budget.allowance())
    }

    @Test
    fun `a spent batch allows nothing and says so`() {
        val budget = ShareBudget(maxBatchBytes = 128 * mb, maxDocumentBytes = 64 * mb)

        budget.record(128 * mb)

        assertEquals(0L, budget.allowance())
        assertTrue(budget.exhausted)
    }

    @Test
    fun `overshooting the budget cannot produce a negative allowance`() {
        // copyToCache stops at the limit, so this should not arise - but a
        // negative allowance handed back to it would mean "no limit at all"
        // on the very next document, which is the worst possible reading.
        val budget = ShareBudget(maxBatchBytes = 128 * mb, maxDocumentBytes = 64 * mb)

        budget.record(200 * mb)

        assertEquals(0L, budget.allowance())
        assertTrue(budget.exhausted)
    }

    @Test
    fun `a negative size cannot buy budget back`() {
        val budget = ShareBudget(maxBatchBytes = 128 * mb, maxDocumentBytes = 64 * mb)

        budget.record(64 * mb)
        budget.record(-32 * mb)

        assertEquals(64 * mb, budget.usedBytes)
    }

    @Test
    fun `the per-document limit still applies when the batch is generous`() {
        val budget = ShareBudget(maxBatchBytes = 1024 * mb, maxDocumentBytes = 64 * mb)

        assertEquals(64 * mb, budget.allowance())
    }

    @Test
    fun `three large documents exhaust a batch part way through`() {
        val budget = ShareBudget(maxBatchBytes = 128 * mb, maxDocumentBytes = 64 * mb)
        val allowances = mutableListOf<Long>()

        repeat(3) {
            allowances += budget.allowance()
            if (!budget.exhausted) budget.record(50 * mb)
        }

        // 64 for the first, 64 for the second (78 left, capped per document),
        // then 28 for what is left. The batch is what stops it, not the count.
        assertEquals(listOf(64 * mb, 64 * mb, 28 * mb), allowances)
    }

    @Test
    fun `the batch budget actually binds`() {
        // If every document a batch is allowed could take the full
        // per-document limit, the byte budget would never fire and only the
        // count would be doing any work. Each limit closes a hole the other
        // leaves: the count bounds the queries and copies attempted, the bytes
        // bound what they can cost.
        val unbounded = ShareBudget.MAX_DOCUMENTS * Spool.MAX_DOCUMENT_BYTES

        assertTrue(
            "a batch budget of ${ShareBudget.MAX_BATCH_BYTES} would never fire",
            ShareBudget.MAX_BATCH_BYTES < unbounded,
        )
    }

    @Test
    fun `the count cap is finite and small enough to be a cap`() {
        // The share target takes uris.take(MAX_DOCUMENTS) before reading any of
        // them, which is the only thing standing between it and a caller
        // sending ten thousand.
        assertTrue(ShareBudget.MAX_DOCUMENTS in 1..100)
    }
}
