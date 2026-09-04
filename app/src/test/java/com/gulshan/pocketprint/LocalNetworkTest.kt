package com.gulshan.pocketprint

import com.gulshan.pocketprint.net.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The classic "it sees my printer but will not print". A printer-only access
 * point has no internet, so Android does not make it the default network: the
 * phone keeps cellular, and a socket opened without saying otherwise goes
 * looking for 192.168.1.50 over mobile data. mDNS still finds the printer,
 * because that runs over multicast on the link itself — which is what makes the
 * failure so confusing.
 */
class LocalNetworkTest {

    private fun choose(defaultIsLocal: Boolean, vararg candidates: String): String? =
        LocalNetwork.choose(defaultIsLocal, candidates.toList()) { it.startsWith("wifi") }

    @Test
    fun `an ordinary home Wi-Fi binds nothing at all`() {
        // The property that matters most here: on every setup that already
        // works, this change does nothing. Binding is only ever reached when
        // the default network is not the local one.
        assertNull(choose(defaultIsLocal = true, "wifi-a", "wifi-b"))
    }

    @Test
    fun `a cellular default with a Wi-Fi available picks the Wi-Fi`() {
        assertEquals("wifi-a", choose(defaultIsLocal = false, "wifi-a", "wifi-b"))
    }

    @Test
    fun `the most recently joined network wins`() {
        // Newest first: the network somebody just joined is the one with the
        // printer on it.
        assertEquals("wifi-b", choose(defaultIsLocal = false, "wifi-b", "wifi-a"))
    }

    @Test
    fun `nothing local available means nothing to bind to`() {
        assertNull(choose(defaultIsLocal = false))
        assertNull(choose(defaultIsLocal = false, "cell-a"))
    }

    @Test
    fun `a non-local candidate is skipped rather than bound to`() {
        // Binding a LAN socket to cellular would be worse than not binding.
        assertEquals("wifi-a", choose(defaultIsLocal = false, "cell-a", "wifi-a"))
    }
}
