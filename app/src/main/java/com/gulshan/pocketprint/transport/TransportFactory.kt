package com.gulshan.pocketprint.transport

import android.content.Context
import com.gulshan.pocketprint.model.BtLink
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress

/**
 * Maps a printer's address onto the transport that can reach it. IPP printers
 * are absent here because IPP carries its own HTTP framing and is driven by
 * IppClient rather than by a raw byte pipe.
 */
object TransportFactory {

    fun requiresIpp(printer: Printer): Boolean = printer.address is PrinterAddress.Ipp

    fun create(context: Context, printer: Printer): PrinterTransport =
        when (val a = printer.address) {
            is PrinterAddress.Raw -> RawSocketTransport(a, context = context.applicationContext)
            // A Bluetooth printer is reached over RFCOMM or over GATT depending
            // on its radio; AUTO probes and falls back so an LE-only printer no
            // longer dies on an RFCOMM socket timeout.
            is PrinterAddress.Bluetooth -> when (a.link) {
                BtLink.CLASSIC -> BluetoothTransport(context.applicationContext, a)
                BtLink.BLE -> BleTransport(context.applicationContext, a)
                BtLink.AUTO -> AutoBluetoothTransport(context.applicationContext, a)
            }
            is PrinterAddress.Usb -> UsbTransport(context.applicationContext, a)
            is PrinterAddress.Ipp -> throw TransportException(
                "IPP printers are driven through IppClient, not a byte transport",
            )
        }
}
