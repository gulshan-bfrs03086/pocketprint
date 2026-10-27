package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.Ending
import com.gulshan.pocketprint.print.TsplStatus
import com.gulshan.pocketprint.print.awaitTsplEnd
import com.gulshan.pocketprint.transport.TransportException
import com.gulshan.pocketprint.transport.TscLibrary
import com.gulshan.pocketprint.transport.TscSdkTransport
import com.gulshan.pocketprint.transport.TscStatusWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TSC's own library under a [TscSdkTransport]: the app talks to a transport and
 * does not know which one. These stand a fake in for the library, which says
 * only "1" or "-1" and answers `ESC ! S` with a word.
 */
class TscSdkTransportTest {

    private val mac = "DC:0D:30:56:8B:3E"

    /**
     * The library, scripted the way the real one behaves: one status word per
     * question and then silence, which is "" - and "-1" only for a dead link. Replies
     * that arrived late sit in [unread] and are handed to the next question, as the
     * real library does, until something discards them.
     */
    private class FakeTsc(
        var openResult: String = "1",
        var sendResult: String = "1",
        words: List<String> = emptyList(),
        var name: String = "RE310",
        var openThrows: Exception? = null,
        var linkDead: Boolean = false,
        unread: List<String> = emptyList(),
        /** When set, openport blocks until released, like a connect that is still dialling. */
        val gate: CountDownLatch? = null,
    ) : TscLibrary {
        val words = ArrayDeque(words)
        val unread = ArrayDeque(unread)
        val sent = mutableListOf<ByteArray>()
        val entered = CountDownLatch(1)
        var opened: String? = null
        var statusAsked = 0
        var nameAsked = 0
        var discards = 0
        var closes = mutableListOf<Int>()

        override fun openport(mac: String): String {
            opened = mac
            entered.countDown()
            gate?.await()
            openThrows?.let { throw it }
            return openResult
        }
        override fun sendcommand(bytes: ByteArray): String { sent += bytes; return sendResult }
        override fun status(waitMs: Int): String {
            statusAsked++
            if (linkDead) return "-1"
            return unread.removeFirstOrNull() ?: words.removeFirstOrNull() ?: ""
        }
        override fun printername(waitMs: Int): String {
            nameAsked++
            if (linkDead) return "-1"
            val late = unread.joinToString("")
            unread.clear()
            return late + name
        }
        override fun closeport(delayMs: Int): String { closes += delayMs; return "1" }
        override fun discardUnread() { discards++; unread.clear() }
    }

    private fun transport(lib: FakeTsc) =
        TscSdkTransport(mac, libraryFactory = { lib }, onMainThread = { it() })

    private fun opened(lib: FakeTsc) = transport(lib).also { runBlocking { it.open() } }

    private val statusQuery = byteArrayOf(0x1B, 0x21, 0x3F)

    /** What the engine does: ask, then read. */
    private fun TscSdkTransport.askStatus(): Int? = runBlocking {
        readAvailable(20)
        write(statusQuery)
        readAvailable(700).firstOrNull()?.toInt()?.and(0xFF)
    }

    // -- opening -----------------------------------------------------------

    @Test
    fun `it opens the printer through the library`() {
        val lib = FakeTsc()
        val t = opened(lib)

        assertEquals(mac, lib.opened)
        assertEquals("tsc://$mac", t.description)
    }

    @Test
    fun `a library that says -1 is an error a person can read, not a silent failure`() {
        val lib = FakeTsc(openResult = "-1")

        try {
            runBlocking { transport(lib).open() }
            fail("opening should have failed")
        } catch (e: TransportException) {
            assertTrue(e.message, "TSC library" in e.message!! && "-1" in e.message!!)
            assertTrue("it should say what to check: ${e.message}", "paired" in e.message!!)
        }
    }

