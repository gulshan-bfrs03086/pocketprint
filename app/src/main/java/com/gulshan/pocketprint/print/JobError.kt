package com.gulshan.pocketprint.print

import androidx.annotation.StringRes
import com.gulshan.pocketprint.R

/**
 * Turns what the transport said into something the user can do something about.
 *
 * Job history showed raw exception text. "read failed, socket might closed or
 * timeout, read ret: -1" is an accurate description of a file descriptor and
 * tells the person holding the printer nothing at all — least of all that the
 * printer has probably gone to sleep and the fix is to press its power button.
 *
 * The original message is never replaced, only explained: it is what makes a
 * printer report useful, and a guess dressed up as a diagnosis would be worse
 * than the raw text. Anything not recognised gets no explanation rather than a
 * generic one.
 */
object JobError {

    /**
     * The resource id of an explanation, or null if there is nothing honest to
     * say. Returning an id rather than a string keeps this callable from a
     * service and from a composable alike, and makes the mapping - which is the
     * part worth being sure about - testable without a Context.
     */
    @StringRes
    fun explain(raw: String?): Int? {
        val message = raw?.lowercase() ?: return null
        return when {
            // Before the network cases: a refused certificate also mentions a
            // handshake, and the handshake is not the thing that is wrong.
            "certificate has changed" in message -> R.string.job_error_certificate_changed

            "certificate is not trusted" in message ||
                "trust anchor" in message -> R.string.job_error_untrusted_certificate

            "socket might closed" in message ||
                "broken pipe" in message ||
                "connection reset" in message ||
                "closed the connection" in message -> R.string.job_error_asleep

            "stopped accepting data" in message -> R.string.job_error_stalled

            "bluetooth is turned off" in message -> R.string.job_error_bluetooth_off

            "permission" in message && "bluetooth" in message ->
                R.string.job_error_bluetooth_permission

            "econnrefused" in message || "connection refused" in message ->
                R.string.job_error_refused

            "etimedout" in message || "timed out" in message || "timeout" in message ->
                R.string.job_error_timeout

            "ehostunreach" in message || "enetunreach" in message ->
                R.string.job_error_unreachable

            "device or resource busy" in message || "resource busy" in message ->
                R.string.job_error_busy

            "not bonded" in message || "authentication" in message ->
                R.string.job_error_pairing

            "no printer" in message || "printer not found" in message ->
                R.string.job_error_no_printer

            "out of paper" in message || "media" in message && "empty" in message ->
                R.string.job_error_out_of_media

            else -> null
        }
    }
}
