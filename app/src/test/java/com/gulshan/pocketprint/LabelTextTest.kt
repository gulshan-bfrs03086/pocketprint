package com.gulshan.pocketprint

import com.gulshan.pocketprint.label.LabelText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which route a label's text takes. Getting this wrong in one direction costs a
 * couple of seconds over Bluetooth; getting it wrong in the other prints a row
 * of question marks, which is what this app did for every non-Latin script.
 */
class LabelTextTest {

    private fun carried(vararg lines: String) = LabelText.printerFontsCanCarry(lines.toList())

    @Test
    fun `ASCII goes out as printer-font text`() {
        assertTrue(carried("SHIPPING LABEL", "Order 12345", "Bengaluru 560001"))
    }

    @Test
    fun `the accented characters ISO-8859-1 does cover stay on the fast path`() {
        assertTrue(carried("Café Müller", "Señor Núñez", "£45 · 20°C"))
    }

    @Test
    fun `the scripts that used to print as question marks are rendered instead`() {
        assertFalse("Devanagari", carried("नमस्ते"))
        assertFalse("Arabic", carried("مرحبا"))
        assertFalse("Thai", carried("สวัสดี"))
        assertFalse("Han", carried("你好"))
        assertFalse("Hangul", carried("안녕하세요"))
        assertFalse("Cyrillic", carried("Здравствуйте"))
    }

    @Test
    fun `common punctuation that is not Latin-1 is caught too`() {
        // The quiet ones. A euro sign or a smart quote pasted in from anywhere
        // else is outside ISO-8859-1 and printed as a question mark.
        assertFalse("euro sign", carried("Total: €45"))
        assertFalse("curly apostrophe", carried("Driver’s copy"))
        assertFalse("em dash", carried("Bengaluru — Chennai"))
        assertFalse("emoji", carried("Fragile 📦"))
    }

    @Test
    fun `one line the printer cannot carry decides the whole label`() {
        // Mixing a printer-resident font with a rendered one on the same label
        // reads as a mistake rather than as a fallback.
        assertFalse(carried("SHIPPING LABEL", "नमस्ते", "Order 12345"))
    }

    @Test
    fun `nothing to print is not a reason to rasterise`() {
        assertTrue(carried())
        assertTrue(carried("", "   "))
    }
}
