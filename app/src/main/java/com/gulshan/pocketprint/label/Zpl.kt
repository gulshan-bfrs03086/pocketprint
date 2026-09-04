package com.gulshan.pocketprint.label

import android.graphics.Bitmap
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.render.Raster

/**
 * ZPL II builder for Zebra and compatible label printers.
 * A label is bracketed by ^XA and ^XZ; a 1 bit in ^GFA prints a dot.
 */
class Zpl(
    private val media: MediaSize = MediaSize.LABEL_4X6,
    private val dpi: Int = 203,
) {
    private val sb = StringBuilder(8 * 1024)

    fun start(density: Int = 10, speed: Int = 4) = apply {
        sb.append("^XA")
        sb.append("^PW").append(media.dotsWide(dpi))
        sb.append("^LL").append(media.dotsHigh(dpi))
        sb.append("^LH0,0")
        sb.append("^MD").append(density.coerceIn(-30, 30))
        sb.append("^PR").append(speed.coerceIn(1, 14))
        sb.append("^CI28") // UTF-8 input
    }

    /**
     * The preamble for a printer loaded with a particular stock.
     *
     * Darkness goes out as ~SD, which is absolute and is the same value as the
     * front panel, rather than ^MD, which is an offset from whatever the
     * printer happens to be set to. Absolute means the same label prints the
     * same on the next unit; the cost is that it changes the printer's own
     * stored setting, which is exactly what turning the front-panel dial does.
     */
    fun start(stock: LabelStock) = apply {
        sb.append("~SD").append("%02d".format(stock.darknessForZpl))
        sb.append("^XA")
        sb.append("^PW").append(media.dotsWide(dpi))
        sb.append("^LL").append(media.dotsHigh(dpi))
        sb.append("^LH0,0")
        sb.append(
            when (stock.sensing) {
                MediaSensing.GAP -> "^MNY"
                MediaSensing.BLACK_MARK -> "^MNM"
                MediaSensing.CONTINUOUS -> "^MNN"
            },
        )
        sb.append("^PR").append(stock.speedIps.coerceIn(1, 14))
        sb.append("^CI28") // UTF-8 input
    }

    /**
     * ~JC runs the printer's own media calibration, which feeds a few labels
     * while it works out where the gaps are.
     */
    fun calibrate(stock: LabelStock) = apply {
        sb.append("^XA")
        sb.append(
            when (stock.sensing) {
                MediaSensing.GAP -> "^MNY"
                MediaSensing.BLACK_MARK -> "^MNM"
                MediaSensing.CONTINUOUS -> "^MNN"
            },
        )
        sb.append("^XZ")
        sb.append("~JC")
    }

    /** ^A0 is the scalable font; height and width are in dots. */
    fun text(x: Int, y: Int, content: String, height: Int = 30, width: Int = 30) = apply {
        sb.append("^FO").append(x).append(',').append(y)
        sb.append("^A0N,").append(height).append(',').append(width)
        sb.append("^FD").append(escape(content)).append("^FS")
    }

    fun barcode128(
        x: Int,
        y: Int,
        data: String,
        height: Int = 100,
        moduleWidth: Int = 2,
        printInterpretation: Boolean = true,
    ) = apply {
        sb.append("^FO").append(x).append(',').append(y)
        sb.append("^BY").append(moduleWidth.coerceIn(1, 10))
        sb.append("^BCN,").append(height).append(',')
            .append(if (printInterpretation) "Y" else "N").append(",N,N")
        sb.append("^FD").append(escape(data)).append("^FS")
    }

    fun qrCode(x: Int, y: Int, data: String, magnification: Int = 5) = apply {
        sb.append("^FO").append(x).append(',').append(y)
        sb.append("^BQN,2,").append(magnification.coerceIn(1, 10))
        // "QA," selects automatic mode with error correction level A.
        sb.append("^FDQA,").append(escape(data)).append("^FS")
    }

    fun box(x: Int, y: Int, width: Int, height: Int, thickness: Int = 3) = apply {
        sb.append("^FO").append(x).append(',').append(y)
        sb.append("^GB").append(width).append(',').append(height).append(',')
            .append(thickness).append("^FS")
    }

    /** ^GFA,totalBytes,totalBytes,bytesPerRow,<hex>. */
    fun image(x: Int, y: Int, bitmap: Bitmap, dither: Boolean = true) = apply {
        val maxWidth = media.dotsWide(dpi)
        val scaled = EscPos.fitWidth(bitmap, minOf(bitmap.width, maxWidth))
        val w = scaled.width
        val h = scaled.height
        val bytesPerRow = (w + 7) / 8
        val packed = Raster.toPackedMono(scaled, dither = dither)
        val total = bytesPerRow * h

        sb.append("^FO").append(x).append(',').append(y)
        sb.append("^GFA,").append(total).append(',').append(total).append(',')
            .append(bytesPerRow).append(',')
        packed.forEach { sb.append(HEX[(it.toInt() ushr 4) and 0xF]).append(HEX[it.toInt() and 0xF]) }
        sb.append("^FS")

        if (scaled !== bitmap) scaled.recycle()
    }

    fun end(copies: Int = 1) = apply {
        if (copies > 1) sb.append("^PQ").append(copies)
        sb.append("^XZ")
    }

    fun build(): ByteArray = sb.toString().toByteArray(Charsets.UTF_8)

    private fun escape(s: String) =
        s.replace("^", " ").replace("~", " ")

    private companion object {
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}
