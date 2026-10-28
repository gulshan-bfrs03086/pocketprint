package com.gulshan.pocketprint.transport

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * A printer reached through TSC's own library instead of our own Bluetooth code.
 *
 * Everything the app sends - the job, setup's test label, the status questions -
 * goes through the library's calls, so a TSC printer is driven the way TSC's
 * documentation drives it. The rest of the app does not change: the engine and
 * setup talk to a [PrinterTransport] and do not know which one.
 *
 * What it costs, because the library is a thin blocking wrapper over one secure
 * classic-Bluetooth socket and no more:
 *  - no Bluetooth LE: the library has no GATT path;
 *  - the library says only "1" or "-1", never why;
 *  - it cannot read the printer's replies, only ask the two questions it has
 *    calls for. The engine and setup ask exactly those two - `ESC ! ?` for
 *    status and `~!T` for the model - and [write] answers them through the
 *    library, so [readAvailable] has something to hand back. Any other query
 *    gets no reply.
 *
 * The status the library gives is its own `ESC ! S` answer (Ready, Printing
 * Batch, Pause, Paper Jam, ...), mapped by [TscStatusWords] to the one-byte
 * status the completion logic reads. A printer that says nothing stays silent
 * here too, which is how a printer busy taking in a job looks.
 *
 * [libraryFactory] is called in [open], on the main thread: the library's class
 * is an Activity, and constructing one builds a Handler, which throws on a
 * thread with no Looper - and everything else here runs on a background one.
 *
 * How it connects depends on the Android version, and only on that.
 * [skipDiscoveryCancel] is true from Android 12: there the library's own
 * `openport` cannot work for this app, because its first act is a
 * `cancelDiscovery()` that needs BLUETOOTH_SCAN, which the app does not hold.
 * So the socket is connected by the app and handed to the library
 * ([TscLibrary.openportWithoutDiscoveryCancel]), which needs only the
 * BLUETOOTH_CONNECT every Bluetooth printer already needs. On Android 11 and
 * older `openport` is used as it is, because the install-time BLUETOOTH_ADMIN
 * covers its cancelDiscovery() there.
 *
 * [hasConnectPermission] is checked before anything else. A job runs in a
 * service, where nothing can ask for a permission, so a missing one fails at
 * once with a message that says where to grant it.
 */
