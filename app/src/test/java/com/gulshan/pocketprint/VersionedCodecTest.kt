package com.gulshan.pocketprint

import com.gulshan.pocketprint.data.VersionedCodec
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Losing somebody's saved printers is the worst thing this app can do to them
 * short of printing the wrong document, and the old code did it silently: a
 * decode failure became an empty list, the next save merged onto that empty
 * list, and the result was written back. These tests pin the two properties
 * that stop that — one bad record costs one record, and an unreadable payload
 * comes back as unreadable rather than as "nothing was saved".
 */
class VersionedCodecTest {

    private val codec = VersionedCodec(Printer.serializer(), currentVersion = 1)

    private fun printer(id: String, link: BtLink = BtLink.AUTO) = Printer(
        id = id,
        displayName = "Printer $id",
        address = PrinterAddress.Bluetooth(mac = "AA:BB:CC:DD:EE:FF", link = link),
    )

    @Test
    fun `round trips`() {
        val printers = listOf(printer("a"), printer("b"))
        val stored = codec.decode(codec.encode(printers))

        assertEquals(printers, stored.items)
        assertEquals(1, stored.fromVersion)
        assertTrue(stored.healthy)
    }

    @Test
    fun `a pinned certificate survives a round trip, and a record from before pins reads as unpinned`() {
        val pinned = Printer(
            id = "ipps",
            displayName = "Secure",
            address = PrinterAddress.Ipp(
                host = "192.168.1.50",
                secure = true,
                certificateSha256 = "f12ffa29adf7cb1f0f708bd73a80a90edb20dba96f3446c50e24d16c4f6e26fb",
            ),
        )
        val encoded = codec.encode(listOf(pinned))
        assertTrue(encoded, "f12ffa29" in encoded)
        assertEquals(pinned, codec.decode(encoded).items.single())

        // What every saved IPPS printer in the field looks like: written by this
        // same codec before the field existed, so the key is simply absent. It
        // must read as "not yet trusted", not as an unreadable record.
        val unpinned = pinned.copy(address = PrinterAddress.Ipp(host = "192.168.1.50", secure = true))
        val old = codec.encode(listOf(unpinned)).replace(""","certificateSha256":null""", "")
        assertFalse(old, "certificateSha256" in old)
        val read = codec.decode(old)
        assertTrue(read.healthy)
        assertNull((read.items.single().address as PrinterAddress.Ipp).certificateSha256)
        assertTrue((read.items.single().address as PrinterAddress.Ipp).secure)
    }

    @Test
    fun `a bare array from before versions existed is still read`() {
        // What every install in the field currently holds.
        val legacy = """[{"id":"a","displayName":"Old","address":""" +
            """{"type":"bluetooth","mac":"AA:BB:CC:DD:EE:FF"}}]"""

        val stored = codec.decode(legacy)

        assertEquals(1, stored.items.size)
        assertEquals("Old", stored.items.first().displayName)
        assertEquals(VersionedCodec.UNVERSIONED, stored.fromVersion)
        assertTrue(stored.healthy)
    }

    @Test
    fun `one unreadable record costs one record, not all of them`() {
        // A renamed or removed enum constant is the realistic version of this:
        // an update ships, one field no longer decodes, and before this change
        // every printer the user had saved went with it.
        val raw = codec.encode(listOf(printer("a"), printer("b", BtLink.CLASSIC), printer("c")))
            .replace("\"CLASSIC\"", "\"CARRIER_PIGEON\"")

        val stored = codec.decode(raw)

        assertEquals(listOf("a", "c"), stored.items.map { it.id })
        assertEquals(1, stored.dropped)
        assertFalse(stored.healthy)
    }

    @Test
    fun `a payload that cannot be parsed is handed back, not reported as empty`() {
        val raw = "{not json at all"
        val stored = codec.decode(raw)

        assertEquals(emptyList<Printer>(), stored.items)
        // The distinction that matters: the caller can tell "unreadable" from
        // "nothing saved", and so can quarantine it instead of writing over it.
        assertEquals(raw, stored.unreadable)
        assertFalse(stored.healthy)
    }

    @Test
    fun `an envelope missing its items is unreadable, not empty`() {
        val stored = codec.decode("""{"v":1}""")
        assertNotNull(stored.unreadable)
        assertFalse(stored.healthy)
    }

    @Test
    fun `nothing saved is not a problem`() {
        listOf(null, "", "   ").forEach { raw ->
            val stored = codec.decode(raw)
            assertTrue(stored.items.isEmpty())
            assertNull(stored.unreadable)
            assertTrue(stored.healthy)
        }
    }

    @Test
    fun `a payload from a newer build keeps whatever this build can still read`() {
        val future = codec.encode(listOf(printer("a"), printer("b")))
            .replace("\"v\":1", "\"v\":99")
            // A field this build has never heard of, which is the ordinary
            // shape of a newer record and must not cost anything.
            .replace("\"id\":\"a\"", "\"id\":\"a\",\"holographic\":true")

        val stored = codec.decode(future)

        assertEquals(listOf("a", "b"), stored.items.map { it.id })
        assertEquals(99, stored.fromVersion)
        assertTrue(stored.healthy)
    }

    @Test
    fun `a migration may drop a record it cannot carry forward`() {
        val dropping = VersionedCodec(
            Printer.serializer(),
            currentVersion = 2,
            migrate = { _, record -> record.takeUnless { it["id"].toString().contains("b") } },
        )
        val raw = codec.encode(listOf(printer("a"), printer("b")))

        val stored = dropping.decode(raw)

        assertEquals(listOf("a"), stored.items.map { it.id })
        assertEquals(1, stored.dropped)
    }
}
