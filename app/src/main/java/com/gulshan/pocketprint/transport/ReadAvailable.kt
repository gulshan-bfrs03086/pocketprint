package com.gulshan.pocketprint.transport

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Drains whatever a printer volunteers, until the deadline or until the stream
 * ends.
 *
 * Extracted from the transports so the one decision that matters can be tested
 * without a printer: the difference between a stream that has nothing to say
 * and a stream that has gone away.
 *
 * Up to targetSdk 36 a dropped RFCOMM socket throws IOException here. From
 * targetSdk 37 it returns -1 instead, and a loop that only checks for a
 * positive count will sit out its whole timeout and return the same empty array
 * that a working, quiet printer returns. That is not a cosmetic difference:
 * language detection is built on reading silence as an answer — no reply to the
 * TSPL status command is what rules TSPL out — so a connection dying mid-probe
 * would come back as a confident statement about the printer's dialect.
 *
 * So end of stream throws, unless something was already collected: a reply that
 * arrived is still a reply, and the printer hanging up afterwards does not
 * unsay it.
 */
internal fun readAvailableFrom(
    input: InputStream,
    timeoutMs: Long,
    pollMs: Long = 25,
    now: () -> Long = System::currentTimeMillis,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
): ByteArray {
    val deadline = now() + timeoutMs
    val collected = ByteArrayOutputStream()
    var ended = false

    try {
        while (now() < deadline) {
            val ready = input.available()
            if (ready > 0) {
                val buffer = ByteArray(ready.coerceAtMost(1024))
                val read = input.read(buffer)
                if (read < 0) {
                    ended = true
                    break
                }
                if (read > 0) collected.write(buffer, 0, read)
            } else {
                sleep(pollMs)
            }
        }
    } catch (_: Throwable) {
        // A read error is best effort, as it always was. Only a clean end of
        // stream carries the meaning this function exists to preserve.
    }

    if (ended && collected.size() == 0) {
        throw TransportException("The printer closed the connection")
    }
    return collected.toByteArray()
}
