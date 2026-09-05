package com.gulshan.pocketprint

import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.print.JobError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(
            R.string.job_error_asleep,
            JobError.explain("read failed, socket might closed or timeout, read ret: -1"),
        )
    }

    @Test
    fun `a refused certificate is explained as a decision, and a changed one as a warning`() {
        assertEquals(
            R.string.job_error_untrusted_certificate,
            JobError.explain("The printer's certificate is not trusted by this device (SHA-256 F1:2F)"),
        )
        // The platform's own wording, for the path that never reached the pinning manager.
        assertEquals(
            R.string.job_error_untrusted_certificate,
            JobError.explain("Trust anchor for certification path not found."),
        )
        assertEquals(
            R.string.job_error_certificate_changed,
            JobError.explain("The printer's certificate has changed since it was trusted (was A3:C3, now F1:2F)"),
        )
    }

    @Test
    fun `a stalled write is explained as the printer, not as the socket`() {
        assertEquals(
            R.string.job_error_stalled,
            JobError.explain("The printer stopped accepting data for 60 seconds"),
        )
    }

    @Test
    fun `network failures point at the thing that is actually wrong`() {
        assertEquals(R.string.job_error_refused, JobError.explain("failed to connect: ECONNREFUSED"))
        assertEquals(R.string.job_error_timeout, JobError.explain("connect timed out"))
        assertEquals(
            R.string.job_error_unreachable,
            JobError.explain("connect failed: EHOSTUNREACH"),
        )
    }

    @Test
    fun `a printer already in use says so`() {
        assertEquals(
            R.string.job_error_busy,
            JobError.explain("bind failed: Device or resource busy"),
        )
    }

    @Test
    fun `pairing failures name the PIN, because it is always 0000`() {
        assertEquals(R.string.job_error_pairing, JobError.explain("Authentication failure"))
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
