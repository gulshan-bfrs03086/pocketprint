package com.gulshan.pocketprint.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.gulshan.pocketprint.model.PrinterAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * RFCOMM (Serial Port Profile) printing. Essentially every Bluetooth receipt
 * and label printer exposes SPP and swallows a raw ESC/POS, TSPL or ZPL stream.
 */
class BluetoothTransport(
    private val context: Context,
    private val address: PrinterAddress.Bluetooth,
    /** Thermal printers have small buffers; large writes overrun them. */
    private val chunkSize: Int = 512,
    private val chunkDelayMs: Long = 12,
    private val connectTimeoutMs: Long = 12_000,
) : PrinterTransport {

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var bytesWritten = 0L

    override val description get() = "bt://${address.mac}"

    companion object {
        private const val TAG = "BluetoothTransport"
        private const val PAIRING_TIMEOUT_MS = 90_000L

        /** Drain budget before closing: a floor, plus time proportional to size. */
        private const val DRAIN_BASE_MS = 700L
        private const val BYTES_PER_MS = 8L
        private const val DRAIN_MAX_MS = 20_000L

        /**
         * Ceiling on how much data can plausibly still be buffered when write()
         * returns. Anything beyond this was already sent while write() blocked
         * on a full socket.
         */
        private const val MAX_RESIDUAL_BYTES = 48_000L

        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun hasConnectPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Legacy BLUETOOTH permission is install-time on API 30 and below.
            }
    }

    /** One rung of the connect ladder. */
    private class Attempt(val name: String, val create: () -> BluetoothSocket)

    @SuppressLint("MissingPermission")
    override suspend fun open() = withContext(Dispatchers.IO) {
        if (!hasConnectPermission(context)) {
            throw TransportException("Bluetooth permission not granted")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw TransportException("This device has no Bluetooth adapter")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address.mac)
        } catch (t: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address ${address.mac}", t)
        }

        // A BLE-only device has no BR/EDR radio, so RFCOMM can never reach it.
        // Without this check it just pages forever and looks like a timeout.
        if (runCatching { device.type }.getOrNull() == BluetoothDevice.DEVICE_TYPE_LE) {
            throw TransportException(
                "${address.mac} is a Bluetooth Low Energy device. PocketPrint speaks " +
                    "classic Bluetooth (RFCOMM) only, so this printer cannot be reached yet.",
            )
        }

        // Pairing must complete before the socket is built: the secure socket
        // would otherwise try to drive pairing mid-connect and simply expire.
        ensureBonded(adapter, device)

        // Inquiry starves the page attempt, and cancelDiscovery is asynchronous.
        runCatching { adapter.cancelDiscovery() }
        delay(250)

        val attempts = listOf(
            Attempt("secure SPP") { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            // Legacy PIN-0000 controllers (HC-05 and relatives) bring the channel
            // up but mishandle authentication, so the insecure socket is the one
            // that actually connects to them.
            Attempt("insecure SPP") {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            },
            Attempt("insecure channel 1") { reflectSocket(device, "createInsecureRfcommSocket") },
            Attempt("secure channel 1") { reflectSocket(device, "createRfcommSocket") },
        )

        val failures = mutableListOf<String>()

        for ((index, attempt) in attempts.withIndex()) {
            // Retrying instantly just reproduces the failure.
            if (index > 0) delay(700)

            val candidate = runCatching { attempt.create() }.getOrElse {
                failures += "${attempt.name}: not available (${it.javaClass.simpleName})"
                null
            } ?: continue

            val startedAt = SystemClock.elapsedRealtime()
            try {
                // connect() blocks uninterruptibly; runInterruptible plus a
                // timeout makes cancelling a job actually cancel the attempt.
                withTimeout(connectTimeoutMs) { runInterruptible { candidate.connect() } }
                socket = candidate
                out = candidate.outputStream
                Log.i(
                    TAG,
                    "connected to ${address.mac} via ${attempt.name} in " +
                        "${SystemClock.elapsedRealtime() - startedAt} ms",
                )
                return@withContext
            } catch (cancel: CancellationException) {
                runCatching { candidate.close() }
                throw cancel
            } catch (t: Throwable) {
                runCatching { candidate.close() }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                failures += "${attempt.name} failed after $elapsed ms: ${t.message}"
                Log.w(TAG, "${attempt.name} failed after $elapsed ms", t)
            }
        }

        throw TransportException(explain(device, failures))
    }

    /**
     * Turns the attempt log into something a person can act on. Every rung
     * reports the same "read failed ... read ret: -1" string, so the useful
     * signal is which rung failed and how long it took.
     */
    @SuppressLint("MissingPermission")
    private fun explain(device: BluetoothDevice, failures: List<String>): String {
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
            .getOrDefault(false)
        val hint = when {
            !bonded ->
                "The printer is not paired. Pair it in Android Settings first " +
                    "(the PIN is usually 0000 or 1234), then try again."
            failures.any { slowFailure(it) } ->
                "Every attempt hung, which usually means the printer is off, out of " +
                    "range, asleep, or still connected to another device. Power-cycle " +
                    "it and make sure nothing else is holding the connection."
            else ->
                "The printer refused the connection quickly. It may not expose a serial " +
                    "port profile, or it may be a Bluetooth Low Energy model."
        }
        return "Could not connect to ${address.mac}. $hint\n\nAttempts:\n" +
            failures.joinToString("\n") { "  - $it" }
    }

    private fun slowFailure(entry: String): Boolean =
        Regex("failed after (\\d+) ms").find(entry)?.groupValues?.get(1)
            ?.toLongOrNull()?.let { it > 5_000 } ?: false

    /**
     * Drives pairing to completion. createBond() is asynchronous and reports via
     * ACTION_BOND_STATE_CHANGED, so the receiver is registered before the call:
     * the BOND_BONDING transition can be broadcast before createBond() returns.
     */
    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(adapter: BluetoothAdapter, device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                @Suppress("DEPRECATION")
                val changed = intent?.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE,
                )
                // Bond broadcasts fire for every device, not just this one.
                if (changed?.address != device.address) return

                when (
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE,
                    )
                ) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> {
                        // NONE straight after BONDING means rejected or expired.
                        val previous = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1,
                        )
                        if (previous == BluetoothDevice.BOND_BONDING) bonded.complete(false)
                    }
                    else -> Unit // BOND_BONDING: keep waiting.
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            runCatching { adapter.cancelDiscovery() }

            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                throw TransportException(
                    "Could not start pairing with ${address.mac}. Pair the printer in " +
                        "Android Settings (PIN is usually 0000), then try again.",
                )
            }

            // Someone has to accept the dialog and type the PIN.
            val accepted = withTimeoutOrNull(PAIRING_TIMEOUT_MS) { bonded.await() }
                ?: throw TransportException(
                    "Pairing with ${address.mac} timed out. Check the printer is powered " +
                        "on, in range, and in pairing mode.",
                )

            if (!accepted) {
                throw TransportException(
                    "Pairing with ${address.mac} was rejected. The PIN is usually 0000.",
                )
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }

        // BOND_BONDED arrives before the stack finishes its post-bond SDP
        // refresh; connecting inside that window reproduces the timeout.
        delay(1_500)
    }

    /** Binds RFCOMM channel 1 directly, for printers with no usable SDP record. */
    @SuppressLint("MissingPermission")
    private fun reflectSocket(device: BluetoothDevice, methodName: String): BluetoothSocket {
        val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val sink = out ?: throw TransportException("Bluetooth socket not open")
            var total = 0L
            val buf = ByteArray(chunkSize)
            source.use { input ->
                while (true) {
                    // Honour cancellation between chunks, so cancelling a job
                    // does not keep pushing bytes at the printer.
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    sink.flush()
                    total += n
                    onProgress(total)
                    // Give the printer's buffer room to drain.
                    if (chunkDelayMs > 0) delay(chunkDelayMs)
                }
            }
            sink.flush()
            bytesWritten += total
            total
        }

    /**
     * RFCOMM has no application-level acknowledgement, so the only safe move is
     * to wait for the link to drain before closing. The delay scales with the
     * payload: a text label is a few hundred bytes, a rasterised page is tens of
     * kilobytes and takes seconds to clock out over Bluetooth.
     */
    override suspend fun finish() = withContext(Dispatchers.IO + NonCancellable) {
        runCatching { out?.flush() }

        // write() already paces itself against the printer, chunk by chunk, so
        // by the time we get here most of the payload has been clocked out.
        // Only the stack and the printer's own buffer can still hold data, so
        // scale the wait by that residual rather than by the whole job: a
        // 123 KB label would otherwise idle here for 16 seconds after it had
        // already finished printing.
        val residual = bytesWritten.coerceAtMost(MAX_RESIDUAL_BYTES)
        val linger = (DRAIN_BASE_MS + residual / BYTES_PER_MS).coerceAtMost(DRAIN_MAX_MS)

        // NonCancellable on purpose: cancelling here would close the socket with
        // bytes still in flight, which is precisely the bug this prevents.
        delay(linger)

        // A reply is a bonus, not a requirement: most printers stay silent.
        runCatching { readAvailable(120) }
        bytesWritten = 0
    }

    override suspend fun readAvailable(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val input: InputStream = socket?.inputStream ?: return@withContext ByteArray(0)
        readAvailableFrom(input, timeoutMs)
    }

    override fun close() {
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        out = null
        socket = null
    }
}
