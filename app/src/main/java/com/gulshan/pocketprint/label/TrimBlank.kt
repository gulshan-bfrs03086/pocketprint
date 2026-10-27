package com.gulshan.pocketprint.label

import com.gulshan.pocketprint.model.LabelStock
import com.gulshan.pocketprint.model.MediaSensing
import java.math.BigDecimal
import kotlin.math.ceil

/**
 * How long a page on a continuous roll needs to be: as far down as it has ink,
 * and a margin past that.
 *
 * Android hands a printer pages at the size that was picked, so a gate pass or a
 * receipt that fills 45 mm of a 75 mm page feeds - and wastes - the other 30.
 * That is invisible on die-cut labels, where the label *is* 75 mm, and on a
 * continuous roll it is paper thrown away on every print.
 *
 * Only continuous stock is trimmed. A gap or black-mark printer finds the end of
 * each label by sensing, and a label shorter than the one on the roll would
 * stop it in the middle of the next.
 */
internal object TrimBlank {

    /** Paper left below the last ink, so the cut does not touch it. */
    const val MARGIN_MM = 4f

    /**
     * The shortest page that is sent, so a page with nothing on it feeds a
     * stub rather than nothing: zero length is not a label.
     *
     * A chosen floor, not a known printer minimum. An RE310 accepted labels of
     * 10.1 mm and 16.1 mm and reported the job finished; the length it actually
     * fed has to be measured on the paper, and a printer with a longer minimum
     * would need this raised.
     */
    const val MIN_LENGTH_MM = 10f

    /** Whether pages for this stock are trimmed at all. */
    fun applies(stock: LabelStock): Boolean =
        stock.sensing == MediaSensing.CONTINUOUS && stock.trimBlank

    /**
     * How many rows of the page to keep. Never more than [height], never fewer
     * than the minimum length (or [height], if that is shorter).
     *
     * [packed] is in TSPL's polarity, a 0 bit being a dot, so a row with ink is
     * one with any byte other than 0xFF. Padding bits at the end of a row are 1
     * and cannot look like ink.
     */
    fun rowsToKeep(packed: ByteArray, widthBytes: Int, height: Int, dpi: Int): Int {
        val minRows = dots(MIN_LENGTH_MM, dpi)
        val last = lastInkRow(packed, widthBytes, height)
        val wanted = if (last < 0) minRows else last + 1 + dots(MARGIN_MM, dpi)
        return wanted.coerceAtLeast(minRows).coerceAtMost(height)
    }

    /** The last row with any ink in it, or -1 for a page with none. */
    fun lastInkRow(packed: ByteArray, widthBytes: Int, height: Int): Int {
        for (row in height - 1 downTo 0) {
            val from = row * widthBytes
            for (i in from until from + widthBytes) {
                if (packed[i] != BLANK) return row
            }
        }
        return -1
    }

    /** Whole dots for a length, rounded up so a margin is never a dot short. */
    fun dots(mm: Float, dpi: Int): Int = ceil(mm.toDouble() * dpi / MM_PER_INCH).toInt()

    /**
     * A length in dots as the millimetres SIZE takes, rounded UP to a tenth.
     *
     * Rounded up because how a printer turns millimetres back into dots is its
     * own business - 8 a millimetre at "203" dpi, or 203 / 25.4, which is a
     * little under - and a label a dot short would cut the last row of the
     * content off. A tenth of a millimetre is under one dot of blank paper.
     * Decimal millimetres were accepted by an RE310 (24.1, 59, 60.6 mm, ...).
     */
    fun mmText(dots: Int, dpi: Int): String {
        val tenths = ceil(dots.toDouble() * MM_PER_INCH / dpi * 10.0 - EPSILON).toLong()
        return BigDecimal.valueOf(tenths).movePointLeft(1).stripTrailingZeros().toPlainString()
    }

    private const val BLANK: Byte = -1 // 0xFF
    private const val MM_PER_INCH = 25.4

    /** So a length that is a whole tenth is not pushed up by floating-point noise. */
    private const val EPSILON = 1e-9
}