class TscSdkTransport(
    private val mac: String,
    private val libraryFactory: () -> TscLibrary,
    private val queryWaitMs: Int = QUERY_WAIT_MS,
    private val onMainThread: suspend (() -> TscLibrary) -> TscLibrary = { make ->
        withContext(Dispatchers.Main) { make() }
    },
    private val skipDiscoveryCancel: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    private val hasConnectPermission: () -> Boolean = { true },
) : PrinterTransport {

    /**
     * Null until opened and again once closed. Taken with getAndSet on close, because
     * close() is also what unblocks a write that is stuck in the library, and so can
     * arrive from another thread while the first is still closing.
     */
    private val library = AtomicReference<TscLibrary?>(null)
    private var bytesWritten = 0L
    private var reply: ByteArray = ByteArray(0)

    override val description get() = "tsc://$mac"

    override suspend fun open() {
        if (!hasConnectPermission()) throw noConnectPermission()
        val lib = onMainThread(libraryFactory)
        // Held before dialling, not after. openport is a blocking call that cannot
        // be interrupted: a job cancelled while it connects still gets a connected
        // socket back, and a library that was only stored once openport returned
        // would be dropped on the floor with the socket open. Stored first, the
        // close() that always follows - the engine wraps every transport in use{} -
        // reaches it, whatever openport did, including a half-made socket.
        library.set(lib)
        val result = try {
            withContext(Dispatchers.IO) {
                if (skipDiscoveryCancel) lib.openportWithoutDiscoveryCancel(mac) else lib.openport(mac)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw if (skipDiscoveryCancel) {
                // Our own connect, refused: BLUETOOTH_CONNECT was revoked between the
                // check above and the dial.
                noConnectPermission(e)
            } else {
                // The library asks for BLUETOOTH_ADMIN before it dials, and does not
                // catch the refusal. The manifest declares it, so this is a device
                // that does not honour that.
                TransportException(
                    "Android refused the TSC library a Bluetooth permission: ${e.message}. " +
                        "Set this printer back to Automatic to print through PocketPrint's " +
                        "own Bluetooth.",
                    e,
                )
            }
        }
        if (result == null) {
            throw TransportException(
                "This copy of TSC's library cannot be used on Android 12 and newer: PocketPrint " +
                    "connects the printer itself there and hands the connection to the library, " +
                    "and this version of the library is not laid out the way that needs. Set " +
                    "this printer back to Automatic, or build with the tscsdk.jar described in " +
                    "docs/TSC_SDK.md.",
            )
        }
        if (result != OK) {
            throw TransportException(
                "The TSC library could not open $mac (it answered \"$result\" and gives no reason). " +
                    "Check the printer is on, in range and paired, and that Bluetooth is on.",
            )
        }
    }

    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val lib = library.get() ?: throw TransportException("The TSC connection is not open")
            val bytes = source.readBytes()

            when {
                // The two questions the engine and setup ask. The library has a
                // call for each; the answer waits in [reply] for readAvailable.
                bytes.contentEquals(STATUS_QUERY) -> {
                    lib.discardUnread()
                    val word = lib.status(queryWaitMs)
                    if (word == TscStatusWords.LINK_FAILED) throw linkLost()
                    reply = TscStatusWords.toStatusByte(word)?.let { byteArrayOf(it.toByte()) }
                        ?: ByteArray(0)
                    bytes.size.toLong()
                }
                bytes.startsWith(MODEL_QUERY) -> {
                    lib.discardUnread()
                    val name = lib.printername(queryWaitMs)
                    if (name == TscStatusWords.LINK_FAILED) throw linkLost()
                    reply = cleaned(name)
                    bytes.size.toLong()
                }
                else -> {
                    reply = ByteArray(0)
                    val result = lib.sendcommand(bytes)
                    if (result != OK) {
                        throw TransportException(
                            "The TSC library could not send ${bytes.size} bytes to $mac " +
                                "(it answered \"$result\"). The connection may have dropped.",
                        )
                    }
                    bytesWritten += bytes.size
                    onProgress(bytes.size.toLong())
                    bytes.size.toLong()
                }
            }
        }

    /**
     * The library has no way to flush, so the wait is the same one the plain
     * Bluetooth transport uses: a floor for the stack, plus a little for what
     * is still in flight.
     */
    override suspend fun finish() = withContext(Dispatchers.IO + NonCancellable) {
        val residual = bytesWritten.coerceAtMost(MAX_RESIDUAL_BYTES)
        delay((DRAIN_BASE_MS + residual / BYTES_PER_MS).coerceAtMost(DRAIN_MAX_MS))
        bytesWritten = 0
    }

    override suspend fun readAvailable(timeoutMs: Long): ByteArray {
        val answer = reply
        reply = ByteArray(0)
        return answer
    }

    override fun close() {
        library.getAndSet(null)?.let { runCatching { it.closeport(0) } }
    }

    /**
     * Worded so JobError recognises it ("permission" and "Bluetooth"), and says where
     * the switch is, because a job cannot show the prompt itself.
     */
    private fun noConnectPermission(cause: Throwable? = null) = TransportException(
        "PocketPrint does not have the Bluetooth permission it needs to connect to $mac. " +
            "Allow Nearby devices on PocketPrint's Printers screen, or in Android Settings > " +
            "Apps > PocketPrint > Permissions.",
        cause,
    )

    /**
     * The library's "-1" on a question: the connection is gone. Thrown, not read
     * as silence - the engine ends a job whose link dropped at once (LinkLost),
     * and a printer that has merely gone quiet it waits for.
     */
    private fun linkLost() = TransportException(
        "The TSC library lost its connection to $mac. The printer may have switched off or " +
            "gone out of range.",
    )

    /** The library hands back a fixed 1 KB buffer; keep only what was written into it. */
    private fun cleaned(text: String): ByteArray =
        text.trimEnd('\u0000', '\r', '\n', ' ').toByteArray(Charsets.ISO_8859_1)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object {
        private const val OK = "1"

        /** `ESC ! ?` - the status question the engine and setup ask. */
        private val STATUS_QUERY = byteArrayOf(0x1B, 0x21, 0x3F)

        /** `~!T` - the model question; setup follows it with CR LF, which the library does not send. */
        private val MODEL_QUERY = byteArrayOf(0x7E, 0x21, 0x54)

        /** The library always waits this long for an answer, answered or not. */
        private const val QUERY_WAIT_MS = 500

        private const val DRAIN_BASE_MS = 700L
        private const val BYTES_PER_MS = 8L
        private const val MAX_RESIDUAL_BYTES = 48_000L
        private const val DRAIN_MAX_MS = 8_000L
    }
}
