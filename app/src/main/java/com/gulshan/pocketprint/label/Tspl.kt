package com.gulshan.pocketprint.label

import android.graphics.Bitmap
import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
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
    /** Send bitmaps as TSC's compressed command; see PrinterCapabilities.compressedBitmaps. */
    private val compressBitmaps: Boolean = false,
) {
    private val out = ByteArrayOutputStream(8 * 1024)

    private fun cmd(text: String) = apply {
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    /** Emits the standard preamble: media size, sensing, heat, speed. */
    fun setup(
        gapMm: Float = 3f,
        density: Int = 8,
        speed: Int = 4,
        direction: Int = 1,
        tearOff: Boolean = true,
    ) = setup(
        LabelStock(gapMm = gapMm, darkness = density, speedIps = speed),
        direction = direction,
        tearOff = tearOff,
    )

    /**
     * The preamble for a printer loaded with a particular stock.
     *
     * Sensing is the part that decides whether anything works at all. A printer
     * told to look for a gap in continuous stock feeds forward hunting for one
     * that is not there and stops with a paper fault; one told to look for a
     * gap when the roll uses black marks does the same. There is no way to
     * detect this from the protocol, which is why it is a setting.
     */
    fun setup(
        stock: LabelStock,
        direction: Int = 1,
        tearOff: Boolean = true,
        /** The page's length in mm, as SIZE takes it, when it is not the media's. */
        pageHeightMm: String? = null,
    ) = apply {
        cmd("SIZE ${fmt(media.widthMm)} mm, ${pageHeightMm ?: fmt(media.heightMm)} mm")
        when (stock.sensing) {
            MediaSensing.GAP ->
                cmd("GAP ${fmt(stock.gapMm)} mm, ${fmt(stock.offsetMm)} mm")
            MediaSensing.BLACK_MARK ->
                cmd("BLINE ${fmt(stock.gapMm)} mm, ${fmt(stock.offsetMm)} mm")
            // Zero gap is how TSPL is told to stop looking for one.
            MediaSensing.CONTINUOUS -> cmd("GAP 0 mm, 0 mm")
        }
        cmd("DIRECTION $direction")
        cmd("REFERENCE 0,0")
        cmd("OFFSET 0 mm")
        cmd("SPEED ${stock.speedIps.coerceIn(1, 12)}")
        cmd("DENSITY ${stock.darknessForTspl}")
        if (tearOff) cmd("SET TEAR ON")
        cmd("CLS")
    }

    /**
     * Asks the printer to find the stock itself, feeding a few labels doing it.
     *
     * Worth having as a button because the alternative, when registration
     * drifts, is a roll of labels printed half on one and half on the next
     * while somebody guesses at gap heights.
     */
    fun calibrate(stock: LabelStock) = apply {
        cmd("SIZE ${fmt(media.widthMm)} mm, ${fmt(media.heightMm)} mm")
        when (stock.sensing) {
            MediaSensing.GAP -> {
                cmd("GAP ${fmt(stock.gapMm)} mm, ${fmt(stock.offsetMm)} mm")
                cmd("GAPDETECT")
            }
            MediaSensing.BLACK_MARK -> {
                cmd("BLINE ${fmt(stock.gapMm)} mm, ${fmt(stock.offsetMm)} mm")
                cmd("BLINEDETECT")
            }
            // Nothing to find: there are no marks. Feeding one label length is
            // still useful confirmation that the size is right.
            MediaSensing.CONTINUOUS -> {
                cmd("GAP 0 mm, 0 mm")
                cmd("FEED ${media.dotsHigh(dpi)}")
            }
        }
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
     * Sends the image as BITMAP x,y,widthBytes,height,0,<binary> CR LF, one per
     * rectangle that has ink in it (see [InkBlocks]), and nothing for the white.
     *
     * That means the image's white margin does not erase anything: it used to be
     * one BITMAP that overwrote its whole rectangle, and a caller that drew TEXT
     * or a BOX first and relied on that would now see them show through. Draw an
     * image first, or keep it clear of other drawing.
     */
    fun image(x: Int, y: Int, bitmap: Bitmap, dither: Boolean = true) = apply {
        val page = pack(bitmap, dither)
        bitmap(x, y, page.packed, page.widthBytes, page.height)
    }

    /**
     * The page as TSPL wants it: scaled to the head, one bit per dot, a 0 bit
     * being a dot. Nothing is written; [image] and [page] decide what to send.
     */
    fun pack(bitmap: Bitmap, dither: Boolean = true): PackedPage {
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

        if (scaled !== bitmap) scaled.recycle()
        return PackedPage(packed, bytesPerRow, h)
    }

    /**
     * One label: the preamble, the page, and PRINT.
     *
     * On continuous stock the page is cut off below its last ink (see
     * [TrimBlank]) and SIZE says so, so the roll is expected to feed only that
     * far. A different SIZE for each label of one job was accepted by an RE310,
     * which reported the job finished; that the paper came out those lengths is
     * for whoever holds it to check. On any other stock this is the page at the
     * size that was picked, exactly as it always was.
     *
     * Only the rectangles that have ink in them are sent (see [bitmap]). The
     * page used to go out whole - 45 KB for a 75 x 75 mm label - and a white byte
     * costs as much to send as a black one; see InkBlocks for what that cost on a
     * printer that takes in a few hundred bytes a second.
     */
    fun page(stock: LabelStock, page: PackedPage, sets: Int = 1, copies: Int = 1) = apply {
        if (TrimBlank.applies(stock)) {
            val keep = TrimBlank.rowsToKeep(page.packed, page.widthBytes, page.height, dpi)
            setup(stock, pageHeightMm = TrimBlank.mmText(keep, dpi))
            bitmap(0, 0, page.packed.copyOf(page.widthBytes * keep), page.widthBytes, keep)
            com.gulshan.pocketprint.print.Diagnostics.record(
                "TsplDiag",
                "continuous stock: page trimmed to $keep of ${page.height} rows " +
                    "(${TrimBlank.mmText(keep, dpi)} mm)",
            )
        } else {
            setup(stock)
            bitmap(0, 0, page.packed, page.widthBytes, page.height)
        }
        print(sets = sets, copies = copies)
    }

    /**
     * A page that is already packed, a 0 bit being a dot, sent as the commands
     * [TsplBitmap] makes of it. [image] ends here; the test page calls it too.
     */
    fun bitmap(x: Int, y: Int, packed: ByteArray, widthBytes: Int, height: Int) = apply {
        val commands = TsplBitmap.encode(packed, widthBytes, height, x, y, compressBitmaps)
        out.write(commands)
        com.gulshan.pocketprint.print.Diagnostics.record(
            "TsplDiag",
            "sending ${commands.size} bytes of bitmap commands${if (compressBitmaps) " (compressed)" else ""} instead of " +
                "${packed.size} (${"%.1f".format(commands.size * 100.0 / packed.size)}%)",
        )
    }

    /**
     * A 96 x 48 dot picture to print on a test label: a frame, a diagonal, and a
     * solid square in the top left. It is lopsided on purpose, so a picture that
     * came out mirrored, upside down or inverted - which is what a printer that
     * misreads the compressed command does - cannot be mistaken for a good one.
     */
    fun testPicture(x: Int, y: Int) = apply {
        val widthBytes = TEST_PICTURE_BYTES
        val packed = ByteArray(widthBytes * TEST_PICTURE_HEIGHT) { 0xFF.toByte() }
        fun dot(px: Int, py: Int) {
            val at = py * widthBytes + px / 8
            packed[at] = (packed[at].toInt() and (0x80 shr (px % 8)).inv()).toByte()
        }
        val width = widthBytes * 8
        for (px in 0 until width) for (t in 0..1) { dot(px, t); dot(px, TEST_PICTURE_HEIGHT - 1 - t) }
        for (py in 0 until TEST_PICTURE_HEIGHT) for (t in 0..1) { dot(t, py); dot(width - 1 - t, py) }
        for (py in 6 until 22) for (px in 6 until 22) dot(px, py)
        for (i in 0 until TEST_PICTURE_HEIGHT - 4) for (t in 0..1) dot(2 + i * (width - 4) / (TEST_PICTURE_HEIGHT - 4) + t, 2 + i)
        bitmap(x, y, packed, widthBytes, TEST_PICTURE_HEIGHT)
    }

    fun print(sets: Int = 1, copies: Int = 1) = cmd("PRINT $sets,$copies")

    companion object {
        const val TEST_PICTURE_BYTES = 12
        const val TEST_PICTURE_HEIGHT = 48
    }

    fun build(): ByteArray = out.toByteArray()

    private fun fmt(v: Float) = if (v == Math.floor(v.toDouble()).toFloat()) {
        v.toInt().toString()
    } else {
        v.toString()
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * A page packed for TSPL: [widthBytes] bytes a row, [height] rows, a 0 bit being
 * a dot (so 0xFF is blank).
 */
class PackedPage(val packed: ByteArray, val widthBytes: Int, val height: Int)
