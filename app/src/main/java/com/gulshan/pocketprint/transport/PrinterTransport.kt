package com.gulshan.pocketprint.transport

import java.io.Closeable
import java.io.InputStream

/**
 * A byte pipe to a printer. Transports move bytes only; deciding *what* bytes
 * to send is the renderer's job. Every implementation must be safe to close
 * twice and must not be reused after closing.
 */
interface PrinterTransport : Closeable {

    val description: String

    /** Establishes the connection. Throws on failure rather than returning a flag. */
    suspend fun open()

    /** Streams a payload, reporting cumulative bytes written. Returns the total. */
    suspend fun write(source: InputStream, onProgress: (Long) -> Unit = {}): Long

    suspend fun write(bytes: ByteArray, onProgress: (Long) -> Unit = {}): Long =
        write(bytes.inputStream(), onProgress)

    /**
     * Waits for queued bytes to actually reach the printer before the caller
     * closes the connection.
     *
     * A socket write returns once the data is handed to the OS buffer, not once
     * the peer has it. Closing immediately after writing tears the channel down
     * and silently discards whatever is still in flight, which looks like a
     * successful job that never prints. Every send path must call this before
     * closing.
     *
     * Deliberately abstract rather than a no-op default: a transport that
     * forgets to implement it reintroduces exactly the bug this exists to
     * prevent, and a default body would let that compile silently. A transport
     * with genuinely nothing to drain should implement it as an empty body and
     * say why.
     */
    suspend fun finish()

    /**
     * Reads whatever the printer volunteers within the timeout. Thermal printers
     * use this for status bytes; most page printers stay silent, so an empty
     * array is a normal result rather than an error.
     */
    suspend fun readAvailable(timeoutMs: Long = 400): ByteArray = ByteArray(0)
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
