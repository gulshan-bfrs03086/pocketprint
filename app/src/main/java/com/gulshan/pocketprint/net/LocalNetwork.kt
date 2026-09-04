package com.gulshan.pocketprint.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.gulshan.pocketprint.print.Diagnostics
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Finds the network the printer is actually on, when it is not the default one.
 *
 * This is the classic "it sees my printer but will not print". A printer-only
 * access point has no internet, so Android does not make it the default
 * network: the phone keeps cellular as default and every socket opened without
 * saying otherwise goes out over mobile data, where 192.168.1.50 does not
 * exist. mDNS still finds the printer, because that runs over multicast on the
 * link itself, which is exactly what makes the failure so confusing — the
 * printer is listed, and then every job times out.
 *
 * The rule here is deliberately narrow. If the default network is already Wi-Fi
 * or Ethernet, nothing is bound and everything behaves as it always did; there
 * is no way for this to break a setup that works. Only when the default is
 * something else — cellular, or nothing — does a LAN network get picked out and
 * bound to.
 *
 * [start] must be called before any printing; the callback is what keeps the
 * answer current without polling or the deprecated enumeration APIs.
 */
object LocalNetwork {

    /** Newest first: the most recently joined Wi-Fi is the likeliest one. */
    private val lanNetworks = CopyOnWriteArrayList<Network>()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return

        // No INTERNET capability in the request: a printer-only access point
        // does not have one, and requiring it would filter out the exact
        // network this exists to find.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lanNetworks.remove(network)
                lanNetworks.add(0, network)
            }

            override fun onLost(network: Network) {
                lanNetworks.remove(network)
            }
        }

        started = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
    }

    /**
     * The network a LAN socket should be bound to, or null to leave it alone.
     *
     * Null is the common answer and the safe one: it means the default network
     * is already the local one.
     */
    fun preferred(context: Context): Network? {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return null

        val default = manager.activeNetwork
        return choose(
            defaultIsLocal = default != null && isLan(manager, default),
            candidates = lanNetworks,
            isLocal = { isLan(manager, it) },
        )
    }

    /**
     * The rule, away from the system services that answer its questions.
     *
     * Both halves are load-bearing. Returning null whenever the default network
     * is already local is what makes this change unable to break a setup that
     * works: on an ordinary home Wi-Fi nothing is bound and nothing changes.
     * And the candidate list is newest-first, because the network somebody just
     * joined is the one with the printer on it.
     */
    internal fun <T> choose(
        defaultIsLocal: Boolean,
        candidates: List<T>,
        isLocal: (T) -> Boolean,
    ): T? = if (defaultIsLocal) null else candidates.firstOrNull(isLocal)

    /**
     * Binds an unconnected socket to the local network, if that is needed.
     * Returns what it bound to, for the diagnostics trail.
     */
    fun bind(context: Context, socket: Socket): Network? {
        val network = preferred(context) ?: return null
        return runCatching {
            network.bindSocket(socket)
            Diagnostics.record(
                "LocalNetwork",
                "bound socket to the local network; the default network is not Wi-Fi",
            )
            network
        }.getOrNull()
    }

    private fun isLan(manager: ConnectivityManager, network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
