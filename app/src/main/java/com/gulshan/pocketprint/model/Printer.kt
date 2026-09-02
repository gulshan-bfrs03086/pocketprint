package com.gulshan.pocketprint.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How we physically reach a printer. */
enum class ConnectionKind { IPP, RAW9100, BLUETOOTH, USB }

/**
 * Which Bluetooth radio carries the payload. Boolean was not enough: a saved
 * MAC is often of *unknown* type until the adapter has seen the device, and
 * dual-mode printers work either way.
 */
enum class BtLink {
    /** Probe BluetoothDevice.getType(), then fall back to the other radio. */
    AUTO,

    /** RFCOMM / SPP. */
    CLASSIC,

    /** GATT over LE. */
    BLE,
}

/**
 * The page description language we hand the printer. Network printers almost
 * always take PDF or PWG raster; thermal units take their own command dialect.
 */
enum class PrintLanguage(val mimeType: String) {
    PDF("application/pdf"),
    PWG_RASTER("image/pwg-raster"),
    POSTSCRIPT("application/postscript"),
    PCL("application/vnd.hp-PCL"),
    JPEG("image/jpeg"),
    ESC_POS("application/octet-stream"),
    TSPL("application/octet-stream"),
    ZPL("application/octet-stream"),
    PLAIN_TEXT("text/plain");

    /** Raster dialects are driven by bitmaps, not by a page-layout document. */
    val isRaster: Boolean
        get() = this == PWG_RASTER || this == ESC_POS || this == TSPL || this == ZPL
}

@Serializable
sealed interface PrinterAddress {
    val kind: ConnectionKind

    @Serializable
    @SerialName("ipp")
    data class Ipp(
        val host: String,
        val port: Int = 631,
        val path: String = "/ipp/print",
        val secure: Boolean = false,
    ) : PrinterAddress {
        override val kind get() = ConnectionKind.IPP
        val uri: String get() = "${if (secure) "ipps" else "ipp"}://$host:$port$path"
        val httpUrl: String get() = "${if (secure) "https" else "http"}://$host:$port$path"
    }

    @Serializable
    @SerialName("raw")
    data class Raw(val host: String, val port: Int = 9100) : PrinterAddress {
        override val kind get() = ConnectionKind.RAW9100
    }

    @Serializable
    @SerialName("bluetooth")
    data class Bluetooth(
        val mac: String,
        /**
         * Kept for the printers already persisted with this field. New code
         * reads [link]; a stored `useBle = true` migrates to [BtLink.BLE].
         */
        val useBle: Boolean = false,
        /**
         * Which radio to reach the printer on. Defaults to AUTO so an existing
         * saved printer is probed rather than assumed to be Classic: LE-only
         * units are exactly the ones that fail RFCOMM with a socket timeout.
         */
        val link: BtLink = if (useBle) BtLink.BLE else BtLink.AUTO,
    ) : PrinterAddress {
        override val kind get() = ConnectionKind.BLUETOOTH
    }

    @Serializable
    @SerialName("usb")
    data class Usb(val vendorId: Int, val productId: Int, val serial: String? = null) : PrinterAddress {
        override val kind get() = ConnectionKind.USB
    }
}

@Serializable
data class MediaSize(
    val id: String,
    val label: String,
    val widthMicrons: Int,
    val heightMicrons: Int,
) {
    val widthMm get() = widthMicrons / 1000f
    val heightMm get() = heightMicrons / 1000f
    val widthPoints get() = widthMicrons * 72f / 25400f
    val heightPoints get() = heightMicrons * 72f / 25400f

    fun dotsWide(dpi: Int) = Math.round(widthMicrons / 25400f * dpi)
    fun dotsHigh(dpi: Int) = Math.round(heightMicrons / 25400f * dpi)

    companion object {
        val A4 = MediaSize("iso_a4_210x297mm", "A4", 210_000, 297_000)
        val A5 = MediaSize("iso_a5_148x210mm", "A5", 148_000, 210_000)
        val LETTER = MediaSize("na_letter_8.5x11in", "Letter", 215_900, 279_400)
        val LEGAL = MediaSize("na_legal_8.5x14in", "Legal", 215_900, 355_600)
        val PHOTO_4X6 = MediaSize("na_index-4x6_4x6in", "4 x 6 photo", 101_600, 152_400)
        /**
         * A "4 x 6" shipping label is 4 x 6 INCHES: 101.6 x 152.4 mm. Sending
         * 100 x 150 mm instead makes a TSPL printer look for the inter-label gap
         * 2.4 mm early, miss it, keep feeding, and stop with a media fault.
         */
        val LABEL_4X6 = MediaSize("om_label-4x6_101.6x152.4mm", "4 x 6 in label", 101_600, 152_400)

        /** The metric near-equivalent, which is a genuinely different stock. */
        val LABEL_100X150 = MediaSize("om_label_100x150mm", "Label 100 x 150 mm", 100_000, 150_000)
        val LABEL_100X50 = MediaSize("om_label-100x50_100x50mm", "Label 100 x 50 mm", 100_000, 50_000)
        val RECEIPT_80 = MediaSize("om_receipt-80_80x297mm", "Receipt 80 mm", 80_000, 297_000)
        val RECEIPT_58 = MediaSize("om_receipt-58_58x297mm", "Receipt 58 mm", 58_000, 297_000)

        val PAPER = listOf(A4, LETTER, LEGAL, A5, PHOTO_4X6)
        val LABELS = listOf(LABEL_4X6, LABEL_100X150, LABEL_100X50, RECEIPT_80, RECEIPT_58)
        val ALL = PAPER + LABELS

        fun byId(id: String?): MediaSize? = ALL.firstOrNull { it.id == id }
    }
}

