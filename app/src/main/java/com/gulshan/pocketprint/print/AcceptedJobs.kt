package com.gulshan.pocketprint.print

import java.util.concurrent.ConcurrentHashMap

/**
 * When each job was accepted, and whether it has to wait for a turn.
 *
 * Pulled out of the service because both answers are small and both were
 * regressions waiting to happen with nothing to see them: a job that finds the
 * service idle must not get a QUEUED row (its SENDING row follows at once), and
 * a job that did wait must keep the time it was accepted when it starts, or it
 * jumps to the top of the history.
 */
internal class AcceptedJobs(private val clock: () -> Long = System::currentTimeMillis) {

    private val at = ConcurrentHashMap<String, Long>()

    /**
     * Notes that [jobId] was accepted now. [queueBusy] is whether another job
     * holds the printer queue; only then does the job show as QUEUED.
     */
    fun accept(jobId: String, queueBusy: Boolean): Accepted {
        val time = clock().also { at[jobId] = it }
        return Accepted(time, showAsQueued = queueBusy)
    }

    /** The time [jobId] was accepted, or now for one that was never announced. */
    fun startedAt(jobId: String): Long = at[jobId] ?: clock()

    fun forget(jobId: String) {
        at.remove(jobId)
    }

    class Accepted(val at: Long, val showAsQueued: Boolean)
}
