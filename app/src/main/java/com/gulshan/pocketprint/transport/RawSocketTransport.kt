package com.gulshan.pocketprint.transport

import android.content.Context
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.net.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * JetDirect / AppSocket printing: open a TCP socket, push the payload, close.
 * There is no acknowledgement in the protocol, so a successful write only means
 * the printer accepted the bytes, not that they printed.
 */
class RawSocketTransport(
    private val address: PrinterAddress.Raw,
    private val connectTimeoutMs: Int = 6_000,
    /** Only for finding the local network; null means use the default one. */
    private val context: Context? = null,
) : PrinterTransport {

    private var socket: Socket? = null
    private var out: OutputStream? = null

    override val description get() = "raw://${address.host}:${address.port}"

    override suspend fun open() = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 15_000
            // Before connect, because a socket can only be bound while it is
            // still unconnected - and after connect it is already on the wrong
            // network with nothing to say so but a timeout.
            context?.let { LocalNetwork.bind(it, s) }
            s.connect(InetSocketAddress(address.host, address.port), connectTimeoutMs)
            socket = s
            out = s.getOutputStream()
        } catch (t: Throwable) {
            close()
            throw TransportException("Could not reach ${address.host}:${address.port}", t)
        }
    }

    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val sink = out ?: throw TransportException("Socket not open")
            var total = 0L
            val buf = ByteArray(32 * 1024)
            source.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
            sink.flush()
            total
        }

    override suspend fun finish() = withContext(Dispatchers.IO) {
        runCatching { out?.flush() }
        // TCP will keep delivering after close, but a brief settle avoids
        // resetting the connection while the printer is still reading.
        kotlinx.coroutines.delay(200)
    }

    override suspend fun readAvailable(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext ByteArray(0)
        try {
            s.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            val input = s.getInputStream()
            val available = input.available()
            if (available <= 0) return@withContext ByteArray(0)
            val buf = ByteArray(available.coerceAtMost(4096))
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    override fun close() {
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        out = null
        socket = null
    }
}
