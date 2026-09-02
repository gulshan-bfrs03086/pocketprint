package com.gulshan.pocketprint.transport

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.PrinterAddress
import java.io.InputStream

/**
 * Picks the Bluetooth radio for a printer whose link type we do not know yet.
 *
 * A saved MAC says nothing about which radio answers: LE-only printers have no
 * RFCOMM server at all, so [BluetoothTransport] hangs and finally fails with
 * "read failed, socket might closed or timeout" -- the socket timeout users
 * report. BluetoothDevice.getType() usually settles it, but it returns
 * DEVICE_TYPE_UNKNOWN whenever the stack has not seen the address, so a failed
 * attempt on the preferred radio falls through to the other one.
 *
 * [resolved] carries the answer back so the caller can persist it and skip the
 * probe next time.
 */
class AutoBluetoothTransport(
    private val context: Context,
    private val address: PrinterAddress.Bluetooth,
) : PrinterTransport {

    private var delegate: PrinterTransport? = null

    /** The link that actually worked; null until [open] succeeds. */
    var resolved: BtLink? = null
        private set

    override val description: String
        get() = delegate?.description ?: "bt-auto://${address.mac}"

    override suspend fun open() {
        val order = preferenceOrder()
        var lastFailure: Throwable? = null

        for (link in order) {
            val candidate = when (link) {
                BtLink.BLE -> BleTransport(context, address)
                else -> BluetoothTransport(context, address)
            }
            try {
                candidate.open()
                delegate = candidate
                resolved = link
                Log.i(TAG, "${address.mac} reached over $link")
                return
            } catch (t: Throwable) {
                runCatching { candidate.close() }
                Log.w(TAG, "${address.mac} not reachable over $link: ${t.message}")
                lastFailure = t
            }
        }
        throw TransportException(
            "Could not reach ${address.mac} over ${order.joinToString("/")}",
            lastFailure,
        )
    }

    /**
     * Classic first when there is real evidence of it (an SPP record, or the
     * adapter reporting BR/EDR); LE first otherwise, because an LE attempt on a
     * Classic device fails in about a second while the reverse burns the full
     * RFCOMM page timeout.
     */
    private fun preferenceOrder(): List<BtLink> = when (address.link) {
        BtLink.BLE -> listOf(BtLink.BLE, BtLink.CLASSIC)
        BtLink.CLASSIC -> listOf(BtLink.CLASSIC, BtLink.BLE)
        BtLink.AUTO -> when (BleTransport.probeLinkType(context, address.mac)) {
            BluetoothDevice.DEVICE_TYPE_LE -> listOf(BtLink.BLE)
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf(BtLink.CLASSIC)
            BluetoothDevice.DEVICE_TYPE_DUAL ->
                if (BleTransport.advertisesSpp(context, address.mac)) {
                    listOf(BtLink.CLASSIC, BtLink.BLE)
                } else {
                    listOf(BtLink.BLE, BtLink.CLASSIC)
                }
            // DEVICE_TYPE_UNKNOWN: the stack has never seen it. SPP in the SDP
            // cache is the only positive evidence available.
            else -> if (BleTransport.advertisesSpp(context, address.mac)) {
                listOf(BtLink.CLASSIC, BtLink.BLE)
            } else {
                listOf(BtLink.BLE, BtLink.CLASSIC)
            }
        }
    }

    override suspend fun write(source: InputStream, onProgress: (Long) -> Unit): Long =
        (delegate ?: throw TransportException("Bluetooth printer not open"))
            .write(source, onProgress)

    override suspend fun readAvailable(timeoutMs: Long): ByteArray =
        delegate?.readAvailable(timeoutMs) ?: ByteArray(0)

    override suspend fun finish() {
        // Without this the drain silently falls through to the interface's
        // default, and the delegate's socket is closed with bytes still queued.
        delegate?.finish()
    }

    override fun close() {
        runCatching { delegate?.close() }
        delegate = null
    }

    private companion object {
        const val TAG = "AutoBtTransport"
    }
}
