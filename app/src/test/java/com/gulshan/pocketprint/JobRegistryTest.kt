package com.gulshan.pocketprint

import com.gulshan.pocketprint.print.JobRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is what makes a Cancel button on a history row mean anything.
 *
 * The row is storage; the coroutine is somewhere else entirely. Everything
 * below is about the seam between the two: that a job can be found while it
 * runs, that it cannot be found once it does not, and that an id nobody here
 * knows gets a clear "not mine" rather than a silent no-op - because the
 * caller's next move is to forward the request to the print service.
 */
class JobRegistryTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a running job can be cancelled by id`() = runBlocking {
        val scope = scope()
        val registry = JobRegistry()
        val started = CompletableDeferred<Unit>()

        val job = scope.launch {
            started.complete(Unit)
            awaitCancellation()
        }
        registry.register("job-1", job)
        started.await()

        assertTrue("the registry should own this id", registry.cancel("job-1"))
        job.join()
        assertTrue("the job should be cancelled", job.isCancelled)
        scope.cancel()
    }

    @Test
    fun `an unknown id reports that it was not found`() {
        val registry = JobRegistry()
        // The caller forwards to the print service on false. Returning true
        // here would drop the request on the floor and leave the job running
        // with a button that appears to have worked.
        assertFalse(registry.cancel("never-registered"))
    }

    @Test
    fun `a job that finishes normally leaves the registry`() = runBlocking {
        val scope = scope()
        val registry = JobRegistry()

        val job = scope.launch { }
        registry.register("job-1", job)
        job.join()

        assertEquals(emptySet<String>(), registry.ids)
        assertFalse("a finished job is not cancellable", registry.cancel("job-1"))
        scope.cancel()
    }

    @Test
    fun `a cancelled job leaves the registry too`() = runBlocking {
        val scope = scope()
        val registry = JobRegistry()
        val started = CompletableDeferred<Unit>()

        val job = scope.launch {
            started.complete(Unit)
            awaitCancellation()
        }
        registry.register("job-1", job)
        started.await()
        registry.cancel("job-1")
        job.join()

        // Left behind, this entry would offer to cancel an already-dead job.
        assertEquals(emptySet<String>(), registry.ids)
        scope.cancel()
    }

    @Test
    fun `registering a job that has already finished leaves nothing behind`() = runBlocking {
        val scope = scope()
        val registry = JobRegistry()

        val job = scope.launch { }
        job.join()
        registry.register("job-1", job)

        // invokeOnCompletion fires immediately on a completed job, so the
        // entry goes in and comes straight back out.
        assertEquals(emptySet<String>(), registry.ids)
        scope.cancel()
    }

    @Test
    fun `an older job completing does not evict a newer one holding the same id`() = runBlocking {
        val scope = scope()
        val registry = JobRegistry()
        val secondStarted = CompletableDeferred<Unit>()

        // Held back so its completion handler runs after the second job has
        // taken the id. An unconditional remove(id) would evict the live job
        // and the Cancel button would go quiet.
        val first = scope.launch(start = CoroutineStart.LAZY) { }
        registry.register("job-1", first)

        val second = scope.launch {
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        registry.register("job-1", second)
        secondStarted.await()

        first.start()
        first.join()

        assertEquals(setOf("job-1"), registry.ids)
        assertTrue("the live job should still be cancellable", registry.cancel("job-1"))
        second.join()
        assertTrue(second.isCancelled)
        scope.cancel()
    }
}
