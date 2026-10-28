package com.gulshan.pocketprint

import com.gulshan.pocketprint.transport.TscPortHandover
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * On Android 12 and newer the app connects a TSC printer itself and hands the
 * socket to TSC's library, because every openport the library has calls
 * cancelDiscovery() first, which there needs a scan permission the app does not
 * hold. These check the handover writes exactly what openport would have.
 */
class TscPortHandoverTest {

    /** Laid out the way TSCActivity is, as far as the handover cares. */
    @Suppress("unused")
    private class LikeTsc {
        private var btSocket: Any? = null
        private var OutStream: OutputStream? = null
        private var InStream: InputStream? = null
        @JvmField var IsConnected: Boolean = false

        fun socket() = btSocket
        fun out() = OutStream
        fun inp() = InStream
    }

    @Suppress("unused")
    private class MissingInput {
        private var btSocket: Any? = null
        private var OutStream: OutputStream? = null
        @JvmField var IsConnected: Boolean = false
    }

    @Suppress("unused")
    private class WrongTypes {
        private var btSocket: Any? = null
        private var OutStream: String? = null
        private var InStream: InputStream? = null
        @JvmField var IsConnected: Boolean = false
    }

    @Suppress("unused")
    private class StaticStreams {
        private var btSocket: Any? = null
        @JvmField var IsConnected: Boolean = false

        companion object {
            @JvmStatic private var OutStream: OutputStream? = null
            @JvmStatic private var InStream: InputStream? = null
        }
    }

    @Test
    fun `it leaves the library holding the socket and its streams, and connected`() {
        val target = LikeTsc()
        val socket = Any()
        val out = ByteArrayOutputStream()
        val inp = ByteArrayInputStream(ByteArray(0))

        TscPortHandover.of(LikeTsc::class.java)!!.install(target, socket, out, inp)

        assertSame(socket, target.socket())
        assertSame(out, target.out())
        assertSame(inp, target.inp())
        assertTrue(target.IsConnected)
    }

    @Test
    fun `a library missing a field it needs cannot be handed a socket`() {
        assertNull(TscPortHandover.of(MissingInput::class.java))
    }

    @Test
    fun `a field of the wrong type is not written into`() {
        assertNull(TscPortHandover.of(WrongTypes::class.java))
    }

    @Test
    fun `streams kept in static fields are not treated as this library's own`() {
        assertNull(TscPortHandover.of(StaticStreams::class.java))
    }

    // -- against the real jar, when the build has it -----------------------

    private fun realLibrary(): Class<*>? =
        runCatching { Class.forName("com.example.tscdll.TSCActivity", false, javaClass.classLoader) }
            .getOrNull()

    /**
     * The fields were read out of TSCActivity's bytecode; this is the check that the
     * jar this build was given has them. Skipped in a build without the jar.
     */
    @Test
    fun `the real TSC library can be handed a socket`() {
        val cls = realLibrary()
        assumeTrue("no tscsdk.jar in this build", cls != null)

        assertNotNull(TscPortHandover.of(cls!!))
    }

    /**
     * And that what is handed over is what the library then uses: its own
     * sendcommand writes into the stream given, and its own status and printername
     * read their answers from the one given - with no openport ever called.
     */
    @Test
    fun `the real library sends and asks through the streams it was handed`() {
        val cls = realLibrary()
        assumeTrue("no tscsdk.jar in this build", cls != null)
        val tsc = cls!!.getDeclaredConstructor().newInstance()
        val out = ByteArrayOutputStream()
        // ESC ! S is answered with a six-byte frame; @@@@ is "Ready".
        val inp = ByteArrayInputStream(byteArrayOf(0x02, 0x40, 0x40, 0x40, 0x40, 0x03))

        TscPortHandover.of(cls)!!.install(tsc, null, out, inp)

        val job = "PRINT 1\r\n".toByteArray()
        val sent = cls.getMethod("sendcommand", ByteArray::class.java).invoke(tsc, job)
        assertEquals("1", sent)
        assertArrayEquals(job, out.toByteArray())

        out.reset()
        val word = cls.getMethod("status", Int::class.javaPrimitiveType).invoke(tsc, 10)
        assertEquals("Ready", word)
        assertArrayEquals(byteArrayOf(0x1B, 0x21, 0x53), out.toByteArray())
    }
}
