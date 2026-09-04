package com.gulshan.pocketprint.print

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, in-memory trail of what the app did to a printer.
 *
 * These numbers already existed — how many dots a page rasterised to, what
 * percentage of the packed bitmap was ink, how many bytes of TSPL came out, what
 * each dialect answered when probed — and they are what made a blank-label bug
 * tractable at all: 30% ink and 107,357 bytes of TSPL says the rendering is
 * fine and the fault is downstream. But they went to logcat, in debug builds
 * only, which means they were available to exactly one person on one machine.
 *
 * Kept here instead, so they can be handed to whoever is trying to help. Bounded
 * and in memory: this is a debugging aid, not a log file, and a printing app has
 * no business accumulating a history of what its user printed on disk.
 */
object Diagnostics {

    private const val CAPACITY = 80

    private val lock = Any()
    private val entries = ArrayDeque<String>(CAPACITY)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun record(tag: String, message: String) {
        Log.i(tag, message)
        val line = "${clock.format(Date())}  $tag  $message"
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
    }

    fun recent(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}
