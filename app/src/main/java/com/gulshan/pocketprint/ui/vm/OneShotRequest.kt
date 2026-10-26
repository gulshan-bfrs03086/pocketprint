package com.gulshan.pocketprint.ui.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Somebody asked for this, and nobody has acted on it yet."
 *
 * For a request that arrives from outside the screen - an intent that opened
 * the app, say - and has to be carried out by whichever composable is ready
 * to, possibly several frames later: the first-run screen may be showing, or
 * the right tab may not be composed yet. A plain event would be lost in those
 * gaps, and a plain flag would be replayed by every rotation, because the
 * ViewModel outlives the activity. This is the flag with the second half: the
 * one that acts on it calls [consume], and it stays acted on.
 *
 * Asking twice before it is consumed is one request. Both mean the same thing,
 * and nobody wants the screen to scroll twice.
 */
class OneShotRequest {
    private val state = MutableStateFlow(false)

    /** True from [request] until [consume]. */
    val pending: StateFlow<Boolean> = state.asStateFlow()

    fun request() {
        state.value = true
    }

    fun consume() {
        state.value = false
    }
}
