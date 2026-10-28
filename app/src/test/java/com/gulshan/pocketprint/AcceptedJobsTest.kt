package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.AcceptedJobs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A document sent while another is printing is shown as QUEUED, and keeps the
 * time it was accepted when its turn comes. One sent to an idle service is not
 * announced at all: it would flash a QUEUED row before its SENDING one.
 */
class AcceptedJobsTest {

    private var now = 1_000L
    private val jobs = AcceptedJobs { now }

    @Test
    fun `a job that has to wait is shown as queued`() {
        assertTrue(jobs.accept("a", queueBusy = true).showAsQueued)
    }

    @Test
    fun `a job that finds the service idle is not`() {
        assertFalse(jobs.accept("a", queueBusy = false).showAsQueued)
    }

    @Test
    fun `a job keeps the time it was accepted when its turn comes`() {
        val accepted = jobs.accept("a", queueBusy = true)
        now = 90_000L

        assertEquals(1_000L, accepted.at)
        assertEquals("it took the time it started, and jumped the list", 1_000L, jobs.startedAt("a"))
    }

    @Test
    fun `a job nobody announced starts now, and a forgotten one does too`() {
        now = 5_000L
        assertEquals(5_000L, jobs.startedAt("never-seen"))

        jobs.accept("a", queueBusy = false)
        jobs.forget("a")
        now = 7_000L
        assertEquals(7_000L, jobs.startedAt("a"))
    }

    @Test
    fun `an idle job's acceptance time is still kept for its own rows`() {
        jobs.accept("a", queueBusy = false)
        now = 2_000L

        assertEquals(1_000L, jobs.startedAt("a"))
    }
}
