package com.gulshan.pocketprint.print

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs [body] when [lock] is free, and makes the wait for it visible and
 * cancellable.
 *
 * The service holds its lock for a whole job, including a TSPL printer's wait
 * for the label to come out - up to ten minutes. A second document sent in that
 * time used to sit on the lock with no history row at all: nothing in the Jobs
 * list, and so no Cancel button, until the first job was over.
 *
 * [announce] writes the waiting row before the lock is asked for. If the job is
 * cancelled while it is still waiting - or while [announce] is running - there
 * is no job body to settle that row, so [settleIfCancelledEarly] does, outside
 * the cancelled scope or it would itself be cancelled at its first suspension
 * point. [body] calls the function it is given once its own row has taken over;
 * from then on it owns its cancellation, as a running job always has.
 */
internal suspend fun <T> inTurn(
    lock: Mutex,
    announce: suspend () -> Unit,
    settleIfCancelledEarly: suspend () -> Unit,
    body: suspend (taken: () -> Unit) -> T,
): T {
    var taken = false
    try {
        announce()
        return lock.withLock { body { taken = true } }
    } catch (cancel: CancellationException) {
        if (!taken) withContext(NonCancellable) { settleIfCancelledEarly() }
        throw cancel
    }
}
