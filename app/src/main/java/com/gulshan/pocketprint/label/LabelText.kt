package com.gulshan.pocketprint.label

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Lays label text out with Android's text engine instead of the printer's own
 * fonts.
 *
 * A TSPL or ZPL TEXT command carries bytes, and this app encoded them
 * ISO-8859-1 — so anything outside U+0000..U+00FF became a question mark on the
 * label. Hindi, Arabic, Thai and Chinese all printed as rows of "?", in exactly
 * the markets that buy these printers. No amount of picking a different
 * resident font fixes that: the printer does not have the glyphs, and on a
 * cheap thermal unit it never will.
 *
 * The phone does. Android carries font fallback for every script it supports,
 * and StaticLayout does the shaping, bidirectional reordering and line breaking
 * that Devanagari and Arabic need and that a printer's TEXT command has never
 * heard of. So the text is drawn here, at printer resolution, and sent as a
 * bitmap.
 *
 * It is not free — a rendered text block is tens of kilobytes where a TEXT
 * command is tens of bytes, which over Bluetooth is seconds rather than
 * milliseconds. So [printerFontsCanCarry] decides, and Latin text keeps the
 * fast path it always had.
 */
object LabelText {

    /** One run of text at one size. Sizes are in printer dots. */
    data class Line(
        val text: String,
        val sizeDots: Float,
        val bold: Boolean = false,
    )

    /** Blank rows left between lines, in dots. */
    private const val LINE_GAP_DOTS = 8

    /**
     * Whether the printer's resident fonts can carry this text at all.
     *
     * U+00FF is the honest ceiling: above it the ISO-8859-1 encoding these
     * commands use cannot represent the character, full stop. Below it the
     * answer depends on the printer's configured code page, so a printer may
     * still substitute an accented character — this says what is definitely
     * impossible, not what is guaranteed to work.
     */
    fun printerFontsCanCarry(lines: List<String>): Boolean =
        lines.all { line -> line.all { it.code <= 0xFF } }

    /**
     * Draws the lines into a bitmap exactly [widthDots] wide, as tall as the
     * text needs, black on white. Returns null when there is nothing to draw.
     */
    fun render(lines: List<Line>, widthDots: Int): Bitmap? {
        val drawable = lines.filter { it.text.isNotBlank() }
        if (drawable.isEmpty() || widthDots <= 0) return null

        val layouts = drawable.map { line -> layoutFor(line, widthDots) }
        val height = layouts.sumOf { it.height } + LINE_GAP_DOTS * (layouts.size - 1)
        if (height <= 0) return null

        val bitmap = Bitmap.createBitmap(widthDots, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // White ground, because the rasteriser downstream reads luminance and
        // an unfilled bitmap is transparent black - which would come out as a
        // solid block of ink.
        canvas.drawColor(Color.WHITE)

        var y = 0
        layouts.forEach { layout ->
            canvas.save()
            canvas.translate(0f, y.toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + LINE_GAP_DOTS
        }
        return bitmap
    }

    private fun layoutFor(line: Line, widthDots: Int): StaticLayout {
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = line.sizeDots
            typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return StaticLayout.Builder
            .obtain(line.text, 0, line.text.length, paint, widthDots)
            // NORMAL rather than LEFT: it follows the paragraph direction, so
            // Arabic and Hebrew sit against the right edge where they belong.
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }
}
