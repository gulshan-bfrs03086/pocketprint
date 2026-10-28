package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.transport.RawSocketTransport
import com.gulshan.pocketprint.transport.TransportException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A 9100 printer answers a query across a network, so its reply is never there
 * the instant the question is written. readAvailable used to look once and
 * return, which made every Raw printer read as mute - and silence is how setup
 * rules a dialect out and how the status wait gives up on a printer.
 *
 * These use a real loopback socket: the behaviour is about time, and a fake
 * stream would only test the fake.
 */
class RawSocketReadTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private var peer: Socket? = null

    @After
    fun tearDown() {
        runCatching { peer?.close() }
        runCatching { server.close() }
    }

    private fun connect(): RawSocketTransport {
        val transport = RawSocketTransport(
            PrinterAddress.Raw(InetAddress.getLoopbackAddress().hostAddress!!, server.localPort),
        )
        runBlocking { transport.open() }
        peer = server.accept()
        return transport
    }

    @Test
    fun `a reply that arrives after the call started is returned`() {
        val transport = connect()
        thread {
            Thread.sleep(250)
            peer!!.getOutputStream().apply { write(byteArrayOf(0x00)); flush() }
        }

        val reply = runBlocking { transport.readAvailable(2_000) }

        assertArrayEquals("the reply was missed because nothing waited for it", byteArrayOf(0x00), reply)
        transport.close()
    }

    @Test
    fun `a printer that says nothing costs the timeout and no more`() {
        val transport = connect()
        val started = System.nanoTime()

        val reply = runBlocking { transport.readAvailable(300) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, reply.size)
        assertTrue("returned after ${tookMs}ms without waiting", tookMs >= 250)
        assertTrue("a silent printer held the call for ${tookMs}ms", tookMs < 1_500)
        transport.close()
    }

    @Test
    fun `a printer that hung up is an error, not silence`() {
        val transport = connect()
        peer!!.close()

        val failure = assertThrows(TransportException::class.java) {
            runBlocking { transport.readAvailable(2_000) }
        }

        assertEquals("The printer closed the connection", failure.message)
        transport.close()
    }

    @Test
    fun `reading before the connection is open is silence`() {
        val transport = RawSocketTransport(PrinterAddress.Raw("127.0.0.1", 9))

        assertEquals(0, runBlocking { transport.readAvailable(50) }.size)
    }
}

/**
 * Setup's language probe reads silence as an answer, so giving a Raw transport
 * a working readAvailable changes what it concludes about a 9100 printer. Both
 * directions are pinned: one that answers is now recognised, and one that never
 * does costs a bounded few seconds and not a hang.
 */
class RawSocketProbeTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun setup() = com.gulshan.pocketprint.print.PrinterAutoSetup(
        android.content.ContextWrapper(null),
    )

    private fun transport(): RawSocketTransport {
        val t = RawSocketTransport(
            PrinterAddress.Raw(InetAddress.getLoopbackAddress().hostAddress!!, server.localPort),
        )
        runBlocking { t.open() }
        return t
    }

    @Test
    fun `a 9100 printer that answers the TSPL status query is recognised as TSPL`() {
        val serving = thread {
            val peer = server.accept()
            val input = peer.getInputStream()
            val out = peer.getOutputStream()
            val buf = ByteArray(64)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n >= 3 && buf[0] == 0x1B.toByte() && buf[1] == 0x21.toByte()) {
                    Thread.sleep(120)
                    out.write(byteArrayOf(0x00)); out.flush()
                } else if (String(buf, 0, n).startsWith("~!T")) {
                    Thread.sleep(120)
                    out.write("TE200\r\n".toByteArray()); out.flush()
                }
            }
        }
        val t = transport()

        val detected = runBlocking { setup().detectLanguage(t) }

        assertEquals(com.gulshan.pocketprint.model.PrintLanguage.TSPL, detected?.language)
        t.close()
        serving.join(2_000)
    }

    @Test
    fun `a 9100 printer that never answers cannot stall setup for more than a few seconds`() {
        val silent = thread { runCatching { server.accept().getInputStream().readBytes() } }
        val t = transport()
        val started = System.nanoTime()

        val detected = runBlocking { setup().detectLanguage(t) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("a printer that said nothing was given a dialect", null, detected)
        assertTrue("the probe waited ${tookMs}ms on a silent printer", tookMs < 6_000)
        t.close()
        silent.join(2_000)
    }
}
