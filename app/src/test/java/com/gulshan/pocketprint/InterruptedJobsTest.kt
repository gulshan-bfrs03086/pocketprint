package com.gulshan.pocketprint

import com.gulshan.pocketprint.data.VersionedCodec
import com.gulshan.pocketprint.data.interruptStaleJobs
import com.gulshan.pocketprint.model.JobState
import com.gulshan.pocketprint.model.PrintJobRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A job dies with the process running it. The row does not.
 *
 * Every writer lives in this one process - the manifest declares no
 * android:process - so a row still marked SENDING when the app next starts
 * belongs to a job that ended when the app did. Left as it was, it sat in the
 * history looking live for good and offered a Cancel button with nothing on
 * the other end of it.
 *
 * The settlement has to say the right thing, though, and the right thing is
 * very little. Bytes may have reached the printer. On a shipping label the
 * difference between "it did not print" and "nobody knows" is the difference
 * between a duplicate parcel and a reprint that was actually needed, so
 * INTERRUPTED claims neither.
 */
class InterruptedJobsTest {

    private val processStart = 10_000L
    private val now = 20_000L

    private fun job(
        id: String,
        state: JobState,
        createdAt: Long = processStart - 1_000L,
    ) = PrintJobRecord(
        id = id,
        printerId = "printer-1",
        printerName = "4BARCODE 4B-2044PA",
        documentName = "Label",
        state = state,
        createdAtEpochMs = createdAt,
        bytesSent = 512,
    )

    @Test
    fun `a job left sending by a dead process is settled as interrupted`() {
        val settled = interruptStaleJobs(
            listOf(job("job-1", JobState.SENDING)),
            startedBefore = processStart,
            now = now,
        )

        assertEquals(JobState.INTERRUPTED, settled.single().state)
        assertEquals(now, settled.single().finishedAtEpochMs)
    }

    @Test
    fun `queued and rendering are just as dead as sending`() {
        val settled = interruptStaleJobs(
            listOf(
                job("job-1", JobState.QUEUED),
                job("job-2", JobState.RENDERING),
                job("job-3", JobState.SENDING),
            ),
            startedBefore = processStart,
            now = now,
        )

        assertTrue(settled.all { it.state == JobState.INTERRUPTED })
    }

    @Test
    fun `a job this process started is left alone`() {
        // The reconcile runs from Application.onCreate, but a job can begin
        // while its write is still in flight. Without the cutoff this would
        // mark a job interrupted while it was printing.
        val live = job("job-1", JobState.SENDING, createdAt = processStart + 5)

        val settled = interruptStaleJobs(listOf(live), startedBefore = processStart, now = now)

        assertEquals(live, settled.single())
    }

    @Test
    fun `a job created in the same millisecond as the cutoff counts as live`() {
        val live = job("job-1", JobState.SENDING, createdAt = processStart)

        val settled = interruptStaleJobs(listOf(live), startedBefore = processStart, now = now)

        assertEquals(live, settled.single())
    }

    @Test
    fun `every terminal state is left exactly as it was`() {
        val finished = listOf(
            job("job-1", JobState.SENT),
            job("job-2", JobState.COMPLETED),
            job("job-3", JobState.FAILED),
            job("job-4", JobState.CANCELLED),
            job("job-5", JobState.INTERRUPTED),
        )

        assertEquals(
            finished,
            interruptStaleJobs(finished, startedBefore = processStart, now = now),
        )
    }

    @Test
    fun `interrupted is itself terminal, so a second launch does not churn`() {
        // If it were not, every start would rewrite the same rows with a new
        // finish time and the history would drift forward for ever.
        assertTrue(JobState.INTERRUPTED.terminal)

        val once = interruptStaleJobs(
            listOf(job("job-1", JobState.SENDING)),
            startedBefore = processStart,
            now = now,
        )
        val twice = interruptStaleJobs(once, startedBefore = processStart + 100_000, now = 99_000)

        assertEquals(once, twice)
    }

    @Test
    fun `terminal is exactly the set the Cancel button treats as finished`() {
        // JobsScreen offers Cancel for anything not terminal. If these two
        // ever disagree, either a dead job gets a button or a live one loses
        // the only way to stop it.
        val stillGoing = JobState.entries.filterNot { it.terminal }.toSet()

        assertEquals(
            setOf(JobState.QUEUED, JobState.RENDERING, JobState.SENDING),
            stillGoing,
        )
    }

    @Test
    fun `settling changes the state and the finish time and nothing else`() {
        val before = job("job-1", JobState.SENDING)
        val after = interruptStaleJobs(
            listOf(before),
            startedBefore = processStart,
            now = now,
        ).single()

        assertEquals(
            before,
            after.copy(state = before.state, finishedAtEpochMs = before.finishedAtEpochMs),
        )
        assertNull("nothing is known to have failed", after.error)
        assertFalse("and there is nothing to replay", after.replayable)
    }

    @Test
    fun `an unreadable history reports no change, so nothing is written over it`() {
        // A payload this build cannot read decodes to an empty list. Writing
        // that back would replace every job the user has with nothing - the
        // exact bug VersionedCodec exists to prevent. interruptStale writes
        // only when this reports something changed, and on an empty list
        // there is nothing to report.
        assertEquals(
            emptyList<PrintJobRecord>(),
            interruptStaleJobs(emptyList(), startedBefore = processStart, now = now),
        )
    }

    @Test
    fun `an interrupted record survives the stored format`() {
        // A new enum constant is the change that used to erase a history. The
        // codec decodes record by record, so this pins that the value written
        // here reads back as itself rather than costing the row.
        val codec = VersionedCodec(PrintJobRecord.serializer(), currentVersion = 1)
        val record = job("job-1", JobState.INTERRUPTED)

        val stored = codec.decode(codec.encode(listOf(record)))

        assertTrue(stored.healthy)
        assertEquals(record, stored.items.single())
    }
}
