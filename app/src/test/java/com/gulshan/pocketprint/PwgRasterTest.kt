package com.gulshan.pocketprint

import com.gulshan.pocketprint.render.PwgRasterEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Round-trips the PWG raster line encoder against an independent decoder, so a
 * malformed packet cannot slip through unnoticed.
 */
class PwgRasterTest {

    @Test
    fun `round trips a solid page`() = roundTrip(width = 64, height = 20) { _, _ -> 0xFF.toByte() }

    @Test
    fun `round trips alternating pixels`() =
        roundTrip(width = 33, height = 7) { x, _ -> if (x % 2 == 0) 0 else 0xFF.toByte() }

    @Test
    fun `round trips a gradient with no repeats`() =
        roundTrip(width = 200, height = 5) { x, y -> ((x * 7 + y * 13) % 256).toByte() }

    @Test
    fun `round trips a long single run followed by one odd pixel`() =
        roundTrip(width = 130, height = 3) { x, _ -> if (x == 129) 0x11 else 0x22 }

    @Test
    fun `solid page compresses far below its raw size`() {
        val width = 512
        val height = 64
        val raw = ByteArray(width * height) { 0xFF.toByte() }
        val encoded = encode(raw, width, height, 1)
        assertTrue(
            "expected strong compression, got ${encoded.size} bytes for ${raw.size}",
            encoded.size < raw.size / 20,
        )
    }

    private fun roundTrip(width: Int, height: Int, pixel: (Int, Int) -> Byte) {
        val raw = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) raw[y * width + x] = pixel(x, y)
        }
        val encoded = encode(raw, width, height, 1)
        val decoded = decode(encoded, width, height, 1)
        assertArrayEquals(raw, decoded)
    }

    private fun encode(raw: ByteArray, width: Int, height: Int, bpp: Int): ByteArray {
        val sink = ByteArrayOutputStream()
        PwgRasterEncoder.encodeLines(sink, raw, width, height, bpp)
        return sink.toByteArray()
    }

    /** Independent reference decoder for the PWG line encoding. */
    private fun decode(data: ByteArray, width: Int, height: Int, bpp: Int): ByteArray {
        val out = ByteArray(width * height * bpp)
        var pos = 0
        var y = 0

        while (y < height) {
            val lineRepeat = data[pos++].toInt() and 0xFF
            val line = ByteArray(width * bpp)
            var x = 0

            while (x < width) {
                val count = data[pos++].toInt() and 0xFF
                if (count <= 127) {
                    val repeats = count + 1
                    val pixel = data.copyOfRange(pos, pos + bpp)
                    pos += bpp
                    repeat(repeats) {
                        System.arraycopy(pixel, 0, line, x * bpp, bpp)
                        x++
                    }
                } else {
                    val literals = 257 - count
                    System.arraycopy(data, pos, line, x * bpp, literals * bpp)
                    pos += literals * bpp
                    x += literals
                }
            }

            repeat(lineRepeat + 1) {
                if (y < height) {
                    System.arraycopy(line, 0, out, y * width * bpp, width * bpp)
                    y++
                }
            }
        }
        return out
    }
}

/**
 * The encoder switched from buffering a whole page to streaming horizontal
 * bands, on the argument that PWG line groups are self-contained and therefore
 * concatenating per-band output is byte-for-byte equivalent to one full pass.
 * These tests hold that argument to account.
 */
class PwgRasterBandingTest {

    @Test
    fun `banded encoding decodes to the same page as a single pass`() {
        val width = 97
        val height = 60
        val raw = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Bands of flat colour with detail at the seams, so a mishandled
                // band boundary shows up rather than hiding in uniform data.
                raw[y * width + x] = when {
                    y % 16 == 0 -> ((x * 5) % 256).toByte()
                    y % 16 < 8 -> 0xFF.toByte()
                    else -> 0x40
                }
            }
        }

        val whole = java.io.ByteArrayOutputStream()
        PwgRasterEncoder.encodeLines(whole, raw, width, height, 1)
        val wholeDecoded = decodePublic(whole.toByteArray(), width, height, 1)

        for (bandRows in listOf(1, 7, 16, 17, 59, 60)) {
            val banded = java.io.ByteArrayOutputStream()
            var y = 0
            while (y < height) {
                val rows = minOf(bandRows, height - y)
                val band = raw.copyOfRange(y * width, (y + rows) * width)
                PwgRasterEncoder.encodeLines(banded, band, width, rows, 1)
                y += rows
            }
            val bandedDecoded = decodePublic(banded.toByteArray(), width, height, 1)
            assertArrayEquals(
                "band height $bandRows did not reproduce the page",
                wholeDecoded,
                bandedDecoded,
            )
            assertArrayEquals("band height $bandRows lost pixel data", raw, bandedDecoded)
        }
    }

    @Test
    fun `banded rgb encoding round trips`() {
        val width = 40
        val height = 24
        val bpp = 3
        val raw = ByteArray(width * height * bpp)
        for (i in raw.indices) raw[i] = ((i * 31) % 256).toByte()

        val banded = java.io.ByteArrayOutputStream()
        var y = 0
        while (y < height) {
            val rows = minOf(5, height - y)
            val band = raw.copyOfRange(y * width * bpp, (y + rows) * width * bpp)
            PwgRasterEncoder.encodeLines(banded, band, width, rows, bpp)
            y += rows
        }
        assertArrayEquals(raw, decodePublic(banded.toByteArray(), width, height, bpp))
    }

    private fun decodePublic(data: ByteArray, width: Int, height: Int, bpp: Int): ByteArray {
        val out = ByteArray(width * height * bpp)
        var pos = 0
        var y = 0
        while (y < height) {
            val lineRepeat = data[pos++].toInt() and 0xFF
            val line = ByteArray(width * bpp)
            var x = 0
            while (x < width) {
                val count = data[pos++].toInt() and 0xFF
                if (count <= 127) {
                    val pixel = data.copyOfRange(pos, pos + bpp)
                    pos += bpp
                    repeat(count + 1) {
                        System.arraycopy(pixel, 0, line, x * bpp, bpp)
                        x++
                    }
                } else {
                    val literals = 257 - count
                    System.arraycopy(data, pos, line, x * bpp, literals * bpp)
                    pos += literals * bpp
                    x += literals
                }
            }
            repeat(lineRepeat + 1) {
                if (y < height) {
                    System.arraycopy(line, 0, out, y * width * bpp, width * bpp)
                    y++
                }
            }
        }
        return out
    }
}
