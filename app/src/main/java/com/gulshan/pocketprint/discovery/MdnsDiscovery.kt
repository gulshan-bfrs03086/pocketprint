@file:Suppress("DEPRECATION")

package com.gulshan.pocketprint.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.model.PrinterCapabilities
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Bonjour/mDNS discovery for network printers.
 *
 * Three service types matter in practice:
 *   _ipp._tcp             IPP Everywhere / AirPrint (port 631)
 *   _ipps._tcp            the TLS flavour (port 631)
 *   _pdl-datastream._tcp  raw JetDirect (port 9100)
 *
 * NsdManager.resolveService can only service one request at a time; issuing a
 * second one before the first completes fails with LISTENER_ALREADY_IN_USE.
 * Resolves are therefore funnelled through a single-consumer channel.
 */
class MdnsDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "MdnsDiscovery"
        const val TYPE_IPP = "_ipp._tcp."
        const val TYPE_IPPS = "_ipps._tcp."
        const val TYPE_PDL = "_pdl-datastream._tcp."
        val ALL_TYPES = listOf(TYPE_IPP, TYPE_IPPS, TYPE_PDL)
    }

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(serviceTypes: List<String> = ALL_TYPES): Flow<Printer> = callbackFlow {
        // Some Wi-Fi chipsets drop multicast when the screen is off unless held.
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("pocketprint-mdns")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        // Single consumer keeps resolves strictly sequential.
        val resolver = launch {
            for (service in resolveQueue) {
                val resolved = resolveOnce(service)
                if (resolved != null) {
                    toPrinter(resolved, service.serviceType)?.let { trySend(it) }
                }
            }
        }

        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "discovery started for $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    resolveQueue.trySend(service)
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.d(TAG, "lost ${service.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "start failed for $serviceType: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            listeners += listener
            runCatching {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.w(TAG, "discoverServices($type) threw", it) }
        }

        awaitClose {
            listeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
            resolveQueue.close()
            resolver.cancel()
            runCatching { multicastLock?.release() }
        }
    }

    /**
     * Bridges the one-shot resolve callback into a suspending call. A failed
     * resolve yields null rather than throwing, so one unreachable printer
     * cannot tear down the whole discovery flow.
     */
    private suspend fun resolveOnce(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    if (done.compareAndSet(false, true)) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (done.compareAndSet(false, true)) cont.resume(serviceInfo)
                }
            }
            runCatching { nsdManager.resolveService(service, listener) }
                .onFailure { if (done.compareAndSet(false, true)) cont.resume(null) }
        }

    private fun toPrinter(info: NsdServiceInfo, rawType: String): Printer? {
        val rawHost = info.host?.hostAddress ?: return null
        // An IPv6 literal has to be bracketed in a URL authority or the URL is
        // malformed and every request to it fails to parse.
        val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) {
            "[${rawHost.substringBefore('%')}]"
        } else {
            rawHost
        }
        val port = info.port.takeIf { it > 0 } ?: return null
        val txt = info.attributes.orEmpty()
            .mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }

        val type = rawType.lowercase()
        val friendly = info.serviceName.ifBlank { host }
        val model = txt["ty"]?.takeIf { it.isNotBlank() }
        val location = txt["note"]?.takeIf { it.isNotBlank() }

        val address = when {
            type.contains("pdl-datastream") ->
                PrinterAddress.Raw(host = host, port = port)
            else -> {
                // "rp" is the resource path, usually ipp/print.
                val rp = txt["rp"]?.trim('/').orEmpty().ifBlank { "ipp/print" }
                PrinterAddress.Ipp(
                    host = host,
                    port = port,
                    path = "/$rp",
                    secure = type.contains("_ipps"),
                )
            }
        }

        return Printer(
            id = stableId(address, friendly),
            displayName = friendly,
            address = address,
            makeAndModel = model,
            location = location,
            capabilities = PrinterCapabilities.UNKNOWN_NETWORK,
            lastSeenEpochMs = System.currentTimeMillis(),
        )
    }

    private fun stableId(address: PrinterAddress, name: String): String = when (address) {
        is PrinterAddress.Ipp -> "ipp:${address.host}:${address.port}${address.path}"
        is PrinterAddress.Raw -> "raw:${address.host}:${address.port}"
        else -> "mdns:$name"
    }
}