    /**
     * What happened on the first real print: the library asks Android for
     * BLUETOOTH_ADMIN before it dials, does not catch the refusal, and the job
     * failed in an instant with nothing on the Bluetooth link at all.
     */
    @Test
    fun `a permission the library is refused is explained, and the library is still closed once`() {
        val lib = FakeTsc(openThrows = SecurityException("Need BLUETOOTH ADMIN permission"))
        val t = transport(lib)

        try {
            runBlocking { t.open() }
            fail("opening should have failed")
        } catch (e: TransportException) {
            assertTrue(e.message, "permission" in e.message!! && "BLUETOOTH ADMIN" in e.message!!)
            assertTrue("it should say what to do: ${e.message}", "Automatic" in e.message!!)
        }
        t.close(); t.close()
        // Closed once, like any failed open: the library was held before it dialled,
        // so whatever it left behind is cleaned up, and a second close is nothing.
        assertEquals(listOf(0), lib.closes)
    }

    @Test
    fun `closing a connection that was never opened does not touch the library`() {
        val lib = FakeTsc()

        transport(lib).close()

        assertTrue(lib.closes.isEmpty())
    }

    /**
     * openport cannot say what it left behind when it fails - a socket made and
     * not connected, say - so the close that always follows a failed open reaches
     * the library once and lets it clean up.
     */
    @Test
    fun `a failed open is still closed once, so nothing half-made is left`() {
        val lib = FakeTsc(openResult = "-1")
        val t = transport(lib)
        runCatching { runBlocking { t.open() } }

        t.close(); t.close()

        assertEquals(listOf(0), lib.closes)
    }

    /**
     * openport cannot be interrupted: a job cancelled while it dials still gets a
     * connected socket back. Stored only after openport returned, the library was
     * dropped with the socket open and nothing ever closed it.
     */
    @Test
    fun `a connect that is cancelled while dialling is still closed`() {
        val gate = CountDownLatch(1)
        val lib = FakeTsc(gate = gate)
        val t = transport(lib)

        runBlocking {
            val job = launch(Dispatchers.Default) { t.open() }
            withContext(Dispatchers.IO) { lib.entered.await() } // openport is dialling
            job.cancel()
            gate.countDown() // ... and now connects, to nobody
            job.join()
        }
        t.close() // what the engine's use{} does

        assertEquals(listOf(0), lib.closes)
    }

    @Test
    fun `it closes the library once, however many times it is closed`() {
        val lib = FakeTsc()
        val t = opened(lib)

        t.close(); t.close()

        assertEquals(listOf(0), lib.closes)
    }

    // -- sending -----------------------------------------------------------

    @Test
    fun `a job goes through the library in one send and is reported as written`() {
        val lib = FakeTsc()
        val t = opened(lib)
        val job = ByteArray(5_000) { (it % 251).toByte() }
        var reported = 0L

        val written = runBlocking { t.write(job) { reported = it } }

        assertEquals(5_000L, written)
        assertEquals(5_000L, reported)
        assertEquals(1, lib.sent.size)
        assertArrayEquals(job, lib.sent.single())
    }

    @Test
    fun `a failed send is an error that says how much and to where`() {
        val lib = FakeTsc(sendResult = "-1")
        val t = opened(lib)

        try {
            runBlocking { t.write(ByteArray(100)) }
            fail("the write should have failed")
        } catch (e: TransportException) {
            assertTrue(e.message, "100 bytes" in e.message!! && mac in e.message!!)
        }
    }

    @Test
    fun `writing before opening is refused`() {
        try {
            runBlocking { transport(FakeTsc()).write(ByteArray(10)) }
            fail("should not write to a printer that is not open")
        } catch (e: TransportException) {
            assertTrue(e.message, "not open" in e.message!!)
        }
    }

    // -- asking the printer ------------------------------------------------

    @Test
    fun `the status question is asked of the library and is not sent as bytes`() {
        val lib = FakeTsc(words = listOf("Ready"))
        val t = opened(lib)

        val status = t.askStatus()

        assertEquals(0x00, status)
        assertEquals(1, lib.statusAsked)
        assertTrue("the question went out as raw bytes too", lib.sent.isEmpty())
    }

    @Test
    fun `each word the library knows reads as the one-byte status the engine understands`() {
        val cases = listOf(
            "Ready" to 0x00,
            "Printing Batch" to 0x20,
            "Head Open" to 0x01,
            "Paper Jam" to 0x02,
            "No Paper" to 0x04,
        )
        for ((word, expected) in cases) {
            assertEquals(word, expected, opened(FakeTsc(words = listOf(word))).askStatus())
        }
    }

