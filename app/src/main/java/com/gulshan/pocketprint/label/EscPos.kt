package com.gulshan.pocketprint.label

import android.graphics.Bitmap
import com.gulshan.pocketprint.render.Raster
import java.io.ByteArrayOutputStream

/**
 * ESC/POS command builder for thermal receipt printers.
 * Reference: Epson ESC/POS command set, as cloned by essentially every 58 mm
 * and 80 mm printer on the market.
 */
class EscPos(private val dotsWide: Int = 576) {

    private val out = ByteArrayOutputStream(8 * 1024)

    enum class Align(val code: Int) { LEFT(0), CENTER(1), RIGHT(2) }

    fun initialize() = apply { out.write(byteArrayOf(0x1B, 0x40)) }          // ESC @

    fun align(a: Align) = apply { out.write(byteArrayOf(0x1B, 0x61, a.code.toByte())) }

    fun bold(on: Boolean) = apply {
        out.write(byteArrayOf(0x1B, 0x45, if (on) 1 else 0))
    }

    fun underline(on: Boolean) = apply {
        out.write(byteArrayOf(0x1B, 0x2D, if (on) 1 else 0))
    }

    /** GS ! n — width and height multipliers, each 1..8. */
    fun textSize(width: Int = 1, height: Int = 1) = apply {
        val w = (width.coerceIn(1, 8) - 1) shl 4
        val h = height.coerceIn(1, 8) - 1
        out.write(byteArrayOf(0x1D, 0x21, (w or h).toByte()))
    }

    fun text(value: String) = apply {
        out.write(value.toByteArray(Charsets.ISO_8859_1))
    }

    fun line(value: String = "") = apply { text(value).feed(1) }

