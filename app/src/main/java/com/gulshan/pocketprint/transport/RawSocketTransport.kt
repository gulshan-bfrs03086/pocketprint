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

    private companion object {
        /** After a reply has started to arrive, how long a pause means it is complete. */
        const val REPLY_GAP_MS = 60L
    }

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

    /**
     * Waits up to [timeoutMs] for the printer to say something, like the other
     * transports do.
     *
     * This used to look at what had already arrived and return at once. A reply
     * to a query written a moment earlier has not crossed the network yet, so it
     * came back empty every time - and silence is read as an answer ("not
     * TSPL", "not answering"), which made every Raw printer look mute.
     *
     * Reads with a socket timeout rather than polling available(): on a socket
     * available() is 0 once the peer has hung up, so polling cannot tell a
     * closed printer from a quiet one, and read() returning -1 can. It returns
     * once a reply has arrived and a short gap has passed with nothing more,
     * since a multi-line status reply comes in pieces. Same distinction as
     * RFCOMM: nothing to say is empty, a closed peer with nothing collected
     * throws.
     */
    override suspend fun readAvailable(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext ByteArray(0)
        val collected = java.io.ByteArrayOutputStream()
        var ended = false
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                // Once something has arrived, only a brief wait for the rest of it.
                s.soTimeout = (if (collected.size() > 0) remaining.coerceAtMost(REPLY_GAP_MS) else remaining)
                    .toInt().coerceAtLeast(1)
                val n = try {
                    input.read(buf)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                if (n < 0) {
                    ended = true
                    break
                }
                collected.write(buf, 0, n)
            }
        } catch (_: Throwable) {
            // A read error is best effort, as it always was; only a clean end of
            // stream carries the meaning described above.
        }
        if (ended && collected.size() == 0) {
            throw TransportException("The printer closed the connection")
        }
        collected.toByteArray()
    }

    override fun close() {
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        out = null
        socket = null
    }
}
