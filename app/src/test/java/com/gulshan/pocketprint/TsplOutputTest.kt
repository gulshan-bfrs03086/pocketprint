package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.Tspl
import com.gulshan.pocketprint.model.MediaSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the exact TSPL byte stream the app emits.
 *
 * These commands were verified against a real 4BARCODE 4B-2044PA over Bluetooth
 * SPP, which identifies itself as "4B-2044PA" in response to ~!T.
 */
class TsplOutputTest {

    private fun testLabel(): ByteArray = Tspl(MediaSize.LABEL_4X6, 203).apply {
        setup()
        text(20, 20, "POCKETPRINT READY", font = "3")
        text(20, 70, "4B-2044PA-5A91", font = "2")
        text(20, 105, "TSPL  " + MediaSize.LABEL_4X6.label, font = "2")
        barcode(20, 145, "POCKETPRINT", height = 60)
        print(sets = 1, copies = 1)
    }.build()

    @Test
    fun `setup preamble matches the TSPL command set`() {
        val text = String(testLabel(), Charsets.ISO_8859_1)
        val lines = text.split("\r\n")

        // 4 x 6 inches, not 100 x 150 mm: 2.4 mm short breaks gap detection.
        assertEquals("SIZE 101.6 mm, 152.4 mm", lines[0])
        assertEquals("GAP 3 mm, 0 mm", lines[1])
        assertEquals("DIRECTION 1", lines[2])
        assertEquals("REFERENCE 0,0", lines[3])
        assertEquals("OFFSET 0 mm", lines[4])
        assertEquals("SPEED 4", lines[5])
        assertEquals("DENSITY 8", lines[6])
        assertEquals("SET TEAR ON", lines[7])
        assertEquals("CLS", lines[8])
    }

    @Test
    fun `text and barcode commands are well formed`() {
        val text = String(testLabel(), Charsets.ISO_8859_1)

        assertTrue(text.contains("""TEXT 20,20,"3",0,1,1,"POCKETPRINT READY""""))
        assertTrue(text.contains("""TEXT 20,70,"2",0,1,1,"4B-2044PA-5A91""""))
        assertTrue(text.contains("""BARCODE 20,145,"128",60,1,0,2,2,"POCKETPRINT""""))
        assertTrue(text.trimEnd().endsWith("PRINT 1,1"))
    }

    @Test
    fun `every command line is CRLF terminated`() {
        val text = String(testLabel(), Charsets.ISO_8859_1)
        // TSPL firmware is strict about this; a bare LF is silently ignored.
        assertTrue(text.endsWith("\r\n"))
        assertEquals(0, Regex("(?<!\r)\n").findAll(text).count())
    }

    @Test
    fun `quotes in user text are escaped so the command cannot be broken`() {
        val out = Tspl(MediaSize.LABEL_4X6, 203).apply {
            text(0, 0, """He said "hi"""")
        }.build()
        assertTrue(String(out, Charsets.ISO_8859_1).contains("""\"hi\""""))
    }

    /** Writes the byte-for-byte payload so it can be sent to real hardware. */
    @Test
    fun `emit hardware fixture`() {
        val target = File("build/hardware-fixture-tspl.bin")
        target.parentFile?.mkdirs()
        target.writeBytes(testLabel())
        assertTrue(target.length() > 0)
    }
}
