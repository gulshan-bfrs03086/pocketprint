package com.gulshan.pocketprint.ui

/**
 * How much a job actually sent, in a unit that does not round it away.
 *
 * The history row used to divide by 1024 and print the result as KB. A label
 * is a few hundred bytes of TSPL or ZPL, so the app's own commonest job -
 * printing a label from the Labels screen - reported "Sent (0 KB)". True, and
 * it reads as nothing was sent.
 *
 * That matters more here than the arithmetic suggests. This row is the one
 * screen whose whole purpose is not overstating what happened: SENT and
 * COMPLETED are separate states because the app once claimed six jobs had
 * completed while nothing came out of the printer. A byte count of zero beside
 * a job that worked is the same error pointing the other way, and it sends
 * somebody into the transport looking for a fault that is not there.
 *
 * The unit is chosen by threshold and the value is truncated within it, so
 * nothing ever displays as "1024 KB" on its way to becoming a megabyte.
 */
data class ByteSize(val value: Int, val unit: Unit) {

    enum class Unit { BYTES, KILOBYTES, MEGABYTES }

    companion object {
        private const val KB = 1024L
        private const val MB = KB * 1024

        fun of(bytes: Long): ByteSize {
            // A negative count is not a smaller number, it is a bug upstream.
            // Showing 0 B is honest about the one thing that is known.
            val safe = bytes.coerceAtLeast(0L)
            return when {
                safe < KB -> ByteSize(safe.toInt(), Unit.BYTES)
                safe < MB -> ByteSize((safe / KB).toInt(), Unit.KILOBYTES)
                else -> ByteSize((safe / MB).toInt(), Unit.MEGABYTES)
            }
        }
    }
}
