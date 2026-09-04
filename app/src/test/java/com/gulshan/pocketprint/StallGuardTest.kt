package com.gulshan.pocketprint

import com.gulshan.pocketprint.transport.WriteStalled
import com.gulshan.pocketprint.transport.guardAgainstStall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The guard exists because of one hard fact: cancelling a coroutine does not
 * interrupt a thread blocked in write(2). Closing the socket underneath it
 * does. These tests stand in for the socket with a latch, since a real one
 * would need a printer that has stopped reading.
 */
class StallGuardTest {

    @Test
    fun `a write that stops moving is unblocked and reported as a stall`() = runBlocking {
        val socket = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        val failure = runCatching {
            guardAgainstStall(
                stallMs = 300,
                unblock = { closed.set(true); socket.countDown() },
            ) { _ ->
                withContext(Dispatchers.IO) {
                    socket.await()
                    // What a write sees once its socket is closed from another
                    // thread. True, and useless on its own.
                    throw IOException("socket closed")
                }
            }
        }.exceptionOrNull()

        assertTrue("the transport should have been closed", closed.get())
        assertTrue("expected WriteStalled, got $failure", failure is WriteStalled)
    }

    @Test
    fun `a slow write that keeps moving is left alone`() = runBlocking {
        // Eight hundred milliseconds of work under a three-hundred millisecond
        // deadline. The deadline is on the gap between chunks, not on the job,
        // because a rasterised page over Bluetooth legitimately takes minutes.
        val closed = AtomicBoolean(false)

        val chunks = guardAgainstStall(
            stallMs = 300,
            unblock = { closed.set(true) },
        ) { heartbeat ->
            var written = 0
            repeat(10) {
                delay(80)
                heartbeat()
                written++
            }
            written
        }

        assertEquals(10, chunks)
        assertFalse(closed.get())
    }

    @Test
    fun `a write that finishes is not touched afterwards`() = runBlocking {
        val closed = AtomicBoolean(false)
        val result = guardAgainstStall(100, { closed.set(true) }) { heartbeat ->
            heartbeat()
            "done"
        }
        assertEquals("done", result)

        // Well past the deadline: the watcher must have been retired with the
        // write, not left running to close a transport somebody else is using.
        delay(300)
        assertFalse(closed.get())
    }

    @Test
    fun `cancelling the job closes the transport, which is what ends the write`() = runBlocking {
        val socket = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        val job = launch(Dispatchers.Default) {
            guardAgainstStall(
                // Far beyond the test: this must be the cancellation doing the
                // work, not the stall deadline.
                stallMs = 60_000,
                unblock = { closed.set(true); socket.countDown() },
            ) { heartbeat ->
                withContext(Dispatchers.IO) {
                    // Busy and healthy - beating steadily, ignoring cancellation
                    // exactly as a blocking write would.
                    while (!socket.await(20, TimeUnit.MILLISECONDS)) heartbeat()
                }
            }
        }

        delay(150)
        job.cancelAndJoin()

        assertTrue("cancelling must close the transport", closed.get())
    }
}
