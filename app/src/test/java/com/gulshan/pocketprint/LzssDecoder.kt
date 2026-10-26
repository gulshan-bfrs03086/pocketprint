package com.gulshan.pocketprint

/**
 * What the printer does with compressed bitmap data, written from the format's
 * description and not from the encoder: Okumura's decoder, with a 4096-byte ring
 * of spaces, its write position at 4078, flag bits read from the low end with a
 * 1 meaning one literal byte and a 0 a two-byte reference.
 *
 * Shared by the tests that prove the encoder's output decodes.
 */
object LzssDecoder {

    /** The decoded bytes, and where in [compressed] the stream ended. */
    class Result(val bytes: ByteArray, val end: Int)

    fun decode(compressed: ByteArray, start: Int, size: Int): Result {
        val ring = ByteArray(4096) { 0x20 }
        var r = 4096 - 18
        val out = ByteArray(size)
        var produced = 0
        var p = start
        var flags = 0
        while (produced < size) {
            flags = flags ushr 1
            if (flags and 256 == 0) flags = (compressed[p++].toInt() and 0xFF) or 0xFF00
            if (flags and 1 != 0) {
                val c = compressed[p++]
                out[produced++] = c
                ring[r] = c
                r = (r + 1) and 4095
            } else {
                var i = compressed[p++].toInt() and 0xFF
                var j = compressed[p++].toInt() and 0xFF
                i = i or ((j and 0xF0) shl 4)
                j = (j and 0x0F) + 2
                for (k in 0..j) {
                    if (produced >= size) break
                    val c = ring[(i + k) and 4095]
                    out[produced++] = c
                    ring[r] = c
                    r = (r + 1) and 4095
                }
            }
        }
        return Result(out, p)
    }

    fun decode(compressed: ByteArray, size: Int): ByteArray = decode(compressed, 0, size).bytes
}
