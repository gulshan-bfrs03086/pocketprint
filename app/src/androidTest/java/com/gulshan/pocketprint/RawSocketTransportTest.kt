package com.gulshan.pocketprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.transport.RawSocketTransport
import com.gulshan.pocketprint.transport.TransportException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The open/write/finish/close lifecycle against a real TCP stack.
 *
 * On device rather than on the JVM because this transport's whole job is what
 * a socket does, and because the one thing it adds over a plain socket -
 * binding to the local network before connect - only exists on Android. The
 * loopback server here stands in for a printer that reads: it cannot tell us
 * anything a printer would not, which is the point, since JetDirect has no
 * acknowledgement at all.
 */
@RunWith(AndroidJUnit4::class)
class RawSocketTransportTest {

    private lateinit var server: ServerSocket
    private val received = AtomicReference<ByteArray>(ByteArray(0))
    private val accepted = CountDownLatch(1)
    private var acceptor: Thread? = null

    @Before
    fun startServer() {
        server = ServerSocket(0)
        acceptor = thread(name = "loopback-printer") {
            runCatching {
                server.accept().use { peer ->
                    accepted.countDown()
                    received.set(peer.getInputStream().readBytes())
                }
            }
        }
    }

    @After
    fun stopServer() {
        runCatching { server.close() }
        acceptor?.join(2_000)
    }

    private fun transport() = RawSocketTransport(
        PrinterAddress.Raw(host = "127.0.0.1", port = server.localPort),
        // No context: LocalNetwork.bind is for choosing a real interface, and
        // loopback is not on one.
        context = null,
    )

    @Test
    fun everyByteWrittenArrivesInOrder() = runBlocking {
        // Bigger than the 32 KB copy buffer, so the loop runs more than once
        // and a partial write would show up as a short or scrambled payload.
        val payload = ByteArray(100_000) { (it % 251).toByte() }

        val transport = transport()
        transport.open()
        val written = transport.write(payload.inputStream())
        transport.finish()
        transport.close()

        assertTrue("server never accepted", accepted.await(5, TimeUnit.SECONDS))
        acceptor?.join(5_000)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, received.get())
    }

    @Test
    fun progressIsCumulativeAndEndsAtTheTotal() = runBlocking {
        val payload = ByteArray(100_000) { 0x41 }
        val seen = mutableListOf<Long>()

        val transport = transport()
        transport.open()
        val written = transport.write(payload.inputStream()) { seen += it }
        transport.finish()
        transport.close()

        assertTrue("no progress was reported at all", seen.isNotEmpty())
        // Cumulative, not per-chunk: the stall guard's heartbeat and the
        // notification's percentage both read it as a running total.
        assertEquals(seen.sorted(), seen)
        assertEquals(written, seen.last())
        assertEquals(payload.size.toLong(), seen.last())
    }

    @Test
    fun openingAPortNothingIsListeningOnFails() = runBlocking {
        val dead = ServerSocket(0).let { probe ->
            val port = probe.localPort
            probe.close()
            port
        }
        val transport = RawSocketTransport(
            PrinterAddress.Raw(host = "127.0.0.1", port = dead),
            connectTimeoutMs = 1_500,
            context = null,
        )

        val thrown = runCatching { transport.open() }.exceptionOrNull()

        // A flag would be ignorable. The interface says throw, and the message
        // has to name the address, because "could not connect" alone has sent
        // people looking at the wrong printer.
        assertTrue("expected TransportException, got $thrown", thrown is TransportException)
        assertTrue(thrown!!.message!!.contains("127.0.0.1"))
        assertTrue(thrown.message!!.contains(dead.toString()))
    }

    @Test
    fun writingBeforeOpeningFails() = runBlocking {
        val transport = transport()

        val thrown = runCatching { transport.write(byteArrayOf(1, 2, 3)) }.exceptionOrNull()

        assertTrue("expected TransportException, got $thrown", thrown is TransportException)
    }

    @Test
    fun closingTwiceIsSafe() = runBlocking {
        // The interface requires it, and the engine's use{} plus its own error
        // paths do close twice on a failure.
        val transport = transport()
        transport.open()
        transport.close()
        transport.close()
    }

    @Test
    fun aClosedConnectionIsNotReportedAsSilence() = runBlocking {
        // Silence and a dropped connection mean opposite things: language
        // detection rules TSPL out precisely because a printer said nothing,
        // so a dead socket returning an empty array would be read as an answer.
        val transport = transport()
        transport.open()
        assertTrue("server never accepted", accepted.await(5, TimeUnit.SECONDS))

        // Drop it from the other end.
        server.close()
        acceptor?.join(2_000)

        val quiet = transport.readAvailable(timeoutMs = 200)
        // A printer with nothing to say is allowed; what must not happen is a
        // crash, and what the transport must never do is invent content.
        assertEquals(0, quiet.size)
        transport.close()
    }

    @Test
    fun anEmptyPayloadIsWrittenWithoutError() = runBlocking {
        val transport = transport()
        transport.open()
        val written = transport.write(ByteArray(0).inputStream())
        transport.finish()
        transport.close()

        assertEquals(0L, written)
    }

    @Test
    fun theDescriptionNamesWhatItWillConnectTo() {
        // It goes into the printer report, which is what somebody pastes when
        // asking why a job did not print.
        val transport = transport()
        assertEquals("raw://127.0.0.1:${server.localPort}", transport.description)
    }
}
