package com.gulshan.pocketprint

import com.gulshan.pocketprint.ipp.IppDecoder
import com.gulshan.pocketprint.ipp.IppOperation
import com.gulshan.pocketprint.ipp.IppRequest
import com.gulshan.pocketprint.ipp.IppStatus
import com.gulshan.pocketprint.ipp.IppTag
import com.gulshan.pocketprint.ipp.IppValue
import com.gulshan.pocketprint.ipp.PwgMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import com.gulshan.pocketprint.ipp.IppClient

class IppCodecTest {

    @Test
    fun `request header carries version operation and id`() {
        val bytes = IppRequest(IppOperation.GET_PRINTER_ATTRIBUTES, 42)
            .operationAttributes {
                charset("attributes-charset")
                naturalLanguage("attributes-natural-language")
                uri("printer-uri", "ipp://192.168.1.50:631/ipp/print")
            }
            .build()

        assertEquals(2, bytes[0].toInt())          // version major
        assertEquals(0, bytes[1].toInt())          // version minor
        assertEquals(0x00, bytes[2].toInt())
        assertEquals(0x0B, bytes[3].toInt())       // Get-Printer-Attributes
        assertEquals(42, readInt(bytes, 4))
        assertEquals(IppTag.OPERATION_ATTRIBUTES, bytes[8].toInt())
        assertEquals(IppTag.END_OF_ATTRIBUTES, bytes.last().toInt())
    }

    @Test
    fun `decoder reads multi-value attributes resolutions and ranges`() {
        val response = buildResponse {
            writeByte(IppTag.PRINTER_ATTRIBUTES)

            // A 1setOf keyword: the extra values carry an empty name.
            attribute(IppTag.KEYWORD, "media-supported", "iso_a4_210x297mm")
            attribute(IppTag.KEYWORD, "", "na_letter_8.5x11in")
            attribute(IppTag.KEYWORD, "", "om_label_100x150mm")

            attribute(IppTag.NAME_WITHOUT_LANGUAGE, "printer-make-and-model", "Acme LaserJet 9000")

            writeByte(IppTag.BOOLEAN)
            writeName("color-supported")
            writeShort(1)
            writeByte(1)

            writeByte(IppTag.RESOLUTION)
            writeName("printer-resolution-supported")
            writeShort(9)
            writeInt(600); writeInt(600); writeByte(3)

            writeByte(IppTag.RANGE_OF_INTEGER)
            writeName("copies-supported")
            writeShort(8)
            writeInt(1); writeInt(99)

            writeByte(IppTag.END_OF_ATTRIBUTES)
        }

        val decoded = IppDecoder.decode(response)

        assertTrue(decoded.isSuccess)
        assertEquals(IppStatus.SUCCESSFUL_OK, decoded.statusCode)
        assertEquals(7, decoded.requestId)

        val media = decoded["media-supported"]
        assertNotNull(media)
        assertEquals(3, media!!.values.size)
        assertEquals(
            listOf("iso_a4_210x297mm", "na_letter_8.5x11in", "om_label_100x150mm"),
            media.asStrings(),
        )

        assertEquals("Acme LaserJet 9000", decoded["printer-make-and-model"]?.asString())
        assertEquals(true, decoded["color-supported"]?.asBool())
        assertEquals(600, decoded["printer-resolution-supported"]?.asInt())

        val copies = decoded["copies-supported"]?.first as? IppValue.IntRangeValue
        assertEquals(1, copies?.lower)
        assertEquals(99, copies?.upper)
    }

    @Test
    fun `decoder survives an unknown value tag`() {
        val response = buildResponse {
            writeByte(IppTag.PRINTER_ATTRIBUTES)
            writeByte(0x39)                      // not a tag we model
            writeName("vendor-blob")
            writeShort(3)
            write(byteArrayOf(1, 2, 3))
            attribute(IppTag.KEYWORD, "printer-state-reasons", "none")
            writeByte(IppTag.END_OF_ATTRIBUTES)
        }

        val decoded = IppDecoder.decode(response)
        assertEquals("none", decoded["printer-state-reasons"]?.asString())
        assertTrue(decoded["vendor-blob"]?.first is IppValue.Raw)
    }

    @Test
    fun `an unsupported resolution is never sent as-is`() {
        // A printer refuses a job whose printer-resolution it does not list; it
        // does not round. This is what CUPS's reference implementation did with
        // the 300 dpi default on a printer that offers 600 only.
        assertEquals(600, IppClient.resolutionToSend(300, listOf(600)))
        assertEquals(300, IppClient.resolutionToSend(300, listOf(300, 600)))
        assertEquals(600, IppClient.resolutionToSend(500, listOf(300, 600, 1200)))
        assertEquals("ties go up", 600, IppClient.resolutionToSend(450, listOf(300, 600)))
        assertNull("unknown printer: ask for nothing rather than guess", IppClient.resolutionToSend(300, emptyList()))
    }

    @Test
    fun `pwg media names decode to physical sizes`() {
        val a4 = PwgMedia.parse("iso_a4_210x297mm")!!
        assertEquals(210_000, a4.widthMicrons)
        assertEquals(297_000, a4.heightMicrons)
        assertEquals("A4", a4.label)

        val letter = PwgMedia.parse("na_letter_8.5x11in")!!
        assertEquals(215_900, letter.widthMicrons)
        assertEquals(279_400, letter.heightMicrons)

        val label = PwgMedia.parse("om_label_100x150mm")!!
        assertEquals(100_000, label.widthMicrons)
        assertEquals(150_000, label.heightMicrons)

        assertNull(PwgMedia.parse("garbage"))
    }

    // --- helpers -------------------------------------------------------------

    private fun readInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun buildResponse(block: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeByte(2); out.writeByte(0)               // version
        out.writeShort(IppStatus.SUCCESSFUL_OK)          // status
        out.writeInt(7)                                  // request id
        out.block()
        out.flush()
        return buffer.toByteArray()
    }

    private fun DataOutputStream.writeName(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.attribute(tag: Int, name: String, value: String) {
        writeByte(tag)
        writeName(name)
        val v = value.toByteArray(Charsets.UTF_8)
        writeShort(v.size)
        write(v)
    }
}