    /**
     * The one that matters. A printer taking in a job answers nothing, and the
     * completion logic reads that as busy. The library's own printerstatus_byte
     * would have handed back a stale 00 here; this must not.
     */
    @Test
    fun `a printer that says nothing stays silent and is never read as ready`() {
        val lib = FakeTsc(words = emptyList()) // every question gets "" - a quiet printer
        val t = opened(lib)

        assertNull(t.askStatus())
        assertNull(t.askStatus())
    }

    @Test
    fun `a word it does not know is silence, not a guess`() {
        assertNull(opened(FakeTsc(words = listOf("Something New"))).askStatus())
    }

    @Test
    fun `an answer is handed over once`() {
        val t = opened(FakeTsc(words = listOf("Ready")))
        runBlocking { t.write(statusQuery) }

        assertEquals(1, runBlocking { t.readAvailable(100) }.size)
        assertEquals(0, runBlocking { t.readAvailable(100) }.size)
    }

    @Test
    fun `an old answer is not left over for the next question`() {
        val lib = FakeTsc(words = listOf("Ready"))
        val t = opened(lib)
        runBlocking { t.write(statusQuery) }

        // The next thing written is a job; whatever was waiting is gone.
        runBlocking { t.write(ByteArray(20)) }

        assertEquals(0, runBlocking { t.readAvailable(100) }.size)
    }

    @Test
    fun `the model is asked of the library, with or without the line ending setup adds`() {
        for (query in listOf("~!T".toByteArray(), "~!T\r\n".toByteArray())) {
            val lib = FakeTsc(name = "RE310\u0000\u0000\u0000")
            val t = opened(lib)

            runBlocking { t.write(query) }
            val text = String(runBlocking { t.readAvailable(100) }, Charsets.ISO_8859_1)

            assertEquals("RE310", text)
            assertEquals(1, lib.nameAsked)
            assertTrue("the model question was sent as bytes as well", lib.sent.isEmpty())
        }
    }

    @Test
    fun `a printer that does not say its model is no reply`() {
        val t = opened(FakeTsc(name = ""))
        runBlocking { t.write("~!T\r\n".toByteArray()) }

        assertEquals(0, runBlocking { t.readAvailable(100) }.size)
    }

    // -- a dead link is not a quiet printer ---------------------------------

    /**
     * In the library's own code "-1" comes back only when its streams are gone or a
     * write or read threw. Read as silence it made a printer that had switched off
     * look busy, and the engine waited out the full time allowed - up to ten
     * minutes, with the printer's queue held.
     */
    @Test
    fun `a dead link on the status question is an error, not a busy printer`() {
        val t = opened(FakeTsc(linkDead = true))

        try {
            t.askStatus()
            fail("a lost connection should have thrown")
        } catch (e: TransportException) {
            assertTrue(e.message, "lost its connection" in e.message!! && mac in e.message!!)
        }
    }

    @Test
    fun `a dead link on the model question is an error too`() {
        val t = opened(FakeTsc(linkDead = true))

        try {
            runBlocking { t.write("~!T\r\n".toByteArray()) }
            fail("a lost connection should have thrown")
        } catch (e: TransportException) {
            assertTrue(e.message, "lost its connection" in e.message!!)
        }
    }

    @Test
    fun `a quiet printer and a dead link are told apart`() {
        assertNull("quiet", opened(FakeTsc(words = emptyList())).askStatus())
        try {
            opened(FakeTsc(linkDead = true)).askStatus()
            fail("dead should differ from quiet")
        } catch (_: TransportException) { /* expected */ }
    }

    // -- replies nobody read -------------------------------------------------

    /**
     * The library never clears the socket before it asks, so a reply that arrived
     * late for an earlier question came back as the answer to the next - here a
     * stale "Ready" in front of a printer that is in fact printing.
     */
    @Test
    fun `a late reply from before is thrown away and not read as this answer`() {
        val lib = FakeTsc(words = listOf("Printing Batch"), unread = listOf("Ready"))
        val t = opened(lib)

        assertEquals(0x20, t.askStatus())
        assertEquals(1, lib.discards)
    }

