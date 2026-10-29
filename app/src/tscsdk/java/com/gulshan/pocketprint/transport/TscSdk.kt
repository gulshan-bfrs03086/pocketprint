package com.gulshan.pocketprint.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.example.tscdll.TSCActivity
import java.io.IOException

/**
 * The real TSC library, in a build that was given `app/libs/tscsdk.jar`.
 *
 * Which of this file and its twin under `src/notscsdk` is compiled is decided
 * in app/build.gradle.kts by whether the jar is there. The jar is TSC's, ships
 * with no licence terms, and is not in this repository - see docs/TSC_SDK.md.
 */
object TscSdk {

    const val isBundled = true

    /**
     * A new library object. `TSCActivity` is an Activity, which builds a Handler
     * when it is constructed, so this must be called on the main thread;
     * [TscSdkTransport.open] does.
     */
    fun library(): TscLibrary = Library(TSCActivity())

    /**
     * The library's private input stream, which it reads from but never clears.
     * Reached by reflection, because there is no other way to throw away a reply
     * that arrived late; if the field is not there (a different version of the
     * jar) the drain quietly does nothing and the old behaviour stands.
     * proguard-rules.pro keeps the field's name through R8.
     */
    private val inputStreamField: java.lang.reflect.Field? = runCatching {
        TSCActivity::class.java.getDeclaredField("InStream").apply { isAccessible = true }
    }.getOrNull()

    /** Where a socket PocketPrint connected goes in the library; null for a jar laid out differently. */
    private val handover: TscPortHandover? = TscPortHandover.of(TSCActivity::class.java)

    private class Library(private val tsc: TSCActivity) : TscLibrary {

        /**
         * The socket [openportWithoutDiscoveryCancel] made, kept so that [closeport]
         * can close it even while it is still connecting: the library's own closeport
         * closes only a socket that has finished connecting.
         */
        @Volatile private var ownSocket: BluetoothSocket? = null

        override fun openport(mac: String): String = tsc.openport(mac)

        /**
         * What `TSCActivity.openport(mac)` does, read from its bytecode, minus its
         * `cancelDiscovery()`: Bluetooth on, the device by address, a secure RFCOMM
         * socket to the serial-port UUID, connect, both streams, then the same 100 ms
         * pause. Only BLUETOOTH_CONNECT is needed for this; a SecurityException for
         * its absence is left to the transport, which explains it.
         */
        @SuppressLint("MissingPermission")
        override fun openportWithoutDiscoveryCancel(mac: String): String? {
            val handover = handover ?: return null
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return FAILED
            if (!adapter.isEnabled) return FAILED
            val socket = try {
                adapter.getRemoteDevice(mac).createRfcommSocketToServiceRecord(BluetoothTransport.SPP_UUID)
            } catch (e: IOException) {
                return FAILED
            } catch (e: IllegalArgumentException) {
                return FAILED // not a Bluetooth address
            }
            ownSocket = socket
            try {
                socket.connect()
                handover.install(tsc, socket, socket.outputStream, socket.inputStream)
            } catch (e: IOException) {
                runCatching { socket.close() }
                return FAILED
            }
            runCatching { Thread.sleep(100) }
            return OK
        }

        override fun sendcommand(bytes: ByteArray): String = tsc.sendcommand(bytes)
        override fun status(waitMs: Int): String = tsc.status(waitMs)
        override fun printername(waitMs: Int): String = tsc.printername(waitMs)

        override fun closeport(delayMs: Int): String {
            // The library's closeport dereferences its socket, which is null if no
            // connect ever got that far; that is "nothing to close", not a crash.
            val result = runCatching { tsc.closeport(delayMs) }.getOrDefault(FAILED)
            ownSocket?.let { runCatching { it.close() } }
            ownSocket = null
            return result
        }

        override fun discardUnread() {
            val stream = runCatching { inputStreamField?.get(tsc) as? java.io.InputStream }.getOrNull()
                ?: return
            runCatching {
                val scratch = ByteArray(1024)
                while (stream.available() > 0 && stream.read(scratch) > 0) { /* thrown away */ }
            }
        }
    }

    private const val OK = "1"
    private const val FAILED = "-1"
}
