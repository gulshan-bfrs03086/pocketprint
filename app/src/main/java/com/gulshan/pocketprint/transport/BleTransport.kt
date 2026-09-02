package com.gulshan.pocketprint.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gulshan.pocketprint.model.PrinterAddress
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * BLE (GATT) printing. Cheap thermal printers increasingly ship an LE-only
 * radio with no RFCOMM server at all: [BluetoothTransport] can never reach
 * those and fails with "read failed, socket might closed or timeout".
 *
 * The device side is a UART bridge, so this is still a byte pipe -- we just
 * push the payload through a writable characteristic MTU-sized chunk at a time.
 */
class BleTransport(
    private val context: Context,
    private val address: PrinterAddress.Bluetooth,
    /** Absolute ceiling per ATT write. The framework itself throws above 512. */
    private val maxChunkBytes: Int = 512,
    /** Force an acknowledged write (real round trip) every N unacknowledged ones. */
    private val ackEvery: Int = 8,
    /** Extra breather after each acknowledged window; printer buffers are small. */
    private val windowPauseMs: Long = 15,
    private val connectTimeoutMs: Long = 20_000,
    private val opTimeoutMs: Long = 5_000,
) : PrinterTransport {

    private val main = Handler(Looper.getMainLooper())

    /** The withTimeoutOrNull around each op may have cancelled us already. */
    private fun AtomicReference<CancellableContinuation<Int>?>.settle(value: Int) {
        getAndSet(null)?.takeIf { it.isActive }?.resume(value)
    }

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    /** ATT_MTU as negotiated; payload per write is this minus the 3-byte ATT header. */
    @Volatile private var attMtu = DEFAULT_ATT_MTU

    /** One GATT operation may be outstanding per connection; this is the gate. */
    private val opGate = Mutex()
    private val pendingWrite = AtomicReference<CancellableContinuation<Int>?>(null)
    private val pendingConnect = AtomicReference<CancellableContinuation<Int>?>(null)
    private val pendingDiscover = AtomicReference<CancellableContinuation<Int>?>(null)
    private val pendingMtu = AtomicReference<CancellableContinuation<Int>?>(null)

    /** Whatever the printer volunteered on its notify characteristic. */
    private val inbox = ByteArrayOutputStream()

    override val description: String
        get() = "ble://${address.mac}" +
            (writeCharacteristic?.let { "/${it.uuid}" } ?: "")

    /** Usable ESC/POS bytes per ATT write. */
    private val chunkSize: Int
        get() = (attMtu - ATT_HEADER_BYTES).coerceIn(MIN_CHUNK_BYTES, maxChunkBytes)

    // ---------------------------------------------------------------- open

    @SuppressLint("MissingPermission")
    override suspend fun open() = withContext(Dispatchers.IO) {
        if (!BluetoothTransport.hasConnectPermission(context)) {
            throw TransportException("Bluetooth permission not granted")
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw TransportException("This device has no Bluetooth LE radio")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw TransportException("This device has no Bluetooth adapter")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")

        val device = try {
            adapter.getRemoteDevice(address.mac)
        } catch (t: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address ${address.mac}", t)
        }

        // A classic inquiry starves the LE connection attempt the same way it
        // starves RFCOMM.
        runCatching { adapter.cancelDiscovery() }

        // Status 133 on the first attempt is endemic; the cure is close() and
        // retry, never disconnect() alone.
        var lastError: String? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            val status = connectOnce(device)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                discoverAndBind()
                return@withContext
            }
            lastError = "GATT status $status"
            teardownGatt()
            delay(RETRY_BACKOFF_MS * (attempt + 1))
        }
        throw TransportException("Could not connect to ${address.mac} over BLE ($lastError)")
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(device: BluetoothDevice): Int {
        val status = withTimeoutOrNull(connectTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingConnect.set(cont)
                // connectGatt must run on a Looper thread on older stacks or the
                // callback registration races and yields 133.
                main.post {
                    val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        device.connectGatt(
                            context.applicationContext,
                            /* autoConnect = */ false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK,
                            main,
                        )
                    } else {
                        // 4-arg overload is API 23, so safe at minSdk 24.
                        device.connectGatt(
                            context.applicationContext,
                            /* autoConnect = */ false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE,
                        )
                    }
                    if (g == null) {
                        pendingConnect.settle(BluetoothGatt.GATT_FAILURE)
                    } else {
                        gatt = g
                    }
                }
                cont.invokeOnCancellation { pendingConnect.compareAndSet(cont, null) }
            }
        }
        pendingConnect.set(null)
        return status ?: BluetoothGatt.GATT_FAILURE
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverAndBind() {
        val g = gatt ?: throw TransportException("GATT went away before discovery")

        // Bonded devices run encryption setup concurrently with the first
        // discovery; a short settle avoids an empty service list.
        delay(SERVICE_SETTLE_MS)

        val discovered = withTimeoutOrNull(opTimeoutMs * 3) {
            suspendCancellableCoroutine { cont ->
                pendingDiscover.set(cont)
                if (!g.discoverServices()) {
                    pendingDiscover.settle(BluetoothGatt.GATT_FAILURE)
                }
                cont.invokeOnCancellation { pendingDiscover.compareAndSet(cont, null) }
            }
        }
        if (discovered != BluetoothGatt.GATT_SUCCESS) {
            teardownGatt()
            throw TransportException("GATT service discovery failed on ${address.mac}")
        }

        val choice = selectEndpoints(g.services)
            ?: run {
                val seen = g.services.joinToString { it.uuid.toString() }
                teardownGatt()
                throw TransportException(
                    "No writable characteristic on ${address.mac}; services: $seen",
                )
            }
        writeCharacteristic = choice.write
        notifyCharacteristic = choice.notify
        Log.i(TAG, "BLE endpoint ${choice.service}/${choice.write.uuid} notify=${choice.notify?.uuid}")

        // Ask for a big MTU BEFORE the first payload write. onMtuChanged carries
        // the value that was actually negotiated -- never assume the request won.
        requestMtuBlocking(REQUESTED_MTU)

        // Short connection interval for the duration of the job.
        runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }

        choice.notify?.let { enableNotifications(g, it) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestMtuBlocking(target: Int) {
        val g = gatt ?: return
        val negotiated = withTimeoutOrNull(opTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                pendingMtu.set(cont)
                if (!g.requestMtu(target)) {
                    pendingMtu.settle(DEFAULT_ATT_MTU)
                }
                cont.invokeOnCancellation { pendingMtu.compareAndSet(cont, null) }
            }
        }
        attMtu = (negotiated ?: DEFAULT_ATT_MTU).coerceAtLeast(DEFAULT_ATT_MTU)
        Log.i(TAG, "ATT MTU=$attMtu -> ${chunkSize}B per write")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(ch, true)) return
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = value; g.writeDescriptor(cccd) }
        }
    }

    // --------------------------------------------------------------- write

    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val ch = writeCharacteristic ?: throw TransportException("BLE printer not open")
            val canWriteUnacked =
                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val canWriteAcked =
                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0

            var total = 0L
            var sinceAck = 0
            val size = chunkSize

            source.use { input ->
                // Read one chunk ahead so the LAST chunk can be sent acknowledged.
                // Ending on an unacknowledged write and then disconnecting drops
                // whatever is still in the controller queue and truncates the print.
                var pendingChunk = input.readChunk(size)
                while (pendingChunk != null) {
                    coroutineContext.ensureActive()
                    val next = input.readChunk(size)
                    val isLast = next == null

                    // Unacknowledged writes ride several per connection event and are
                    // several times faster, but give no end-to-end backpressure, so
                    // force a real ATT round trip every `ackEvery` chunks to let the
                    // printer's ring buffer drain.
                    val forceAck = canWriteAcked &&
                        (!canWriteUnacked || isLast || ++sinceAck >= ackEvery)
                    val type = if (forceAck) {
                        sinceAck = 0
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }

                    writeChunk(ch, pendingChunk, type)
                    total += pendingChunk.size
                    onProgress(total)
                    if (forceAck && !isLast && windowPauseMs > 0) delay(windowPauseMs)
                    pendingChunk = next
                }
            }

            // Let the printer's own buffer drain before the caller closes us.
            delay(DRAIN_MS)
            total
        }

    /** Fills up to [size] bytes, or returns null at end of stream. */
    private fun InputStream.readChunk(size: Int): ByteArray? {
        val buf = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val n = read(buf, filled, size - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled == 0) null else if (filled == size) buf else buf.copyOf(filled)
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
        type: Int,
    ) = opGate.withLock {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val status = withTimeoutOrNull(opTimeoutMs) {
                suspendCancellableCoroutine<Int> { cont ->
                    pendingWrite.set(cont)
                    val started = submit(ch, bytes, type)
                    if (started != BluetoothStatusCodes.SUCCESS) {
                        pendingWrite.settle(started)
                    }
                    cont.invokeOnCancellation { pendingWrite.compareAndSet(cont, null) }
                }
            } ?: throw TransportException("BLE write timed out on ${address.mac}")

            when {
                status == BluetoothGatt.GATT_SUCCESS -> return@withLock
                // Local controller queue full, or the framework's one-op-at-a-time
                // gate rejected us: back off and repeat the same chunk.
                status == BluetoothGatt.GATT_CONNECTION_CONGESTED ||
                    status == ERROR_WRITE_BUSY -> {
                    if (++attempt > CONGESTION_RETRIES) {
                        throw TransportException("BLE link congested on ${address.mac}")
                    }
                    delay(CONGESTION_BACKOFF_MS)
                }
                else -> throw TransportException("BLE write failed, GATT status $status")
            }
        }
    }

    /** Returns BluetoothStatusCodes.SUCCESS when the write was handed to the stack. */
    @SuppressLint("MissingPermission")
    private fun submit(ch: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Int {
        val g = gatt ?: return BluetoothGatt.GATT_FAILURE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = bytes
                if (g.writeCharacteristic(ch)) BluetoothStatusCodes.SUCCESS
                else ERROR_WRITE_BUSY
            }
        }
    }

    // ---------------------------------------------------------------- read

    override suspend fun readAvailable(timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = synchronized(inbox) { inbox.size() > 0 }
            if (ready) break
            delay(25)
        }
        return drainInbox()
    }

    private fun drainInbox(): ByteArray = synchronized(inbox) {
        val out = inbox.toByteArray()
        inbox.reset()
        out
    }

    // --------------------------------------------------------------- close

    override suspend fun finish() {
        // write() already forces the final chunk to WRITE_TYPE_DEFAULT and waits
        // for its callback, so this is a short belt-and-braces settle before the
        // GATT connection is torn down.
        delay(DRAIN_MS)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        }
        teardownGatt()
        writeCharacteristic = null
        notifyCharacteristic = null
        attMtu = DEFAULT_ATT_MTU
    }

    @SuppressLint("MissingPermission")
    private fun teardownGatt() {
        val g = gatt ?: return
        gatt = null
        // close() releases the client interface. Leaking them (disconnect only)
        // exhausts the ~32 registrations and every later connect returns 133.
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    // ------------------------------------------------------------ callback

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    pendingConnect.settle(status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    pendingConnect.settle(
                        if (status == BluetoothGatt.GATT_SUCCESS) BluetoothGatt.GATT_FAILURE
                        else status,
                    )
                    pendingDiscover.settle(BluetoothGatt.GATT_FAILURE)
                    pendingMtu.settle(DEFAULT_ATT_MTU)
                    pendingWrite.settle(BluetoothGatt.GATT_FAILURE)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingDiscover.settle(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendingMtu.settle(
                if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_ATT_MTU,
            )
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite.settle(status)
        }

        @Deprecated("Superseded by the byte[] overload on API 33+")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            characteristic.value?.let { collect(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = collect(value)

        private fun collect(value: ByteArray) {
            synchronized(inbox) { if (inbox.size() < INBOX_CAP) inbox.write(value) }
        }
    }

    // -------------------------------------------------- endpoint selection

    data class Endpoints(
        val service: UUID,
        val write: BluetoothGattCharacteristic,
        val notify: BluetoothGattCharacteristic?,
    )

    companion object {
        private const val TAG = "BleTransport"

        /** Bluetooth Core spec default; payload is MTU - 3 = 20 bytes. */
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_BYTES = 3

        /**
         * 512 is BluetoothGatt.GATT_MAX_ATTR_LEN: the framework throws
         * IllegalArgumentException above it. Requesting 517 would make
         * (mtu - 3) = 514 and blow up, so ask for 512 and cap at 509.
         */
        const val REQUESTED_MTU = 512
        const val MIN_CHUNK_BYTES = DEFAULT_ATT_MTU - ATT_HEADER_BYTES // 20

        private const val CONNECT_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 600L
        private const val SERVICE_SETTLE_MS = 300L
        private const val DRAIN_MS = 400L
        private const val CONGESTION_RETRIES = 40
        private const val CONGESTION_BACKOFF_MS = 40L
        private const val INBOX_CAP = 16 * 1024

        /** BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, inlined for API < 33. */
        private const val ERROR_WRITE_BUSY = 201

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun sig(short: String): UUID =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        /**
         * Known-good (service, write, notify) triples, most specific first.
         * This is an accelerator, not the mechanism: anything absent here still
         * resolves through the generic scoring pass below.
         */
        val KNOWN_ENDPOINTS: List<Triple<UUID, UUID, UUID?>> = listOf(
            // Zjiang / Goojprt / MTP / PT-210 class 58 mm printers.
            Triple(sig("ff00"), sig("ff02"), sig("ff01")),
            // "Printer service" seen on Cashino, Xprinter, some Rongta units.
            Triple(sig("18f0"), sig("2af1"), sig("2af0")),
            // HM-10 / JDY / AT-09 (TI CC254x) transparent serial modules.
            Triple(sig("fff0"), sig("fff2"), sig("fff1")),
            // Microchip / ISSC BM77-BM78 transparent UART (Bixolon, Star, many OEMs).
            Triple(
                UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
                UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
                UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"),
            ),
            // Same ISSC service, acknowledged-write characteristic.
            Triple(
                UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
                UUID.fromString("49535343-aca3-481c-91ec-d85e28a60318"),
                UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"),
            ),
            // Nordic UART Service: RX is phone -> device.
            Triple(
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            ),
            // Assorted Chinese label printers.
            Triple(sig("ae30"), sig("ae01"), sig("ae02")),
            // Zebra ZQ/ZD BLE ("Zebra Print Service").
            Triple(
                UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b"),
                UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b"),
                UUID.fromString("38eb4a81-c570-11e3-9507-0002a5d5c51b"),
            ),
        )

        /** Services that are never the data pipe. */
        private val IGNORED_SERVICES: Set<UUID> = setOf(
            sig("1800"), // Generic Access
            sig("1801"), // Generic Attribute
            sig("1802"), // Immediate Alert
            sig("1803"), // Link Loss
            sig("1804"), // Tx Power
            sig("1805"), // Current Time
            sig("180a"), // Device Information
            sig("180f"), // Battery
            sig("fe59"), // Nordic DFU
        )

        /**
         * Picks the byte pipe without depending on a single vendor.
         *
         * 1. Exact match against [KNOWN_ENDPOINTS].
         * 2. Otherwise score every writable characteristic outside the standard
         *    services and take the best. The shape being looked for is a serial
         *    bridge: a vendor service holding one writable and one notifying
         *    characteristic.
         */
        fun selectEndpoints(services: List<BluetoothGattService>): Endpoints? {
            for ((svc, wr, nt) in KNOWN_ENDPOINTS) {
                val service = services.firstOrNull { it.uuid == svc } ?: continue
                val write = service.getCharacteristic(wr) ?: continue
                if (!write.isWritable()) continue
                val notify = nt?.let { service.getCharacteristic(it) }
                    ?: service.characteristics.firstOrNull { it.isNotifying() }
                return Endpoints(service.uuid, write, notify)
            }

            var best: Endpoints? = null
            var bestScore = Int.MIN_VALUE
            for (service in services) {
                if (service.uuid in IGNORED_SERVICES) continue
                val notify = service.characteristics.firstOrNull { it.isNotifying() }
                for (ch in service.characteristics) {
                    if (!ch.isWritable()) continue
                    var score = 0
                    // Unacknowledged writes are what a print pipe wants.
                    if (ch.properties and
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                    ) score += 40
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) score += 10
                    // A sibling notify characteristic means "serial bridge".
                    if (notify != null) score += 30
                    // Vendor UUIDs beat SIG-assigned ones.
                    if (!service.uuid.isSigAssigned()) score += 15
                    else if (service.uuid.shortId() in 0xff00..0xffff) score += 12
                    // A characteristic that is also readable is usually config, not data.
                    if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) score -= 5
                    if (score > bestScore) {
                        bestScore = score
                        best = Endpoints(service.uuid, ch, notify)
                    }
                }
            }
            return best
        }

        private fun BluetoothGattCharacteristic.isWritable(): Boolean =
            properties and (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) != 0

        private fun BluetoothGattCharacteristic.isNotifying(): Boolean =
            properties and (
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                ) != 0

        private fun UUID.isSigAssigned(): Boolean =
            leastSignificantBits == -0x7fffff7fa064cb05L &&
                (mostSignificantBits and 0x00000000ffffffffL) == 0x1000L

        private fun UUID.shortId(): Int = (mostSignificantBits ushr 32).toInt() and 0xffff

        // ----------------------------------------------------- link probing

        /** What kind of radio the saved MAC actually has. */
        @SuppressLint("MissingPermission")
        fun probeLinkType(context: Context, mac: String): Int {
            if (!BluetoothTransport.hasConnectPermission(context)) {
                return BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return BluetoothDevice.DEVICE_TYPE_UNKNOWN
            // getType() returns DEVICE_TYPE_UNKNOWN when the adapter is off or
            // the stack has never seen the address.
            if (!adapter.isEnabled) return BluetoothDevice.DEVICE_TYPE_UNKNOWN
            return runCatching { adapter.getRemoteDevice(mac).type }
                .getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        }

        /** True when RFCOMM cannot possibly work: the device has no BR/EDR radio. */
        fun isBleOnly(context: Context, mac: String): Boolean =
            probeLinkType(context, mac) == BluetoothDevice.DEVICE_TYPE_LE

        /** True when the SDP cache proves an SPP server exists. */
        @SuppressLint("MissingPermission")
        fun advertisesSpp(context: Context, mac: String): Boolean {
            if (!BluetoothTransport.hasConnectPermission(context)) return false
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val uuids = runCatching { adapter.getRemoteDevice(mac).uuids }.getOrNull()
                ?: return false
            return uuids.any { it.uuid == BluetoothTransport.SPP_UUID }
        }
    }
}
