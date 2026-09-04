package com.gulshan.pocketprint

import com.gulshan.pocketprint.transport.TransportException
import com.gulshan.pocketprint.transport.readAvailableFrom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream

/**
 * The one platform change in Android 17 that this app cannot afford to get
 * wrong quietly.
 *
 * Up to targetSdk 36 a dropped RFCOMM socket throws here. From 37 it returns -1
 * instead — and language detection reads silence as an answer, because no reply
 * to the TSPL status command is exactly what rules TSPL out. Treat -1 as
 * silence and a connection dying mid-probe comes back as a confident statement
 * about which dialect the printer speaks.
 */
class ReadAvailableTest {

    /**
     * A stream that says it has bytes and then reports end of stream, which is
     * the pathological shape the platform change produces and which no ordinary
     * InputStream implementation exhibits.
     */
    private class DeadStream(private val prelude: ByteArray = ByteArray(0)) : InputStream() {
        private var offset = 0
        override fun available(): Int = 8
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (offset >= prelude.size) return -1
            val n = minOf(len, prelude.size - offset)
            System.arraycopy(prelude, offset, b, off, n)
            offset += n
            return n
        }
    }

    /** Connected, nothing to say. Every page printer, all the time. */
    private class QuietStream : InputStream() {
        override fun available(): Int = 0
        override fun read(): Int = throw UnsupportedOperationException()
    }

    private fun drain(input: InputStream, timeoutMs: Long = 60) =
        readAvailableFrom(input, timeoutMs, pollMs = 1)

    @Test
    fun `a reply is returned`() {
        val reply = "4B-2044PA".toByteArray()
        assertArrayEquals(reply, drain(DeadStream(reply)))
    }

    @Test
    fun `silence is an empty answer, not an error`() {
        // This is a legitimate result and the whole basis of dialect detection:
        // the interpreter that is not running does not answer its own status
        // command.
        assertEquals(0, drain(QuietStream()).size)
    }

    @Test
    fun `a stream that ended without saying anything is an error, not silence`() {
        val failure = assertThrows(TransportException::class.java) { drain(DeadStream()) }
        assertEquals("The printer closed the connection", failure.message)
    }

    @Test
    fun `a reply that arrived before the stream ended still counts`() {
        // The printer answered and then hung up. Hanging up afterwards does not
        // unsay the answer.
        val reply = "~HS ok".toByteArray()
        assertArrayEquals(reply, drain(DeadStream(reply)))
    }

    @Test
    fun `a read error is still best effort`() {
        val broken = object : InputStream() {
            override fun available(): Int = 4
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw java.io.IOException("bt socket read failed")
        }
        // An exception mid-read is not the end-of-stream signal and must not be
        // promoted into one; it reports nothing, as it always did.
        assertEquals(0, drain(broken).size)
    }
}
