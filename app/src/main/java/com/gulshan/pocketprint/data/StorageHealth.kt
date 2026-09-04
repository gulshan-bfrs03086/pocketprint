package com.gulshan.pocketprint.data

import com.gulshan.pocketprint.print.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app can still read what it saved.
 *
 * Losing a user's saved printers is the worst thing this app can do to them
 * short of printing the wrong thing, and the old failure mode was silent: an
 * empty list came back, the next save wrote it, and there was nothing anywhere
 * to say what had happened. So a loss is now stated in the interface, and
 * recorded in the diagnostics trail that goes into a printer report.
 *
 * Keyed by store so re-reading the same broken payload — which happens on every
 * emission of the flow — reports the same problem rather than a growing pile of
 * identical ones.
 */
object StorageHealth {

    private val _problems = MutableStateFlow<Map<String, String>>(emptyMap())
    val problems: StateFlow<Map<String, String>> = _problems.asStateFlow()

    fun report(store: String, stored: StoredList<*>) {
        val message = when {
            stored.unreadable != null ->
                "PocketPrint could not read its saved $store at all. The unreadable " +
                    "data has been set aside rather than deleted, but the list starts " +
                    "empty. This usually means the app was downgraded."

            stored.dropped > 0 ->
                "${stored.dropped} saved $store could not be read by this version and " +
                    "were left out. The rest are intact."

            else -> null
        }

        if (message == null) {
            if (_problems.value.containsKey(store)) {
                _problems.value = _problems.value - store
            }
            return
        }

        if (_problems.value[store] == message) return
        Diagnostics.record("StorageHealth", message)
        _problems.value = _problems.value + (store to message)
    }

    fun clear() { _problems.value = emptyMap() }
}
