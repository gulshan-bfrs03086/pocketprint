package com.gulshan.pocketprint

import com.gulshan.pocketprint.printservice.Rebind
import com.gulshan.pocketprint.printservice.shouldRebind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequence that makes the print framework bind PocketPrintService afresh,
 * after a stop or an update left it holding a binding that never connects.
 *
 * What these pin was found on a device: disabling and re-enabling the service
 * component inside the package manager's broadcast delay (ten seconds on
 * Android 9) does nothing at all, because the print framework only ever hears
 * about the final state. 0 s and 3 s gaps left the print dialog searching;
 * 20 s cleared it.
 */
class RebindTest {

    private class FakeComponent(var disabled: Boolean = false) : Rebind.ComponentSwitch {
        val calls = mutableListOf<String>()
        override fun isDisabled() = disabled
        override fun disable() { disabled = true; calls += "disable" }
        override fun restore() { disabled = false; calls += "restore" }
    }

    /** Runs nothing until told how much time has passed. */
    private class ManualTimer : Rebind.Timer {
        private var now = 0L
        private val due = mutableListOf<Pair<Long, () -> Unit>>()
        override fun after(delayMs: Long, action: () -> Unit) { due += (now + delayMs) to action }
        fun advance(ms: Long) {
            now += ms
            val ready = due.filter { it.first <= now }
            due.removeAll(ready)
            ready.forEach { it.second() }
        }
    }

    @Test
    fun `the service is switched off at once and back on only after the hold`() {
        val component = FakeComponent()
        val timer = ManualTimer()
        val rebind = Rebind(component, timer)

        assertTrue(rebind.start())
        assertTrue(component.disabled)

        timer.advance(Rebind.HOLD_MS - 1)
        assertTrue("re-enabled before the hold was over", component.disabled)

        timer.advance(1)
        assertFalse(component.disabled)
        assertEquals(listOf("disable", "restore"), component.calls)
    }

    @Test
    fun `the hold outlasts the package manager's ten second broadcast delay`() {
        // Shorter and the print framework is told about one change, to a
        // component that is enabled, and does nothing.
        assertTrue(Rebind.HOLD_MS > 10_000L)
    }

    @Test
    fun `done is reported only once the service is enabled again`() {
        val component = FakeComponent()
        val timer = ManualTimer()
        var done = false
        val rebind = Rebind(component, timer)

        rebind.start { done = !component.disabled }
        assertFalse(done)

        timer.advance(Rebind.HOLD_MS)
        assertTrue("done before the service was back", done)
        assertFalse(rebind.running)
    }

    @Test
    fun `a second launch during the hold does not start another one`() {
        val component = FakeComponent()
        val timer = ManualTimer()
        val rebind = Rebind(component, timer)

        rebind.start()
        timer.advance(5_000)

        assertFalse(rebind.start())
        timer.advance(Rebind.HOLD_MS)
        assertEquals(listOf("disable", "restore"), component.calls)
    }

    @Test
    fun `a service left disabled by a process that died mid-hold is enabled on the next start`() {
        // Without this, swiping the app away inside the hold would leave
        // PocketPrint missing from every print dialog for good.
        val component = FakeComponent(disabled = true)
        val rebind = Rebind(component, ManualTimer())

        assertTrue(rebind.repairIfLeftDisabled())
        assertFalse(component.disabled)
    }

    @Test
    fun `repair leaves an enabled service alone`() {
        val component = FakeComponent(disabled = false)
        val rebind = Rebind(component, ManualTimer())

        assertFalse(rebind.repairIfLeftDisabled())
        assertTrue(component.calls.isEmpty())
    }

    @Test
    fun `repair does not cut short a rebind that is still holding`() {
        val component = FakeComponent()
        val timer = ManualTimer()
        val rebind = Rebind(component, timer)
        rebind.start()

        assertFalse(rebind.repairIfLeftDisabled())
        assertTrue(component.disabled)
    }

    @Test
    fun `rebinds only after a possible stop, and never under a running service`() {
        assertTrue(shouldRebind(mayHaveBeenStopped = true, serviceRunning = false))
        // Connected: the framework's binding works, and disabling the service
        // would only pull PocketPrint out of a dialog that is using it.
        assertFalse(shouldRebind(mayHaveBeenStopped = true, serviceRunning = true))
        // Not stopped since the last launch: every reproduction of the wedge
        // needed the package stopped first.
        assertFalse(shouldRebind(mayHaveBeenStopped = false, serviceRunning = false))
        assertFalse(shouldRebind(mayHaveBeenStopped = false, serviceRunning = true))
    }
}
