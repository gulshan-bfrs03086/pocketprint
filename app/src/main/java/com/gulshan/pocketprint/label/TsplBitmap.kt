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
     * @return the commands; empty for a page with no ink, which prints a blank
     *   label just as the full white bitmap did, for none of the bytes.
     */
    fun encode(packed: ByteArray, widthBytes: Int, height: Int, x: Int, y: Int): ByteArray {
        val blocks = InkBlocks.find(packed, widthBytes, height)
        val out = ByteArrayOutputStream(blocks.sumOf { it.widthBytes * it.height + InkBlocks.COMMAND_COST })

        for (block in blocks) {
            // BITMAP x,y,widthBytes,height,mode,<binary>. x is in dots, so a
            // block that starts at byte column 3 starts 24 dots in. Mode 0
            // overwrites, which is right: blocks never overlap.
            val header = "BITMAP ${x + block.xByte * 8},${y + block.y},${block.widthBytes},${block.height},0,"
            out.write(header.toByteArray(Charsets.US_ASCII))
            for (row in block.y until block.y + block.height) {
                out.write(packed, row * widthBytes + block.xByte, block.widthBytes)
            }
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }
}
