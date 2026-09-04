package com.gulshan.pocketprint

import com.gulshan.pocketprint.ipp.IppCapabilityMapper
import com.gulshan.pocketprint.ipp.IppDecoder
import com.gulshan.pocketprint.ipp.IppJobState
import com.gulshan.pocketprint.ipp.IppStatus
import com.gulshan.pocketprint.ipp.IppTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * job-state is the only thing in this app that can turn "the bytes left" into
 * "the document printed", so what it decodes to decides whether a job is
 * reported as COMPLETED or honestly as SENT.
 */
class IppJobStateTest {

    @Test
    fun `job-state 9 is the one value that means completed`() {
        assertEquals(IppJobState.COMPLETED, stateOf(9))
        assertEquals(IppJobState.ABORTED, stateOf(8))
        assertEquals(IppJobState.CANCELED, stateOf(7))
        assertEquals(IppJobState.PROCESSING_STOPPED, stateOf(6))
        assertEquals(IppJobState.PROCESSING, stateOf(5))
        assertEquals(IppJobState.PENDING_HELD, stateOf(4))
        assertEquals(IppJobState.PENDING, stateOf(3))
    }

    @Test
    fun `only completed and the two terminal failures end the wait`() {
        val terminal = IppJobState.entries.filter { it.terminal }.toSet()
        assertEquals(
            setOf(IppJobState.COMPLETED, IppJobState.ABORTED, IppJobState.CANCELED),
            terminal,
        )
    }

    @Test
    fun `an unrecognised state decodes to null rather than to completed`() {
        // A printer inventing a state must never be read as "it printed".
        assertNull(stateOf(42))
        assertNull(IppJobState.of(null))
    }

    @Test
    fun `state reasons are carried through, and 'none' is dropped`() {
        val response = IppDecoder.decode(
            jobResponse(6, listOf("media-empty-error", "job-printing")),
        )
        assertEquals(IppJobState.PROCESSING_STOPPED, IppCapabilityMapper.jobState(response))
        assertEquals(
            listOf("media-empty-error", "job-printing"),
            IppCapabilityMapper.jobStateReasons(response),
        )

        val quiet = IppDecoder.decode(jobResponse(9, listOf("none")))
        assertEquals(emptyList<String>(), IppCapabilityMapper.jobStateReasons(quiet))
    }

    private fun stateOf(code: Int): IppJobState? =
        IppCapabilityMapper.jobState(IppDecoder.decode(jobResponse(code, emptyList())))

    /** A minimal Get-Job-Attributes response carrying one job group. */
    private fun jobResponse(state: Int, reasons: List<String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        out.writeByte(2); out.writeByte(0)
        out.writeShort(IppStatus.SUCCESSFUL_OK)
        out.writeInt(1)

        out.writeByte(IppTag.JOB_ATTRIBUTES)

        out.writeByte(IppTag.INTEGER)
        out.writeName("job-id")
        out.writeShort(4); out.writeInt(31)

        out.writeByte(IppTag.ENUM)
        out.writeName("job-state")
        out.writeShort(4); out.writeInt(state)

        // A 1setOf: every value after the first carries an empty name.
        reasons.forEachIndexed { index, reason ->
            out.writeByte(IppTag.KEYWORD)
            out.writeName(if (index == 0) "job-state-reasons" else "")
            val bytes = reason.toByteArray(Charsets.UTF_8)
            out.writeShort(bytes.size); out.write(bytes)
        }

        out.writeByte(IppTag.END_OF_ATTRIBUTES)
        out.flush()
        return buffer.toByteArray()
    }

    private fun DataOutputStream.writeName(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }
}
