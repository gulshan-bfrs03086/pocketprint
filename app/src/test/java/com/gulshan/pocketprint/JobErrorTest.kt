package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.JobError
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Job history showed raw exception text. "read failed, socket might closed or
 * timeout, read ret: -1" is an accurate description of a file descriptor and
 * tells the person holding the printer nothing — least of all that it has
 * probably gone to sleep and the fix is its power button.
 */
class JobErrorTest {

    @Test
    fun `the message a sleeping bluetooth printer actually produces`() {
        // The literal string the Android Bluetooth stack returns, which is what
        // this app has been showing people.
        val advice = JobError.explain(
            "read failed, socket might closed or timeout, read ret: -1",
        )
        assertNotNull(advice)
        assertTrue(advice!!.contains("sleep"))
    }

    @Test
    fun `a stalled write is explained as the printer, not as the socket`() {
        val advice = JobError.explain("The printer stopped accepting data for 60 seconds")
        assertNotNull(advice)
        assertTrue(advice!!.contains("out of paper"))
    }

    @Test
    fun `network failures point at the thing that is actually wrong`() {
        assertTrue(JobError.explain("failed to connect: ECONNREFUSED")!!.contains("9100"))
        assertTrue(JobError.explain("connect timed out")!!.contains("switched on"))
        assertTrue(JobError.explain("connect failed: EHOSTUNREACH")!!.contains("subnet"))
    }

    @Test
    fun `a printer already in use says so`() {
        assertTrue(
            JobError.explain("bind failed: Device or resource busy")!!
                .contains("one connection at a time"),
        )
    }

    @Test
    fun `pairing failures name the PIN, because it is always 0000`() {
        assertTrue(JobError.explain("Authentication failure")!!.contains("0000"))
    }

    @Test
    fun `an unrecognised failure gets no explanation rather than a guess`() {
        // A confident wrong diagnosis is worse than the raw text, which at
        // least a search engine can do something with.
        assertNull(JobError.explain("java.lang.IllegalStateException: unexpected tag 0x7f"))
        assertNull(JobError.explain(""))
        assertNull(JobError.explain(null))
    }
}
