package com.gulshan.pocketprint.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A write that stopped moving bytes and had to be pulled out from under. */
class WriteStalled(val stallMs: Long, cause: Throwable? = null) : Exception(
    "The printer stopped accepting data for ${stallMs / 1000} seconds",
    cause,
)

/**
 * Runs a write with a deadline on *progress*, not on total time.
 *
 * A printer that stops draining its buffer blocks the write forever. RFCOMM and
 * a raw 9100 socket have no timeout of their own — the write sits in a syscall
 * until the printer is fixed or the process dies — and every job behind it in
 * the queue waits with it. The only recovery was force-stopping the app.
 *
 * Two things are true at once here and both matter. Cancelling a coroutine does
 * not interrupt a thread blocked in write(2); and closing the underlying socket
 * from another thread does. So [unblock] — closing the transport — is the
 * mechanism for both the stall deadline and for an ordinary user cancellation,
 * and the watcher coroutine is what has a thread free to call it.
 *
 * The deadline is on the gap between chunks rather than on the job, because
 * jobs vary from a few hundred bytes to a rasterised page that legitimately
 * takes minutes over Bluetooth. Sixty seconds without a single byte moving is
 * not a slow printer; it is a stuck one.
 */
suspend fun <T> guardAgainstStall(
    stallMs: Long,
    unblock: () -> Unit,
    body: suspend (heartbeat: () -> Unit) -> T,
): T = coroutineScope {
    val lastBeat = AtomicLong(System.currentTimeMillis())
    val stalled = AtomicBoolean(false)
    val finished = AtomicBoolean(false)

    // Default, not IO: this must have a thread of its own while the write sits
    // in a syscall on one of IO's.
    val watcher = launch(Dispatchers.Default) {
        val tick = (stallMs / 4).coerceIn(100L, 5_000L)
        try {
            while (isActive) {
                delay(tick)
                if (System.currentTimeMillis() - lastBeat.get() >= stallMs) {
                    stalled.set(true)
                    runCatching { unblock() }
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            // Distinguishing the two cancellations matters: the tidy-up below
            // cancels this watcher on the way out, and closing the transport
            // then would be closing something already finished with. Only a
            // cancellation that arrives while the write is still running means
            // somebody asked for the job to stop.
            if (!finished.get()) runCatching { unblock() }
            throw cancelled
        }
    }

    try {
        body { lastBeat.set(System.currentTimeMillis()) }
    } catch (failure: Throwable) {
        // The transport reports a closed socket, which is true but useless.
        // What happened is that the printer went quiet.
        if (stalled.get() && failure !is CancellationException) {
            throw WriteStalled(stallMs, failure)
        }
        throw failure
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
