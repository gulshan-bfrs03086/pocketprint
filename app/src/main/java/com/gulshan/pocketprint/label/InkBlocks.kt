package com.gulshan.pocketprint.label

/**
 * A rectangle of a packed 1-bit page: [xByte] and [widthBytes] are in bytes
 * across (8 dots each), [y] and [height] in rows down.
 */
data class InkBlock(val xByte: Int, val y: Int, val widthBytes: Int, val height: Int)

/**
 * Splits a packed page into the rectangles that actually have ink in them.
 *
 * ## Why this exists
 *
 * A label page is mostly white, and a white byte costs as much to send as a
 * black one. On a printer that takes bytes in at a few hundred a second that is
 * not a detail: measured on an RE310 over Bluetooth, the printer took in about
 * 190 bytes a second whatever was in them (7.5 KB kept it busy for 39 s, 15 KB
 * for 77 s). A 75 x 75 mm page at 203 dpi is about 45 KB of bitmap, so one label
 * took four minutes to arrive. Bluetooth swallowed it in a second, the app logged
 * "sent", the system said "completed", and the label came out minutes later -
 * behind however many others had been pressed in the meantime.
 *
 * Sending only the blocks with ink in them cut the two real labels measured
 * (a gate pass and a bin barcode) to 22 % and 11 % of the full page.
 *
 * ## How
 *
 * An XY cut, the classic page-segmentation move: take the tight box around the
 * ink, find the widest run of blank rows or blank byte-columns inside it, cut
 * there, and recurse on both halves. A text line, a barcode and an open border
 * end up as separate rectangles, with the white between them never sent.
 *
 * ## Known limit
 *
 * A cut needs a blank gap that runs the whole width or height of the box. A
 * closed frame - a rule across the top joined to one down the side - or table
 * gridlines leave no such gap, so a page with one is sent as a single block, as
 * it was before. That is not worse than before, only no better; compressing the
 * blocks is what helps there (see the printer measurements in the project
 * notes).
 *
 * Every BITMAP command has a header, so a cut is only made when the blank bytes
 * it removes outweigh the header it adds ([COMMAND_COST]). That makes the result
 * never larger than the one tight box around everything, and a full-page photo
 * - no blank gap anywhere - comes out as one block, byte for byte what was sent
 * before.
 *
 * It is lossless by construction: only rectangles that are entirely blank are
 * dropped. TsplBitmapTest checks that by redrawing what a printer would draw from
 * the commands and comparing it with the page that went in.
 */
object InkBlocks {

    /**
     * What one more BITMAP command costs in bytes: `BITMAP 1234,1234,75,599,0,`
     * and the CR LF after the data. Rounded up, so a cut has to clearly pay.
     */
    const val COMMAND_COST = 28

    /**
     * Most blocks one page may become.
     *
     * A page of fine noise would otherwise be cut into hundreds of slivers, each
     * with a header, for little saving and a long command stream a printer may
     * not like. Past this the blank-gap threshold is raised until it fits.
     */
    const val MAX_BLOCKS = 64

    /** What a blank byte looks like in TSPL's polarity, where a 0 bit is a dot. */
    private const val BLANK: Byte = 0xFF.toByte()

    /** Blank gaps tried in turn, smallest first: [rows, bytes]. */
    private val GAP_LADDER = listOf(2 to 2, 4 to 4, 8 to 8, 16 to 16)

    /**
     * @param packed the page as TSPL wants it: [widthBytes] per row, a 0 bit for
     *   ink, so a blank byte is 0xFF.
     * @return the blocks to send, top to bottom and left to right; empty for a
     *   page with no ink at all.
     */
    fun find(packed: ByteArray, widthBytes: Int, height: Int): List<InkBlock> {
        require(widthBytes > 0 && height > 0) { "empty page ${widthBytes}x$height" }
        require(packed.size == widthBytes * height) {
            "packed is ${packed.size} bytes, expected ${widthBytes * height}"
        }

        val page = Page(packed, widthBytes)
        val whole = page.tight(0, 0, widthBytes, height) ?: return emptyList()

        for ((gapRows, gapBytes) in GAP_LADDER) {
            val blocks = cut(page, whole, gapRows, gapBytes)
            if (blocks.size <= MAX_BLOCKS) return sorted(blocks)
        }
        // Still too many at the widest gap: one box around everything.
        return listOf(whole.toBlock())
    }

