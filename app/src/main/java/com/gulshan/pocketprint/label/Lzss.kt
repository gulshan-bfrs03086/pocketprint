package com.gulshan.pocketprint.label

/**
 * LZSS as Haruhiko Okumura published it, which is what TSC's printers decode.
 *
 * The parameters are the algorithm's own and have to match the printer's
 * decoder exactly, or the printer draws garbage from perfectly good data:
 *
 *  - a 4096-byte ring buffer, filled with spaces (0x20) before the first byte,
 *    whose write position starts at 4096 - 18;
 *  - the stream is groups of eight items behind a flag byte, read from its low
 *    bit up: a 1 is one literal byte, a 0 is a two-byte reference;
 *  - a reference is `position & 0xFF`, then `((position >> 4) & 0xF0) |
 *    (length - 3)`, where position is an index into the ring buffer (not a
 *    distance) and length runs 3..18;
 *  - a reference may run into bytes the same reference is writing, which is how
 *    one byte followed by a reference makes a run of any length.
 *
 * There is no end marker: the printer knows how many bytes the bitmap is
 * (width in bytes times height) and stops when it has them.
 *
 * Only the encoder lives here. The decoder is in the tests, written from the
 * description above, because it is the printer's job in production and the only
 * reason to have one is to prove this output decodes.
 *
 * ## Why it is worth having
 *
 * A bitmap of a label is long runs of 0xFF. A run of 18 white bytes becomes two,
 * so blank space costs a ninth of what it did - and the measured limit on a
 * Bluetooth label printer is bytes per second, whatever they contain.
 */
object Lzss {

    private const val RING = 4096
    private const val MAX_MATCH = 18
    private const val MIN_MATCH = 3
    private const val SPACE: Byte = 0x20

    /**
     * How far back a reference may reach. The ring holds [RING] bytes, but the
     * longest match is read from the bytes just ahead of the write position, so
     * the furthest source that is certain to be intact is [RING] - [MAX_MATCH].
     */
    private const val WINDOW = RING - MAX_MATCH

    /** How many earlier positions with the same three bytes to try. Bounds the work per byte. */
    private const val MAX_CHAIN = 48

    private const val HASH_BITS = 13

    fun encode(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)

        // The ring's initial contents are part of the history: spaces, then the
        // data. A byte at index k of this array lives at ring position k % RING.
        val history = ByteArray(WINDOW + data.size)
        java.util.Arrays.fill(history, 0, WINDOW, SPACE)
        System.arraycopy(data, 0, history, WINDOW, data.size)

        val head = IntArray(1 shl HASH_BITS) { -1 }
        val previous = IntArray(history.size) { -1 }
        fun hash(k: Int): Int {
            val h = (history[k].toInt() and 0xFF) or
                ((history[k + 1].toInt() and 0xFF) shl 8) or
                ((history[k + 2].toInt() and 0xFF) shl 16)
            return (h * 0x9E3779B1.toInt()) ushr (32 - HASH_BITS)
        }
        fun insert(k: Int) {
            if (k + 2 >= history.size) return
            val h = hash(k)
            previous[k] = head[h]
            head[h] = k
        }
        // Index the spaces too, so a run of them at the start can be matched.
        for (k in 0 until WINDOW) insert(k)

        val out = java.io.ByteArrayOutputStream(data.size / 4 + 16)
        val group = ByteArray(1 + 8 * 2)
        var flags = 0
        var items = 0
        var groupLength = 1

        fun flush() {
            if (items == 0) return
            group[0] = flags.toByte()
            out.write(group, 0, groupLength)
            flags = 0
            items = 0
            groupLength = 1
        }

        var at = WINDOW
        val end = history.size
        while (at < end) {
            var bestLength = 0
            var bestSource = 0
            val limit = minOf(MAX_MATCH, end - at)

            if (limit >= MIN_MATCH) {
                var candidate = head[hash(at)]
                var tries = 0
                while (candidate >= 0 && at - candidate <= WINDOW && tries < MAX_CHAIN) {
                    var length = 0
                    while (length < limit && history[candidate + length] == history[at + length]) length++
                    if (length > bestLength) {
                        bestLength = length
                        bestSource = candidate
                        if (length == limit) break
                    }
                    candidate = previous[candidate]
                    tries++
                }
            }

            if (bestLength >= MIN_MATCH) {
                val position = bestSource % RING
                group[groupLength++] = (position and 0xFF).toByte()
                group[groupLength++] = (((position shr 4) and 0xF0) or (bestLength - MIN_MATCH)).toByte()
                for (k in 0 until bestLength) insert(at + k)
                at += bestLength
            } else {
                flags = flags or (1 shl items)
                group[groupLength++] = history[at]
                insert(at)
                at++
            }
            if (++items == 8) flush()
        }
        flush()
        return out.toByteArray()
    }
}