enum class ColorMode { MONOCHROME, COLOR }
enum class DuplexMode { SIMPLEX, LONG_EDGE, SHORT_EDGE }
enum class Orientation { PORTRAIT, LANDSCAPE }

@Serializable
data class PrinterCapabilities(
    val languages: List<PrintLanguage> = listOf(PrintLanguage.PDF),
    val mediaSizes: List<MediaSize> = listOf(MediaSize.A4),
    val resolutionsDpi: List<Int> = listOf(300),
    val supportsColor: Boolean = false,
    val supportsDuplex: Boolean = false,
    val maxCopies: Int = 99,
    /** Printable dot width for roll/thermal devices; null for sheet printers. */
    val rasterWidthDots: Int? = null,
) {
    fun preferredLanguage(): PrintLanguage = languages.firstOrNull() ?: PrintLanguage.PDF

    companion object {
        val UNKNOWN_NETWORK = PrinterCapabilities()

        /** Sensible default for an unqueried 80 mm ESC/POS receipt printer. */
        val ESC_POS_80MM = PrinterCapabilities(
            languages = listOf(PrintLanguage.ESC_POS),
            mediaSizes = listOf(MediaSize.RECEIPT_80, MediaSize.RECEIPT_58),
            resolutionsDpi = listOf(203),
            rasterWidthDots = 576,
        )

        val TSPL_LABEL = PrinterCapabilities(
            languages = listOf(PrintLanguage.TSPL),
            mediaSizes = listOf(MediaSize.LABEL_4X6, MediaSize.LABEL_100X150, MediaSize.LABEL_100X50),
            resolutionsDpi = listOf(203),
            rasterWidthDots = 812,
        )

        val ZPL_LABEL = PrinterCapabilities(
            languages = listOf(PrintLanguage.ZPL),
            mediaSizes = listOf(MediaSize.LABEL_4X6, MediaSize.LABEL_100X150, MediaSize.LABEL_100X50),
            resolutionsDpi = listOf(203),
            rasterWidthDots = 812,
        )
    }
}

enum class PrinterStatus { IDLE, BUSY, OFFLINE, ERROR, UNKNOWN }

@Serializable
data class Printer(
    val id: String,
    val displayName: String,
    val address: PrinterAddress,
    val makeAndModel: String? = null,
    val location: String? = null,
    val capabilities: PrinterCapabilities = PrinterCapabilities.UNKNOWN_NETWORK,
    /** True once the user has explicitly saved it, as opposed to a live discovery hit. */
    val saved: Boolean = false,
    /** Surface this printer through Android's system print dialog. */
    val exposeToSystem: Boolean = true,
    val lastSeenEpochMs: Long = 0L,
) {
    val kind: ConnectionKind get() = address.kind

    val subtitle: String
        get() = when (val a = address) {
            is PrinterAddress.Ipp -> "${a.host}:${a.port}"
            is PrinterAddress.Raw -> "${a.host}:${a.port} (raw)"
            is PrinterAddress.Bluetooth -> when (a.link) {
                BtLink.BLE -> "Bluetooth LE ${a.mac}"
                BtLink.CLASSIC -> "Bluetooth ${a.mac}"
                BtLink.AUTO -> "Bluetooth ${a.mac}"
            }
            is PrinterAddress.Usb -> "USB %04x:%04x".format(a.vendorId, a.productId)
        }
}
