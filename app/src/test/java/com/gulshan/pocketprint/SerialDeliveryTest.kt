package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.print.Ending
import com.gulshan.pocketprint.print.TsplStatus
import com.gulshan.pocketprint.print.TsplStatusMemory
import com.gulshan.pocketprint.print.asksStatus
import com.gulshan.pocketprint.print.deliverSerial
import com.gulshan.pocketprint.transport.PrinterTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every job to a serial printer goes through [deliverSerial], whether it was
 * rendered from a document or built by the label designer, the test page or
 * calibration. Raw jobs used to be written and left Sent-only, so a label that
 * ran out of paper was reported as sent.
 */
class SerialDeliveryTest {

    /**
     * A key no other test has marked. The memory is process-wide and JUnit makes
     * a new instance per test, so a counter would hand the same key out twice.
     */
    private fun freshKey() = "bt:serial-delivery:${java.util.UUID.randomUUID()}"

    /**
     * A transport that answers the status query from a script, one entry per
     * question: a byte is the reply, null is silence. Anything else written is
     * the job.
     */
    private class FakePrinter(replies: List<Int?>) : PrinterTransport {
        private val script = ArrayDeque(replies)
        private var pending = ByteArray(0)
        val queries = AtomicInteger()
        var payload = ByteArray(0)
        var finished = false
        var closed = false
        /** When set, a question's write blocks like a socket to a printer that stopped reading. */
        var blockQueries: CountDownLatch? = null

        override val description = "fake"
        override suspend fun open() = Unit
        override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long {
            val bytes = source.readBytes()
            if (bytes.contentEquals(TsplStatus.QUERY)) {
                queries.incrementAndGet()
                blockQueries?.let { gate ->
                    withContext(Dispatchers.IO) {
                        gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        throw IOException("socket closed")
                    }
                }
                pending = script.removeFirstOrNull()?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0)
            } else {
                payload += bytes
                onProgress(bytes.size.toLong())
            }
            return bytes.size.toLong()
        }
        override suspend fun finish() { finished = true }
        override suspend fun readAvailable(timeoutMs: Long): ByteArray = pending.also { pending = ByteArray(0) }
        override fun close() {
            closed = true
            blockQueries?.countDown()
        }
    }

    private class Clock {
        var now = 0L
        val pause: suspend (Long) -> Unit = { now += it }
    }

    private fun deliver(
        printer: FakePrinter,
        language: PrintLanguage?,
        key: String = freshKey(),
        stallMs: Long = 60_000,
    ): com.gulshan.pocketprint.print.SerialOutcome = runBlocking {
        val clock = Clock()
        deliverSerial(
            transport = printer,
            printerKey = key,
            language = language,
            source = "SIZE 1,1\r\nPRINT 1,1\r\n".byteInputStream(),
            stallMs = stallMs,
            now = { clock.now },
            pause = clock.pause,
        )
    }

    @Test
    fun `a TSPL job that finds the paper out is reported as a fault`() {
        // The case raw jobs could not report: ready before the job, out of paper after.
        val printer = FakePrinter(listOf(0x00, TsplStatus.PAPER_EMPTY))

        val outcome = deliver(printer, PrintLanguage.TSPL)

        assertEquals(Ending.Fault(TsplStatus.Fault.PAPER_EMPTY), outcome.ending)
        assertTrue(outcome.answeredBefore)
    }

    @Test
    fun `the job itself is written once and in full`() {
        val printer = FakePrinter(listOf(0x00, 0x00, null, 0x00))

        val outcome = deliver(printer, PrintLanguage.TSPL)

        assertEquals("SIZE 1,1\r\nPRINT 1,1\r\n", String(printer.payload))
        assertEquals(printer.payload.size.toLong(), outcome.sent)
    }

    @Test
    fun `a job that does not say its language is never questioned`() {
        // Before the language reached printRaw, this is what every raw label was.
        val printer = FakePrinter(listOf(0x00, TsplStatus.PAPER_EMPTY))

        val outcome = deliver(printer, language = null)

        assertEquals("a question was written to a printer of unknown dialect", 0, printer.queries.get())
        assertEquals(null, outcome.ending)
        assertTrue("an unconfirmed job must still be drained", printer.finished)
    }

    @Test
    fun `only TSPL has a status query worth asking`() {
        val key = freshKey()

        assertTrue(asksStatus(PrintLanguage.TSPL, key))
        assertFalse(asksStatus(PrintLanguage.ZPL, key))
        assertFalse(asksStatus(PrintLanguage.ESC_POS, key))
        assertFalse(asksStatus(null, key))
    }

    @Test
    fun `a printer already found not to answer is not asked again`() {
        val key = freshKey()
        TsplStatusMemory.markSilent(key)
        val printer = FakePrinter(listOf(0x00))

        val outcome = deliver(printer, PrintLanguage.TSPL, key)

        assertEquals(0, printer.queries.get())
        assertEquals(null, outcome.ending)
    }

    @Test
    fun `a finished job is not lingered over, and an unproven one is drained`() {
        val finished = FakePrinter(listOf(0x00, 0x00, null, 0x00))
        assertTrue(deliver(finished, PrintLanguage.TSPL).ending is Ending.Finished)
        assertFalse("it waited again for a printer that had said it was done", finished.finished)

        val unproven = FakePrinter(listOf(0x00, 0x00, 0x00, 0x00))
        assertTrue(deliver(unproven, PrintLanguage.TSPL).ending is Ending.ReadyButNeverSeenBusy)
        assertTrue(unproven.finished)
    }

    @Test
    fun `a question written to a printer that stopped reading is cut off, not waited on for good`() {
        // The status query's write is a write to the same blocking socket as the
        // job. Outside the stall guard it hung until the process died.
        val printer = FakePrinter(listOf(0x00)).apply { blockQueries = CountDownLatch(1) }

        val outcome = runBlocking {
            withTimeout(10_000) { async { deliver(printer, PrintLanguage.TSPL, stallMs = 300) }.await() }
        }

        assertTrue("the transport was not closed under the blocked write", printer.closed)
        assertEquals(Ending.LinkLost, outcome.ending)
    }

    @Test
    fun `cancelling a job whose question is blocked closes the transport`() = runBlocking {
        val printer = FakePrinter(listOf(0x00)).apply { blockQueries = CountDownLatch(1) }
        val started = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            deliverSerial(
                transport = printer,
                printerKey = freshKey(),
                language = PrintLanguage.TSPL,
                source = "PRINT 1,1\r\n".byteInputStream(),
                stallMs = 60_000,
            )
        }
        started.await()
        while (printer.queries.get() == 0) kotlinx.coroutines.delay(10)

        withTimeout(10_000) { job.cancelAndJoin() }

        assertTrue("cancellation could not reach a write blocked in the question", printer.closed)
    }
}
