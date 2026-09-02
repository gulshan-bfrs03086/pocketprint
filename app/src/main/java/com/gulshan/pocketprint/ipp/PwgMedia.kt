package com.gulshan.pocketprint.ipp

import com.gulshan.pocketprint.model.MediaSize

/**
 * PWG 5101.1 self-describing media names look like:
 *   iso_a4_210x297mm      na_letter_8.5x11in      om_label_100x150mm
 * The trailing token carries the dimensions and the unit, so the physical size
 * can be recovered without a lookup table.
 */
object PwgMedia {

    private val KNOWN_LABELS = mapOf(
        "iso_a4_210x297mm" to "A4",
        "iso_a5_148x210mm" to "A5",
        "iso_a3_297x420mm" to "A3",
        "iso_a6_105x148mm" to "A6",
        "na_letter_8.5x11in" to "Letter",
        "na_legal_8.5x14in" to "Legal",
        "na_executive_7.25x10.5in" to "Executive",
        "na_index-4x6_4x6in" to "4 x 6 photo",
        "na_index-5x7_5x7in" to "5 x 7 photo",
        "jis_b5_182x257mm" to "B5",
    )

    fun parse(name: String): MediaSize? {
        val dims = name.substringAfterLast('_', "")
        if (dims.isEmpty()) return null

        val unitMicronsPerUnit = when {
            dims.endsWith("mm") -> 1_000.0
            dims.endsWith("in") -> 25_400.0
            else -> return null
        }
        val numbers = dims.dropLast(2).split('x')
        if (numbers.size != 2) return null

        val w = numbers[0].toDoubleOrNull() ?: return null
        val h = numbers[1].toDoubleOrNull() ?: return null

        return MediaSize(
            id = name,
            label = KNOWN_LABELS[name] ?: prettify(name, w, h, dims.takeLast(2)),
            widthMicrons = Math.round(w * unitMicronsPerUnit).toInt(),
            heightMicrons = Math.round(h * unitMicronsPerUnit).toInt(),
        )
    }

    private fun prettify(name: String, w: Double, h: Double, unit: String): String {
        val base = name.split('_').getOrNull(1)?.replace('-', ' ') ?: name
        val fmt = { d: Double -> if (d == Math.floor(d)) d.toInt().toString() else d.toString() }
        return "${base.replaceFirstChar { it.uppercase() }} (${fmt(w)} x ${fmt(h)} $unit)"
    }

    /** Best-effort reverse lookup so a chosen size can be named back to the printer. */
    fun nameFor(size: MediaSize, supported: List<String>): String? {
        supported.firstOrNull { it == size.id }?.let { return it }
        val tolerance = 2_000 // 2 mm
        return supported.firstOrNull { candidate ->
            val parsed = parse(candidate) ?: return@firstOrNull false
            Math.abs(parsed.widthMicrons - size.widthMicrons) < tolerance &&
                Math.abs(parsed.heightMicrons - size.heightMicrons) < tolerance
        }
    }
}
