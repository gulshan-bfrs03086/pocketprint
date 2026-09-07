package com.gulshan.pocketprint

import com.gulshan.pocketprint.ui.ByteSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug this replaces was found on hardware: a real ZPL 4 x 6 label printed
 * correctly and its history row read "Sent (0 KB)". Dividing by 1024 and
 * calling the result KB truncates every label job to nothing, and a label is
 * the app's own commonest job.
 *
 * Everything here is a boundary, because the fault was exactly a boundary.
 */
class ByteSizeTest {

    private fun of(bytes: Long) = ByteSize.of(bytes)

    @Test
    fun `a label sized job is reported in bytes rather than rounded away`() {
        // The case from the device: a few hundred bytes of ZPL.
        assertEquals(ByteSize(412, ByteSize.Unit.BYTES), of(412))
        assertEquals(ByteSize(1, ByteSize.Unit.BYTES), of(1))
    }

    @Test
    fun `zero bytes stays zero, and says bytes`() {
        // Genuinely sending nothing is a real outcome and must still read as a
        // count, not as an empty unit.
        assertEquals(ByteSize(0, ByteSize.Unit.BYTES), of(0))
    }

    @Test
    fun `the last byte below a kilobyte is still bytes`() {
        assertEquals(ByteSize(1023, ByteSize.Unit.BYTES), of(1023))
    }

    @Test
    fun `exactly one kilobyte becomes kilobytes`() {
        assertEquals(ByteSize(1, ByteSize.Unit.KILOBYTES), of(1024))
        assertEquals(ByteSize(1, ByteSize.Unit.KILOBYTES), of(1025))
    }

    @Test
    fun `the last byte below a megabyte is still kilobytes and never says 1024`() {
        // Rounding rather than truncating within the unit would print
        // "1024 KB" here, which is a megabyte wearing the wrong hat.
        assertEquals(ByteSize(1023, ByteSize.Unit.KILOBYTES), of(1024L * 1024 - 1))
    }

    @Test
    fun `exactly one megabyte becomes megabytes`() {
        assertEquals(ByteSize(1, ByteSize.Unit.MEGABYTES), of(1024L * 1024))
    }

    @Test
    fun `a rasterised page lands in megabytes`() {
        // A 600 dpi A4 page packed to one bit per dot is a few megabytes, which
        // is the other end of the range this has to cover.
        assertEquals(ByteSize(4, ByteSize.Unit.MEGABYTES), of(4L * 1024 * 1024 + 900))
    }

    @Test
    fun `a negative count is not shown as a smaller number`() {
        // bytesSent should never be negative; if it is, that is a bug upstream
        // and the row should say the one thing that is known rather than
        // rendering nonsense.
        assertEquals(ByteSize(0, ByteSize.Unit.BYTES), of(-1))
        assertEquals(ByteSize(0, ByteSize.Unit.BYTES), of(Long.MIN_VALUE))
    }

    @Test
    fun `no size in any unit is ever reported as zero unless it is zero`() {
        // The property behind the bug: every non-zero byte count must produce a
        // non-zero number in whatever unit it chooses.
        val counts = listOf(
            1L, 2L, 100L, 411L, 1023L, 1024L, 1025L, 5_000L,
            1024L * 1024 - 1, 1024L * 1024, 3_500_000L, 50_000_000L,
        )

        counts.forEach { bytes ->
            val size = of(bytes)
            assertEquals(
                "$bytes displayed as ${size.value} ${size.unit}",
                true,
                size.value > 0,
            )
        }
    }
}