    @Test
    fun `a late reply is not left in front of the model either`() {
        val lib = FakeTsc(unread = listOf("Ready"), name = "RE310")
        val t = opened(lib)

        runBlocking { t.write("~!T\r\n".toByteArray()) }

        assertEquals("RE310", String(runBlocking { t.readAvailable(100) }, Charsets.ISO_8859_1))
    }

    @Test
    fun `every question discards first, not just the first one`() {
        val lib = FakeTsc(words = listOf("-", "-", "-"))
        val t = opened(lib)

        repeat(3) { t.askStatus() }

        assertEquals(3, lib.discards)
    }

    // -- the words themselves ----------------------------------------------

    @Test
    fun `every word the library decodes has a status`() {
        val words = mapOf(
            "Ready" to 0x00, "Printing Batch" to 0x20, "Cutting" to 0x20,
            "Pause" to 0x10, "Waiting to Press Print Key" to 0x10, "Waiting to Take Label" to 0x10,
            "Head Open" to 0x01, "Paper Jam" to 0x02, "No Paper" to 0x04, "Paper Empty" to 0x04,
            "Ribbon Empty" to 0x08, "Ribbon Jam" to 0x08,
        )
        for ((word, status) in words) assertEquals(word, status, TscStatusWords.toStatusByte(word))
    }

    @Test
    fun `silence and unknown words have no status`() {
        for (word in listOf("-1", "", "ready", "READY", "Printing", "Cutting Label")) {
            assertNull(word, TscStatusWords.toStatusByte(word))
        }
    }

    @Test
    fun `the library's faults are faults to the completion logic, and a pause is not a fault`() {
        assertTrue(TsplStatus.fault(TscStatusWords.toStatusByte("Paper Jam")!!) == TsplStatus.Fault.PAPER_JAM)
        assertTrue(TsplStatus.fault(TscStatusWords.toStatusByte("No Paper")!!) == TsplStatus.Fault.PAPER_EMPTY)
        assertTrue(TsplStatus.fault(TscStatusWords.toStatusByte("Head Open")!!) == TsplStatus.Fault.HEAD_OPEN)
        assertNull(TsplStatus.fault(TscStatusWords.toStatusByte("Pause")!!))
        assertNull(TsplStatus.fault(TscStatusWords.toStatusByte("Ribbon Empty")!!))
    }

    // -- through the real completion logic ---------------------------------

    private fun finish(lib: FakeTsc, answeredBefore: Boolean = false): Ending {
        val t = opened(lib)
        var clock = 0L
        return runBlocking {
            awaitTsplEnd(
                ask = { t.askStatus() },
                maxWaitMs = 600_000,
                now = { clock },
                pause = { clock += it },
                answeredBefore = answeredBefore,
            )
        }
    }

    @Test
    fun `busy, busy, printing, ready is a finished job`() {
        // Silence while it takes the job in, "Printing Batch" while it prints,
        // "Ready" when it is done: the shape the completion logic is built for.
        val lib = FakeTsc(words = listOf("", "", "", "Printing Batch", "Ready"))

        val result = finish(lib, answeredBefore = true)

        assertTrue("$result", result is Ending.Finished)
    }

    @Test
    fun `a printer that never answers is given up on and not called finished`() {
        val result = finish(FakeTsc(words = emptyList()))

        assertEquals(Ending.NoAnswer, result)
    }

    @Test
    fun `a paper jam reported by the library fails the job at once`() {
        val result = finish(FakeTsc(words = listOf("Paper Jam")))

        assertEquals(Ending.Fault(TsplStatus.Fault.PAPER_JAM), result)
    }

    @Test
    fun `ready every time without ever being busy is not taken for a finished job`() {
        val result = finish(FakeTsc(words = List(6) { "Ready" }))

        assertTrue("$result", result is Ending.ReadyButNeverSeenBusy)
    }

    @Test
    fun `a pause is the printer holding the label, not a finished one`() {
        val result = finish(FakeTsc(words = List(6) { "Pause" }))

        assertTrue("$result", result is Ending.NeedsAttention)
    }

}