    /** x0/y0 inclusive, x1/y1 exclusive. */
    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
        fun toBlock() = InkBlock(x0, y0, width, height)
    }

    private class Page(val data: ByteArray, val widthBytes: Int) {
        fun inked(x: Int, y: Int) = data[y * widthBytes + x] != BLANK

        /** The smallest box around the ink inside the given region, or null if there is none. */
        fun tight(x0: Int, y0: Int, x1: Int, y1: Int): Box? {
            var top = -1
            var bottom = -1
            var left = x1
            var right = x0 - 1
            for (y in y0 until y1) {
                var first = -1
                var last = -1
                for (x in x0 until x1) {
                    if (inked(x, y)) {
                        if (first < 0) first = x
                        last = x
                    }
                }
                if (first >= 0) {
                    if (top < 0) top = y
                    bottom = y
                    if (first < left) left = first
                    if (last > right) right = last
                }
            }
            return if (top < 0) null else Box(left, top, right + 1, bottom + 1)
        }
    }

    private fun cut(page: Page, whole: Box, gapRows: Int, gapBytes: Int): List<InkBlock> {
        val blocks = mutableListOf<InkBlock>()
        // An explicit stack: depth is bounded by the number of cuts, but the
        // page is the caller's and this runs on a phone.
        val pending = ArrayDeque<Box>()
        pending.add(whole)

        while (pending.isNotEmpty()) {
            val box = pending.removeLast()

            val rowInk = BooleanArray(box.height) { dy ->
                (box.x0 until box.x1).any { x -> page.inked(x, box.y0 + dy) }
            }
            val colInk = BooleanArray(box.width) { dx ->
                (box.y0 until box.y1).any { y -> page.inked(box.x0 + dx, y) }
            }
            val rowGap = widestInteriorGap(rowInk, gapRows)
            val colGap = widestInteriorGap(colInk, gapBytes)

            // Bytes a cut would stop sending: the gap, across the box.
            val rowSaving = rowGap?.let { (it.last - it.first + 1) * box.width } ?: 0
            val colSaving = colGap?.let { (it.last - it.first + 1) * box.height } ?: 0
            val best = maxOf(rowSaving, colSaving)

            if (best <= COMMAND_COST) {
                blocks += box.toBlock()
                continue
            }

            val halves = if (rowSaving >= colSaving) {
                val gap = rowGap!!
                listOf(
                    Box(box.x0, box.y0, box.x1, box.y0 + gap.first),
                    Box(box.x0, box.y0 + gap.last + 1, box.x1, box.y1),
                )
            } else {
                val gap = colGap!!
                listOf(
                    Box(box.x0, box.y0, box.x0 + gap.first, box.y1),
                    Box(box.x0 + gap.last + 1, box.y0, box.x1, box.y1),
                )
            }
            // Each half is re-tightened, so the white at its outer edge is
            // dropped too; a half that is all white (cannot happen for an
            // interior gap, but costs nothing to be sure of) vanishes.
            for (half in halves) {
                page.tight(half.x0, half.y0, half.x1, half.y1)?.let { pending.add(it) }
            }
        }
        return blocks
    }

    /**
     * The longest run of blank entries that has ink on both sides and is at
     * least [minRun] long, as an index range - or null if there is none.
     */
    private fun widestInteriorGap(ink: BooleanArray, minRun: Int): IntRange? {
        var best: IntRange? = null
        var i = 0
        while (i < ink.size) {
            if (ink[i]) {
                i++
                continue
            }
            var j = i
            while (j < ink.size && !ink[j]) j++
            val interior = i > 0 && j < ink.size
            if (interior && j - i >= minRun && (best == null || j - i > best.last - best.first + 1)) {
                best = i until j
            }
            i = j
        }
        return best
    }

    private fun sorted(blocks: List<InkBlock>) =
        blocks.sortedWith(compareBy({ it.y }, { it.xByte }))
}
