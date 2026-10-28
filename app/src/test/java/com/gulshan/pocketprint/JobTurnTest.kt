package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.inTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service holds one lock for a whole job, and a TSPL job holds it through
 * the printer's wait for the label - minutes. A second document sent meanwhile
 * used to have no history row, and so no Cancel button, until the first was over.
 */
class JobTurnTest {

    private val events = mutableListOf<String>()

    private suspend fun <T> turn(
        lock: Mutex,
        announce: suspend () -> Unit = { events += "announce" },
        body: suspend (taken: () -> Unit) -> T,
    ) = inTurn(lock, announce, settleIfCancelledEarly = { events += "settled" }, body = body)

    @Test
    fun `a job is announced while it waits, not after`() = runBlocking {
        val lock = Mutex().apply { lock() }

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            turn(lock) { events += "body" }
        }
        yield()

        assertEquals("it was not in the history while the lock was held", listOf("announce"), events)

        lock.unlock()
        waiting.join()
        assertEquals(listOf("announce", "body"), events)
    }

    @Test
    fun `a job cancelled while it waits is settled, and never starts`() = runBlocking {
        val lock = Mutex().apply { lock() }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            turn(lock) { events += "body" }
        }
        yield()

        waiting.cancelAndJoin()

        assertEquals("the QUEUED row was left behind", listOf("announce", "settled"), events)
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun `a job cancelled while it is being announced is settled too`() = runBlocking {
        val inAnnounce = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            turn(Mutex(), announce = { inAnnounce.complete(Unit); awaitCancellation() }) {
                events += "body"
            }
        }
        inAnnounce.await()

        job.cancelAndJoin()

        assertEquals(listOf("settled"), events)
    }

    @Test
    fun `a job that has taken over owns its own cancellation`() = runBlocking {
        val running = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            turn(Mutex()) { taken ->
                taken()
                running.complete(Unit)
                awaitCancellation()
            }
        }
        running.await()

        job.cancelAndJoin()

        assertFalse("a running job's row was overwritten from outside", "settled" in events)
    }

    @Test
    fun `a job that runs through is not settled`() = runBlocking {
        val result = turn(Mutex()) { taken -> taken(); 42 }

        assertEquals(42, result)
        assertEquals(listOf("announce"), events)
    }
}
