package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.Lzss
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The encoder is only right if a decoder that follows the format reproduces the
 * input, and the only decoder that matters is inside the printer. So this one is
 * written from the format's description, not from the encoder, and every test
 * here is "encode, decode with this, compare".
 */
class LzssTest {

    private fun decode(compressed: ByteArray, size: Int) = LzssDecoder.decode(compressed, size)

    private fun roundTrip(label: String, data: ByteArray): ByteArray {
        val compressed = Lzss.encode(data)
        assertArrayEquals("$label: decoded bytes differ from the input", data, decode(compressed, data.size))
        return compressed
    }

    @Test
    fun `whatever goes in comes back out`() {
        val random = Random(20261026)
        repeat(300) { round ->
            val size = random.nextInt(1, 6000)
            // A mix that exercises every path: runs, repeats at all distances,
            // random bytes, and the byte values the format treats specially.
            val data = ByteArray(size)
            var i = 0
            while (i < size) {
                val kind = random.nextInt(5)
                val length = minOf(size - i, random.nextInt(1, 300))
                when (kind) {
                    0 -> data.fill(0xFF.toByte(), i, i + length)
                    1 -> for (k in 0 until length) data[i + k] = random.nextInt(256).toByte()
                    2 -> data.fill(0x20, i, i + length)
                    3 -> if (i > 0) for (k in 0 until length) data[i + k] = data[maxOf(0, i - random.nextInt(1, 4090)) + (k % 18)]
                    else -> for (k in 0 until length) data[i + k] = (k * 7).toByte()
                }
                i += length
            }
            roundTrip("random input $round ($size bytes)", data)
        }
    }

    @Test
    fun `a run of one byte becomes a literal and a reference that copies itself`() {
        // The reason a white page costs a ninth of what it did: the reference
        // reads bytes it is itself still writing.
        val white = ByteArray(5000) { 0xFF.toByte() }

        val compressed = roundTrip("white", white)

        assertTrue("5000 white bytes became ${compressed.size}", compressed.size < 700)
    }

    @Test
    fun `input that does not compress is only slightly larger than it was`() {
        // One flag byte per eight literals: 12.5 % at worst.
        val random = Random(9)
        val noise = ByteArray(4000) { random.nextInt(256).toByte() }

        val compressed = roundTrip("noise", noise)

        assertTrue("${compressed.size} for ${noise.size}", compressed.size <= noise.size + noise.size / 8 + 2)
    }

    @Test
    fun `data that begins with spaces is matched against the ring's own spaces`() {
        // The ring starts full of 0x20, so the first bytes of a text can refer to
        // it. If the encoder forgot that, this would still round-trip - so check
        // it does not come out larger than literals either way.
        val text = ByteArray(200) { 0x20 }
        text[100] = 'A'.code.toByte()

        val compressed = roundTrip("spaces", text)

        assertTrue(compressed.size < 60)
    }

    @Test
    fun `a match can reach back almost the whole ring`() {
        // The same 40-byte phrase twice, 4000 bytes apart: inside the window, so
        // the second copy should be a reference and not 40 literals.
        val random = Random(4)
        val phrase = ByteArray(40) { random.nextInt(256).toByte() }
        val data = ByteArray(4100)
        for (k in data.indices) data[k] = random.nextInt(256).toByte()
        System.arraycopy(phrase, 0, data, 10, 40)
        System.arraycopy(phrase, 0, data, 4010, 40)

        val compressed = roundTrip("far match", data)

        // Same page with the second copy replaced by different bytes: that one
        // has nothing to refer to, so it is the size without the repeat.
        val different = data.copyOf()
        for (k in 4010 until 4050) different[k] = random.nextInt(256).toByte()
        val withoutRepeat = Lzss.encode(different)

        assertTrue(
            "the repeat should save bytes: ${compressed.size} vs ${withoutRepeat.size}",
            compressed.size < withoutRepeat.size - 20,
        )
    }

    @Test
    fun `a page-sized input round-trips, and quickly`() {
        // 4 x 6 in at 300 dpi: 153 bytes x 1800 rows. A phone does this inline
        // while a print job is waiting, so it must not be quadratic.
        val random = Random(2)
        val page = ByteArray(153 * 1800) { 0xFF.toByte() }
        repeat(60) {
            val x = random.nextInt(0, 120)
            val y = random.nextInt(0, 1760)
            for (row in y until y + 30) for (col in x until x + 30) page[row * 153 + col] = random.nextInt(256).toByte()
        }

        val started = System.nanoTime()
        val compressed = roundTrip("page", page)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue("took $millis ms", millis < 3_000)
        assertTrue("${page.size} -> ${compressed.size}", compressed.size < page.size / 3)
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertEquals(0, Lzss.encode(ByteArray(0)).size)
    }

    @Test
    fun `a single byte round-trips`() {
        roundTrip("one", byteArrayOf(0x7E))
    }
}
