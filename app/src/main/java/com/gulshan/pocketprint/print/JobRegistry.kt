package com.gulshan.pocketprint.print

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The jobs a component currently has running, by id, so that a cancel request
 * arriving from somewhere else can find the right one.
 *
 * A history row is not a handle. The Jobs screen holds records read back from
 * storage, and pressing Cancel on one only says which id the user meant - the
 * coroutine actually doing the work lives wherever it was started, and nothing
 * in the record points at it. This is the lookup that closes that gap.
 *
 * Cancelling the coroutine is only half of a cancellation, and the smaller
 * half: a write blocked in a syscall ignores it entirely. What finishes the
 * job off is the stall guard around the transport, which closes the socket
 * from another thread precisely because this cancellation reached it.
 */
class JobRegistry {

    private val running = ConcurrentHashMap<String, Job>()

    /**
     * Remembers [job] under [id] until it ends, however it ends - cancelled
     * counts, which is the case that would otherwise leave a dead entry
     * behind and offer to cancel it a second time.
     *
     * Safe on a job that has already finished: the completion handler fires
     * straight away and takes the entry out again.
     */
    fun register(id: String, job: Job) {
        running[id] = job
        // Value-conditional, so a handler firing late cannot evict a newer job
        // that has since taken the same id.
        job.invokeOnCompletion { running.remove(id, job) }
    }

    /**
     * Cancels the job registered under [id].
     *
     * Returns whether there was one, which is the answer a caller needs: a job
     * this registry has never heard of is running somewhere else, and the
     * request has to be forwarded rather than dropped.
     */
    fun cancel(id: String): Boolean {
        val job = running[id] ?: return false
        job.cancel()
        return true
    }

    /** Ids currently registered. For assertions and diagnostics. */
    val ids: Set<String> get() = running.keys.toSet()
}
