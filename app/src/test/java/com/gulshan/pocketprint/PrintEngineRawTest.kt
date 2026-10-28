package com.gulshan.pocketprint

import android.content.ContextWrapper
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrintResult
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.print.PrintEngine
import com.gulshan.pocketprint.print.TsplStatus
import com.gulshan.pocketprint.render.RenderPipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A raw label job - the designer, the test page, calibration - reports what the
 * printer says about it, but only when it is told which language it is in. A
 * printer here is a real loopback socket that is ready before the job and out
 * of paper after it, so the one thing that differs between the two tests is
 * the language handed to printRaw.
 */
class PrintEngineRawTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val queries = AtomicInteger()

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private val printer get() = Printer(
        id = "raw:engine-test:${server.localPort}",
        displayName = "Loopback",
        address = PrinterAddress.Raw(InetAddress.getLoopbackAddress().hostAddress!!, server.localPort),
    )

    private fun engine(): PrintEngine {
        val context = ContextWrapper(null)
        // The words are made of the resource id, so what was said is comparable.
        return PrintEngine(context, RenderPipeline(context), string = { "string:$it" })
    }

    /** Ready to the first question, out of paper to every one after. */
    private fun printerThatRunsOutOfPaper() = thread {
        val peer = server.accept()
        val input = peer.getInputStream()
        val out = peer.getOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val asked = String(buf, 0, n, Charsets.ISO_8859_1).windowed(3).count {
                it.toByteArray(Charsets.ISO_8859_1).contentEquals(TsplStatus.QUERY)
            }
            repeat(asked) {
                val reply = if (queries.getAndIncrement() == 0) 0x00 else TsplStatus.PAPER_EMPTY
                out.write(reply); out.flush()
            }
        }
    }

    private fun print(language: PrintLanguage?): PrintResult = runBlocking {
        engine().printRaw(printer, "SIZE 1,1\r\nPRINT 1,1\r\n".toByteArray(), "Test page", PrintOptions(), language = language)
    }

    @Test
    fun `a raw TSPL label that finds the paper out is a failure and not a send`() {
        val serving = printerThatRunsOutOfPaper()

        val result = print(PrintLanguage.TSPL)

        assertTrue("$result", result is PrintResult.Failure)
        assertEquals("string:${R.string.tspl_fault_paper_empty}", (result as PrintResult.Failure).message)
        serving.join(3_000)
    }

    @Test
    fun `a raw label that does not say its language is sent and never questioned`() {
        val serving = printerThatRunsOutOfPaper()

        val result = print(null)

        assertTrue("$result", result is PrintResult.Sent)
        assertEquals("string:${R.string.unconfirmed_raw}", (result as PrintResult.Sent).reason)
        assertEquals("a question was written to a printer of unknown dialect", 0, queries.get())
        serving.join(3_000)
    }
}
