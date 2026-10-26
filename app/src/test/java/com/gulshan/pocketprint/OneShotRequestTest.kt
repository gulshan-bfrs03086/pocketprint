package com.gulshan.pocketprint

import com.gulshan.pocketprint.ui.vm.OneShotRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request a launch leaves for a screen that may not exist yet, and that
 * must be answered once - not lost before the screen is ready, and not
 * replayed by every rotation after it was.
 */
class OneShotRequestTest {

    @Test
    fun `nothing is pending until somebody asks`() {
        assertFalse(OneShotRequest().pending.value)
    }

    @Test
    fun `a request stays pending however long nobody is there to answer it`() {
        // The first-run screen can be showing, or the right tab not composed
        // yet. Reading it is not answering it.
        val request = OneShotRequest()

        request.request()

        repeat(3) { assertTrue(request.pending.value) }
    }

    @Test
    fun `consuming it clears it, and it stays cleared`() {
        // The ViewModel outlives the activity, so a request that did not clear
        // would be answered again after every rotation.
        val request = OneShotRequest()
        request.request()

        request.consume()

        assertFalse(request.pending.value)
        assertFalse(request.pending.value)
    }

    @Test
    fun `asking twice before it is answered is one request`() {
        val request = OneShotRequest()
        request.request()
        request.request()

        request.consume()

        assertFalse("the second ask outlived the answer", request.pending.value)
    }

    @Test
    fun `a request after the last was answered is a new one`() {
        val request = OneShotRequest()
        request.request()
        request.consume()

        request.request()

        assertTrue(request.pending.value)
    }

    @Test
    fun `consuming when nothing was asked changes nothing`() {
        val request = OneShotRequest()

        request.consume()

        assertFalse(request.pending.value)
    }
}
