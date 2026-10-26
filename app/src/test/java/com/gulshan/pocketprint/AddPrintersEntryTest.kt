package com.gulshan.pocketprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Add printer using PocketPrint" is an entry the system draws and the app only
 * answers, so the one way to break it is for the names to stop agreeing - and
 * when they do, nothing fails: the entry still opens the app, on whatever
 * screen it was last left, which is exactly what it did before it existed.
 *
 * Three places hold the name, and these hold them together. They read the real
 * files rather than a copy of them, because a copy is what would drift.
 */
class AddPrintersEntryTest {

    private val android = "http://schemas.android.com/apk/res/android"

    /** Gradle runs unit tests from the module directory. */
    private fun root(path: String): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    @Test
    fun `a launch is recognised by the name it was started under, and by no other`() {
        assertTrue(AddPrintersEntry.isLaunchedVia("com.gulshan.pocketprint.AddPrintersEntry"))

        // The launcher, the share sheet and a bare start must not look like it.
        assertFalse(AddPrintersEntry.isLaunchedVia("com.gulshan.pocketprint.MainActivity"))
        assertFalse(AddPrintersEntry.isLaunchedVia("com.gulshan.pocketprint.ShareTargetActivity"))
        assertFalse(AddPrintersEntry.isLaunchedVia(null))
    }

    @Test
    fun `the print service tells the system to open the entry, not the launcher activity`() {
        val service = root("src/main/res/xml/print_service.xml")

        assertEquals(AddPrintersEntry.CLASS_NAME, service.attr("addPrintersActivity"))
    }

    @Test
    fun `the manifest declares that name as an exported alias of the main activity`() {
        val aliases = root("src/main/AndroidManifest.xml").children("activity-alias")
        // Manifest names starting with a dot are relative to the app's namespace.
        val shortName = "." + AddPrintersEntry.CLASS_NAME.substringAfterLast('.')

        val entry = aliases.firstOrNull { it.attr("name") == shortName }
        assertNotNull("no <activity-alias> named $shortName in the manifest", entry)

        // The print dialog and Settings are other apps; an alias that is not
        // exported is refused to them, and the entry silently does nothing.
        assertEquals("true", entry!!.attr("exported"))
        assertEquals(".MainActivity", entry.attr("targetActivity"))
    }

    @Test
    fun `the alias has no intent filter, so nothing reaches it except by its exact name`() {
        val manifest = root("src/main/AndroidManifest.xml")
        val shortName = "." + AddPrintersEntry.CLASS_NAME.substringAfterLast('.')
        val entry = manifest.children("activity-alias").first { it.attr("name") == shortName }

        assertEquals(0, entry.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `the settings page still opens the app as it always did`() {
        // Only "add" gets its own name. Settings has nothing to say about adding
        // a printer, and must not start routing there.
        val service = root("src/main/res/xml/print_service.xml")

        assertEquals("com.gulshan.pocketprint.MainActivity", service.attr("settingsActivity"))
    }
}
