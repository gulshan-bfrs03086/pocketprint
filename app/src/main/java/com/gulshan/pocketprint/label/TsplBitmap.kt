package com.gulshan.pocketprint.label

import java.io.ByteArrayOutputStream

/**
 * A packed page as TSPL BITMAP commands: one per block of [InkBlocks], and
 * nothing at all for the white between them.
 *
 * Pure bytes in, bytes out, so the one property that matters - the printer
 * ends up with exactly the page that went in - can be checked on the JVM by
 * redrawing the commands (TsplBitmapTest does).
 */
object TsplBitmap {

    /**
     * @param packed the page in TSPL polarity (0 bit = dot), [widthBytes] per row.
     * @param x where the page's left edge sits on the label, in dots.
     * @param y where its top edge sits, in dots.
     * @param compress send each block as TSC's LZSS-compressed bitmap command
     *   instead of plain BITMAP, wherever that is smaller. Only for a printer
     *   that decodes it (see PrinterCapabilities.compressedBitmaps).
     * @return the commands; empty for a page with no ink, which prints a blank
     *   label just as the full white bitmap did, for none of the bytes.
     */
    fun encode(
        packed: ByteArray,
        widthBytes: Int,
        height: Int,
        x: Int,
        y: Int,
        compress: Boolean = false,
    ): ByteArray {
        val blocks = InkBlocks.find(packed, widthBytes, height)
        val out = ByteArrayOutputStream(blocks.sumOf { it.widthBytes * it.height + InkBlocks.COMMAND_COST })

        for (block in blocks) {
            // x is in dots, so a block that starts at byte column 3 starts 24
            // dots in.
            val xDots = x + block.xByte * 8
            val yDots = y + block.y
            val raw = ByteArray(block.widthBytes * block.height)
            for (row in 0 until block.height) {
                System.arraycopy(
                    packed, (block.y + row) * widthBytes + block.xByte,
                    raw, row * block.widthBytes, block.widthBytes,
                )
            }

            val plain = "BITMAP $xDots,$yDots,${block.widthBytes},${block.height},0,"
                .toByteArray(Charsets.US_ASCII)
            val squeezed = if (compress) Lzss.encode(raw) else null

            // The header's fields are 16 bits, and a value that does not fit would
            // wrap silently into a different picture. No page this app makes is
            // that large, but plain BITMAP states its numbers in decimal.
            val fits = maxOf(xDots, yDots, block.widthBytes, block.height) <= MAX_COMPRESSED_FIELD && minOf(xDots, yDots) >= 0

            if (squeezed != null && fits && COMPRESSED_HEADER + squeezed.size < plain.size + raw.size) {
                out.write(compressedHeader(xDots, yDots, block.widthBytes, block.height))
                out.write(squeezed)
            } else {
                // BITMAP x,y,widthBytes,height,mode,<binary>. Mode 0 overwrites,
                // which is right: blocks never overlap. Also what a block that
                // does not compress, such as a photograph, falls back to.
                out.write(plain)
                out.write(raw)
            }
            out.write(CRLF)
        }
        return out.toByteArray()
    }

    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

    /** The 16 bytes in front of compressed data: see [compressedHeader]. */
    private const val COMPRESSED_HEADER = 16

    /** The largest number a 16-bit header field holds. */
    private const val MAX_COMPRESSED_FIELD = 0xFFFF

    /**
     * TSC's compressed bitmap command, as the official utility sends it: `!S`,
     * then x, y, width in bytes and height as 16-bit little-endian numbers,
     * zero-padded to 16 bytes, then the LZSS data, then CR LF. There is no
     * length: the printer decodes until it has width x height bytes.
     *
     * The pixels are in the same polarity as BITMAP - a 0 bit is a dot - so the
     * packed page goes in unchanged.
     */
    private fun compressedHeader(xDots: Int, yDots: Int, widthBytes: Int, height: Int): ByteArray {
        val header = ByteArray(COMPRESSED_HEADER)
        header[0] = '!'.code.toByte()
        header[1] = 'S'.code.toByte()
        fun put(at: Int, value: Int) {
            header[at] = (value and 0xFF).toByte()
            header[at + 1] = ((value shr 8) and 0xFF).toByte()
        }
        put(2, xDots)
        put(4, yDots)
        put(6, widthBytes)
        put(8, height)
        return header
    }
}
