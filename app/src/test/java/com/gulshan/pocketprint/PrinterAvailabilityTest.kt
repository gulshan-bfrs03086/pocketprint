package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.PrinterAvailability
import com.gulshan.pocketprint.print.PrinterStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the print dialog is told about a network printer. The rules matter
 * because two of them are counter-intuitive: a stopped printer and one that has
 * stopped accepting jobs both take a job over IPP and hold it, which looks like
 * success to the sender and prints nothing until somebody walks over to it.
 */
class PrinterAvailabilityTest {

    private fun status(
        reachable: Boolean = true,
        accepting: Boolean = true,
        state: Int? = 3,
    ) = PrinterStatus.fromIpp(reachable, accepting, state)

    @Test
    fun `an idle printer that answers and accepts work is idle`() {
        assertEquals(PrinterAvailability.IDLE, status(state = 3))
    }

    @Test
    fun `processing is busy, not unavailable`() {
        assertEquals(PrinterAvailability.BUSY, status(state = 4))
    }

    @Test
    fun `stopped is unavailable even though the printer is answering`() {
        assertEquals(PrinterAvailability.UNAVAILABLE, status(state = 5))
    }

    @Test
    fun `a printer that has stopped accepting jobs is unavailable whatever its state`() {
        assertEquals(PrinterAvailability.UNAVAILABLE, status(accepting = false, state = 3))
        assertEquals(PrinterAvailability.UNAVAILABLE, status(accepting = false, state = 4))
    }

    @Test
    fun `unreachable wins over everything else it might have claimed`() {
        assertEquals(PrinterAvailability.UNAVAILABLE, status(reachable = false, state = 3))
    }

    @Test
    fun `an unknown printer-state is idle, not unavailable`() {
        // A printer inventing a state should not be greyed out in the dialog:
        // a printer that cannot be selected cannot explain itself either.
        assertEquals(PrinterAvailability.IDLE, status(state = null))
        assertEquals(PrinterAvailability.IDLE, status(state = 99))
    }
}