    fun feed(lines: Int = 1) = apply {
        out.write(byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte()))
    }

    fun separator(char: Char = '-', width: Int = 48) = apply {
        line(char.toString().repeat(width))
    }

    /** GS k — Code128 barcode. Height and width are set beforehand. */
    fun barcode128(data: String, height: Int = 80, moduleWidth: Int = 2) = apply {
        out.write(byteArrayOf(0x1D, 0x68, height.coerceIn(1, 255).toByte()))     // GS h
        out.write(byteArrayOf(0x1D, 0x77, moduleWidth.coerceIn(2, 6).toByte()))  // GS w
        out.write(byteArrayOf(0x1D, 0x48, 2))                                    // HRI below
        // GS k 73 n  (function B, explicit length). n is a single byte, so the
        // payload must fit in 1..255 or the length silently wraps: a 254-char
        // input would encode as n = 0 and desynchronise the command stream.
        val payload = "{B$data".toByteArray(Charsets.US_ASCII)
        require(payload.size in 1..255) {
            "Code128 payload is ${payload.size} bytes; ESC/POS allows at most 253 characters"
        }
        out.write(byteArrayOf(0x1D, 0x6B, 73, payload.size.toByte()))
        out.write(payload)
    }

    /** GS ( k — QR code, model 2. */
    fun qrCode(data: String, moduleSize: Int = 6) = apply {
        val bytes = data.toByteArray(Charsets.UTF_8)
        // Select model 2.
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 4, 0, 49, 65, 50, 0))
        // Module size.
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 3, 0, 49, 67, moduleSize.coerceIn(1, 16).toByte()))
        // Error correction level M.
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 3, 0, 49, 69, 49))
        // Store data: length includes the two function bytes.
        val len = bytes.size + 3
        out.write(
            byteArrayOf(
                0x1D, 0x28, 0x6B,
                (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
                49, 80, 48,
            ),
        )
        out.write(bytes)
        // Print the symbol.
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 3, 0, 49, 81, 48))
    }

    /**
     * GS v 0 — raster bit image. Sent in horizontal bands because printers with
     * small line buffers reject a full-page image in one command.
     *
     * [skipBlankDpi] is the print head's resolution, and asking for it turns on
     * blank-band skipping: see [rasterBands]. Zero, the default, sends every band.
     */
    fun image(
        bitmap: Bitmap,
        bandHeight: Int = 128,
        dither: Boolean = true,
        skipBlankDpi: Int = 0,
    ) = apply {
        val scaled = fitWidth(bitmap, dotsWide)
        val bytesPerRow = (scaled.width + 7) / 8
        val packed = Raster.toPackedMono(scaled, dither = dither)
        rasterBands(packed, bytesPerRow, scaled.height, bandHeight, skipBlankDpi)
        if (scaled !== bitmap) scaled.recycle()
    }

    /**
     * The bands of an already packed page (1 = ink, a row is [bytesPerRow] bytes).
     *
     * With [skipBlankDpi] in 1..255, a band with no ink in it is not sent: the
     * paper is fed over it instead, with ESC J, and a run of blank bands is one
     * feed however long. The feed is the same number of dots the band would have
     * printed, so every inked row lands where it always did, and a blank tail is
     * still fed before whatever follows the image (the cut). The saving is the
     * whole band: a blank 80 mm band is 9 KB over the wire and 3 bytes here.
     *
     * ESC J counts in the printer's vertical motion unit, which is a model default
     * until GS P sets it, and the Epson default is 1/360 inch, not a dot. So the
     * unit is pinned to the head's own resolution before the first feed, and only
     * then. That is also why this cannot be left on in a stream that sets ESC 3:
     * its argument is in the same unit, and would change meaning under GS P. This
     * builder has no ESC 3, line feeds being ESC d at the printer's own spacing.
     *
     * A resolution over 255 does not fit GS P, so it skips nothing.
     */
    fun rasterBands(
        packed: ByteArray,
        bytesPerRow: Int,
        height: Int,
        bandHeight: Int = 128,
        skipBlankDpi: Int = 0,
    ) = apply {
        val skipping = skipBlankDpi in 1..255
        var unitPinned = false
        var pendingFeed = 0

        fun flushFeed() {
            if (pendingFeed == 0) return
            if (!unitPinned) {
                out.write(byteArrayOf(0x1D, 0x50, skipBlankDpi.toByte(), skipBlankDpi.toByte())) // GS P x y
                unitPinned = true
            }
            // ESC J n takes one byte, so a longer run is several feeds.
            while (pendingFeed > 0) {
                val n = minOf(pendingFeed, 255)
                out.write(byteArrayOf(0x1B, 0x4A, n.toByte())) // ESC J n
                pendingFeed -= n
            }
        }

        var y = 0
        while (y < height) {
            val rows = minOf(bandHeight, height - y)
            val from = y * bytesPerRow
            val to = from + rows * bytesPerRow
            if (skipping && isBlank(packed, from, to)) {
                pendingFeed += rows
            } else {
                flushFeed()
                out.write(
                    byteArrayOf(
                        0x1D, 0x76, 0x30, 0x00,
                        (bytesPerRow and 0xFF).toByte(), ((bytesPerRow shr 8) and 0xFF).toByte(),
                        (rows and 0xFF).toByte(), ((rows shr 8) and 0xFF).toByte(),
                    ),
                )
                out.write(packed, from, rows * bytesPerRow)
            }
            y += rows
        }
        flushFeed()
    }

    private fun isBlank(packed: ByteArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (packed[i].toInt() != 0) return false
        return true
    }

    /** GS V — partial cut, after feeding the paper clear of the head. */
    fun cut(feedLines: Int = 4) = apply {
        feed(feedLines)
        out.write(byteArrayOf(0x1D, 0x56, 66, 0))
    }

    fun openCashDrawer() = apply {
        out.write(byteArrayOf(0x1B, 0x70, 0, 25, (250).toByte()))
    }

    fun build(): ByteArray = out.toByteArray()

    companion object {
        /** Scales a bitmap so its width matches the print head, preserving ratio. */
        fun fitWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
            if (bitmap.width == targetWidth) return bitmap
            val height = Math.round(
                bitmap.height.toFloat() * targetWidth / bitmap.width.toFloat(),
            ).coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
        }
    }
}
