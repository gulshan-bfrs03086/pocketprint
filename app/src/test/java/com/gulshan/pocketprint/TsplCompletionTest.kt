package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.Ending
import com.gulshan.pocketprint.print.TsplStatus
import com.gulshan.pocketprint.print.TsplStatusMemory
import com.gulshan.pocketprint.print.awaitTsplEnd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what is read from a printer's status answers, using sequences taken from
 * a real RE310 - not what a status protocol ought to look like. The measured
 * shape around a job is: one early "ready", then silence while it takes the job
 * in, then "ready" again.
 */
class TsplCompletionTest {

    /** A clock that only moves when the code under test waits or asks. */
    private class Scenario(private val replies: List<Int?>) {
        var clock = 0L
        var asked = 0
        val pauses = mutableListOf<Long>()
        val busyReports = mutableListOf<Long>()

        suspend fun ask(): Int? {
            val reply = replies.getOrNull(asked)
            asked++
            // An answer is quick; waiting for one that never comes costs the whole window.
            clock += if (reply == null) 700 else 150
            return reply
        }

        suspend fun run(maxWaitMs: Long = 600_000): Ending = awaitTsplEnd(
            ask = ::ask,
            maxWaitMs = maxWaitMs,
            now = { clock },
            pause = { clock += it; pauses += it },
            onBusy = { busyReports += it },
        )
    }

    private fun end(vararg replies: Int?, max: Long = 600_000) = runBlocking { Scenario(replies.toList()).run(max) }

    @Test
    fun `an early ready, then silence, then ready, is a finished job`() {
        // The shape measured on the printer for every job, at every size.
        val result = end(0x00, null, null, null, 0x00)

        assertTrue("$result", result is Ending.Finished)
    }

    @Test
    fun `ready after the printer reported printing is a finished job`() {
        val result = end(0x00, null, 0x20, 0x20, 0x00)

        assertTrue("$result", result is Ending.Finished)
    }

    @Test
    fun `ready every time and never busy is not taken for a finished job`() {
        // Idle says nothing about whether it took the job: it may not have
        // started yet.
        val result = end(0x00, 0x00, 0x00)

        assertTrue("$result", result is Ending.ReadyButNeverSeenBusy)
    }

    @Test
    fun `a printer that never answers is given up on after a few tries, not waited for`() {
        val scenario = Scenario(listOf(null, null, null, null, null))

        val result = runBlocking { scenario.run() }

        assertEquals(Ending.NoAnswer, result)
        assertEquals("it kept asking a printer that cannot answer", 3, scenario.asked)
    }

    @Test
    fun `a printer that answered and then went silent is working, however long that takes`() {
        // The case that must not be mistaken for the one above: one early answer
        // and then silence is a busy printer, and giving up on it would put the
        // old "sent" back.
        val scenario = Scenario(listOf(0x00) + List(40) { null } + listOf(0x00))

        val result = runBlocking { scenario.run() }

        assertTrue("$result", result is Ending.Finished)
        assertEquals(42, scenario.asked)
    }

    @Test
    fun `out of paper is reported at once, whatever else is going on`() {
        assertEquals(Ending.Fault(TsplStatus.Fault.PAPER_EMPTY), end(0x04))
        assertEquals(Ending.Fault(TsplStatus.Fault.PAPER_JAM), end(0x00, null, 0x02))
        assertEquals(Ending.Fault(TsplStatus.Fault.HEAD_OPEN), end(0x01))
        // Several at once: the one that matters most to a person standing there.
        assertEquals(Ending.Fault(TsplStatus.Fault.PAPER_EMPTY), end(0x05))
    }

    @Test
    fun `printing is not a fault, and neither are the bits that are not faults`() {
        assertNull(TsplStatus.fault(0x20))
        assertNull(TsplStatus.fault(0x00))
        // Ribbon, pause and "other": not reported, because a direct thermal
        // printer may set one with nothing wrong and a false failure costs a reprint.
        assertNull(TsplStatus.fault(0x08))
        assertNull(TsplStatus.fault(0x10))
        assertNull(TsplStatus.fault(0x80))
    }

    @Test
    fun `a printer that is paused or in an error state is not reported as having finished`() {
        // Neither ready nor printing nor one of the faults we name, so it was
        // being counted as ready - and a printer that is holding the label was
        // reported Completed. The same byte three times is a verdict.
        assertEquals(Ending.NeedsAttention(0x10), end(0x00, null, 0x10, 0x10, 0x10))
        assertEquals(Ending.NeedsAttention(0x80), end(0x00, null, 0x80, 0x80, 0x80))
        assertEquals(Ending.NeedsAttention(0x40), end(0x00, null, 0x40, 0x40, 0x40))
    }

    @Test
    fun `one stray byte is not a verdict`() {
        // A single odd answer between ready ones must not end the wait either way.
        val result = end(0x00, null, 0x10, null, 0x00)

        assertTrue("$result", result is Ending.Finished)
    }

    @Test
    fun `the ribbon bit alone is not a reason to doubt a ready printer`() {
        // A direct-thermal printer may set it with nothing wrong.
        assertTrue(end(0x00, null, 0x08) is Ending.Finished)
        // And printing with it set is still printing.
        assertTrue(end(0x00, null, 0x28, 0x28, 0x08) is Ending.Finished)
    }

    @Test
    fun `a job that is still busy when the time allowed runs out is timed out, not failed`() {
        val result = end(0x00, null, null, null, null, null, null, max = 10_000)

        assertTrue("$result", result is Ending.TimedOut)
    }

    @Test
    fun `it asks less often the longer it waits`() {
        // Each query waits in the printer's input behind the job, and a slow
        // printer reads that at a couple of hundred bytes a second.
        val scenario = Scenario(listOf(0x00) + List(30) { null } + listOf(0x00))

        runBlocking { scenario.run() }

        assertTrue("pauses should not shrink: ${scenario.pauses}", scenario.pauses.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("the gap should reach several seconds: ${scenario.pauses.max()}", scenario.pauses.max() >= 5_000)
        assertTrue("and stop growing there", scenario.pauses.max() <= 5_000)
    }

    @Test
    fun `it says how long it has been waiting each time the printer is silent`() {
        val scenario = Scenario(listOf(0x00, null, null, 0x00))

        runBlocking { scenario.run() }

        assertEquals(2, scenario.busyReports.size)
        assertTrue(scenario.busyReports.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `a printer found not to answer is remembered, and one that does is not`() {
        val silent = "bt:aa:bb:cc:dd:ee:01"
        val talking = "bt:aa:bb:cc:dd:ee:02"
        TsplStatusMemory.markSilent(silent)

        assertFalse(TsplStatusMemory.answers(silent))
        assertTrue(TsplStatusMemory.answers(talking))
    }
}
