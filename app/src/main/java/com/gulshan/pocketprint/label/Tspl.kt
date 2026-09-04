package com.gulshan.pocketprint.label

import android.graphics.Bitmap
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.render.Raster
import java.io.ByteArrayOutputStream

/**
 * TSPL/TSPL2 builder for TSC and compatible label printers.
 *
 * Note the polarity flip: in a TSPL BITMAP command a 0 bit prints a dot, which
 * is the opposite of ZPL and ESC/POS.
 */
class Tspl(
    private val media: MediaSize = MediaSize.LABEL_4X6,
    private val dpi: Int = 203,
) {
    private val out = ByteArrayOutputStream(8 * 1024)

    private fun cmd(text: String) = apply {
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    /** Emits the standard preamble: media size, gap, direction, heat, speed. */
    fun setup(
        gapMm: Float = 3f,
        density: Int = 8,
        speed: Int = 4,
        direction: Int = 1,
        tearOff: Boolean = true,
    ) = apply {
        cmd("SIZE ${fmt(media.widthMm)} mm, ${fmt(media.heightMm)} mm")
        cmd("GAP ${fmt(gapMm)} mm, 0 mm")
        cmd("DIRECTION $direction")
        cmd("REFERENCE 0,0")
        cmd("OFFSET 0 mm")
        cmd("SPEED $speed")
        cmd("DENSITY ${density.coerceIn(0, 15)}")
        if (tearOff) cmd("SET TEAR ON")
        cmd("CLS")
    }

    fun clear() = cmd("CLS")

    /** Built-in bitmap fonts are "1".."5"; scalable fonts use "0". */
    fun text(
        x: Int,
        y: Int,
        content: String,
        font: String = "3",
        rotation: Int = 0,
        scaleX: Int = 1,
        scaleY: Int = 1,
    ) = cmd("""TEXT $x,$y,"$font",$rotation,$scaleX,$scaleY,"${escape(content)}"""")

    fun barcode(
        x: Int,
        y: Int,
        data: String,
        type: String = "128",
        height: Int = 80,
        readable: Int = 1,
        rotation: Int = 0,
        narrow: Int = 2,
        wide: Int = 2,
    ) = cmd(
        """BARCODE $x,$y,"$type",$height,$readable,$rotation,$narrow,$wide,"${escape(data)}"""",
    )

    fun qrCode(
        x: Int,
        y: Int,
        data: String,
        cellWidth: Int = 6,
        ecc: String = "M",
        rotation: Int = 0,
    ) = cmd("""QRCODE $x,$y,$ecc,$cellWidth,A,$rotation,"${escape(data)}"""")

    fun box(x: Int, y: Int, right: Int, bottom: Int, thickness: Int = 3) =
        cmd("BOX $x,$y,$right,$bottom,$thickness")

    fun line(x: Int, y: Int, width: Int, height: Int) = cmd("BAR $x,$y,$width,$height")

    /**
     * BITMAP x,y,widthBytes,height,mode,<binary>. Mode 0 overwrites.
     * The bitmap payload follows the newline-terminated header directly.
     */
    fun image(x: Int, y: Int, bitmap: Bitmap, dither: Boolean = true) = apply {
        val maxWidth = media.dotsWide(dpi)
        val scaled = EscPos.fitWidth(bitmap, minOf(bitmap.width, maxWidth))
        val w = scaled.width
        val h = scaled.height
        val bytesPerRow = (w + 7) / 8
        val packed = Raster.toPackedMono(scaled, dither = dither)

        // Recorded unconditionally: a population count over the packed buffer
        // is a handful of instructions per byte, and the ink percentage is the
        // one number that separates "the renderer produced nothing" from "the
        // printer did nothing with it" - which is the fork every blank-label
        // report starts at.
        var ink = 0
        for (b in packed) ink += Integer.bitCount(b.toInt() and 0xFF)
        com.gulshan.pocketprint.print.Diagnostics.record(
            "TsplDiag",
            "image src=${bitmap.width}x${bitmap.height} scaled=${w}x$h " +
                "bytesPerRow=$bytesPerRow maxWidth=$maxWidth dpi=$dpi " +
                "media=${media.id} inkBits=$ink of ${packed.size * 8} " +
                "(${"%.2f".format(ink * 100.0 / (packed.size * 8))}%)",
        )

        // TSPL prints a dot for a 0 bit, so invert our ink mask.
        for (i in packed.indices) packed[i] = packed[i].toInt().inv().toByte()

        out.write("BITMAP $x,$y,$bytesPerRow,$h,0,".toByteArray(Charsets.US_ASCII))
        out.write(packed)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        if (scaled !== bitmap) scaled.recycle()
    }

    fun print(sets: Int = 1, copies: Int = 1) = cmd("PRINT $sets,$copies")

    fun build(): ByteArray = out.toByteArray()

    private fun fmt(v: Float) = if (v == Math.floor(v.toDouble()).toFloat()) {
        v.toInt().toString()
    } else {
        v.toString()
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
