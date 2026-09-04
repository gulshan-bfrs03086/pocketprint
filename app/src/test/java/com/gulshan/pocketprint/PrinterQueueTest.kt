package com.gulshan.pocketprint

import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.print.PrinterQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A thermal printer has one RFCOMM slot. Two jobs inside it at once do not
 * interleave politely — they produce one garbled document from two, or a
 * connection failure. So overlap here is the bug the queue exists to prevent,
 * and it is worth asserting rather than assuming.
 */
class PrinterQueueTest {

    private fun bluetooth(mac: String, link: BtLink = BtLink.AUTO) = Printer(
        id = "id-$mac-$link",
        displayName = "Printer $mac",
        address = PrinterAddress.Bluetooth(mac = mac, link = link),
    )

    @Test
    fun `the key is the device, not the saved record`() {
        // The same hardware saved twice, once as Classic and once as auto-probe.
        // The printer does not care that the app filed them separately.
        val classic = bluetooth("AA:BB:CC:DD:EE:FF", BtLink.CLASSIC)
        val auto = bluetooth("aa:bb:cc:dd:ee:ff", BtLink.AUTO)
        assertNotEquals(classic.id, auto.id)
        assertEquals(PrinterQueue.keyOf(classic), PrinterQueue.keyOf(auto))
    }

    @Test
    fun `different devices get different queues`() {
        assertNotEquals(
            PrinterQueue.keyOf(bluetooth("AA:BB:CC:DD:EE:01")),
            PrinterQueue.keyOf(bluetooth("AA:BB:CC:DD:EE:02")),
        )
        assertNotEquals(
            PrinterQueue.keyOf(
                Printer("a", "a", PrinterAddress.Raw("192.168.1.9", 9100)),
            ),
            PrinterQueue.keyOf(
                Printer("b", "b", PrinterAddress.Raw("192.168.1.9", 9101)),
            ),
        )
    }

    @Test
    fun `jobs on one printer never overlap`() = runBlocking {
        val printer = bluetooth("AA:BB:CC:00:00:01")
        val inside = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)

        withContext(Dispatchers.Default) {
            (1..8).map {
                async {
                    PrinterQueue.withPrinter(printer) {
                        val now = inside.incrementAndGet()
                        maxSeen.updateAndGet { seen -> maxOf(seen, now) }
                        delay(5)
                        inside.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, maxSeen.get())
    }

    @Test
    fun `jobs on different printers do not block each other`() = runBlocking {
        val slow = bluetooth("AA:BB:CC:00:00:02")
        val other = bluetooth("AA:BB:CC:00:00:03")
        var otherRanWhileSlowHeld = false

        PrinterQueue.withPrinter(slow) {
            withContext(Dispatchers.Default) {
                PrinterQueue.withPrinter(other) { otherRanWhileSlowHeld = true }
            }
        }

        assertTrue(otherRanWhileSlowHeld)
    }

    @Test
    fun `waiting is reported only when the job actually queues`() = runBlocking {
        val printer = bluetooth("AA:BB:CC:00:00:04")
        var uncontendedWaited = false

        PrinterQueue.withPrinter(printer, onWaiting = { uncontendedWaited = true }) { }
        assertFalse(uncontendedWaited)

        val waited = AtomicInteger(0)
        val resumed = AtomicInteger(0)
        withContext(Dispatchers.Default) {
            (1..3).map {
                async {
                    PrinterQueue.withPrinter(
                        printer,
                        onWaiting = { waited.incrementAndGet() },
                        onResumed = { resumed.incrementAndGet() },
                    ) { delay(10) }
                }
            }.awaitAll()
        }

        // Three jobs, one printer: at least two of them had to wait, and every
        // job that waited was told when it stopped waiting.
        assertTrue("expected contention, saw ${waited.get()}", waited.get() >= 2)
        assertEquals(waited.get(), resumed.get())
    }

    @Test
    fun `a job that throws still releases the printer`() = runBlocking {
        val printer = bluetooth("AA:BB:CC:00:00:05")
        runCatching {
            PrinterQueue.withPrinter(printer) { error("transport blew up") }
        }
        assertFalse(PrinterQueue.isBusy(printer))
    }
}
