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
 * The wake activity is called by name from another app, so the ways to break
 * it are all quiet ones: a renamed class or an unexported entry and the caller's
 * start fails into a catch, a lost NoDisplay theme and every print flashes a
 * blank window, a rebind added to it and the printer leaves the dialog the
 * caller is about to open. These read the real files, as AddPrintersEntryTest
 * does, because a copy is what would drift.
 */
class WakeActivityTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun root(path: String): Element =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun entry(): Element {
        val activities = root("src/main/AndroidManifest.xml").getElementsByTagName("activity")
        val shortName = "." + WakeActivity.CLASS_NAME.substringAfterLast('.')
        val match = (0 until activities.length).map { activities.item(it) as Element }
            .firstOrNull { it.attr("name") == shortName }
        assertNotNull("no <activity> named $shortName in the manifest", match)
        return match!!
    }

    @Test
    fun `the published name is the class's real name`() {
        // Callers hard-code this string; it has to name the class that exists.
        assertEquals(WakeActivity::class.java.name, WakeActivity.CLASS_NAME)
    }

    @Test
    fun `it is exported, so another app may start it, and has no intent filter`() {
        val wake = entry()

        assertEquals("true", wake.attr("exported"))
        assertEquals(0, wake.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `it never shows - no display, not in recents, no history`() {
        val wake = entry()

        assertEquals("@android:style/Theme.NoDisplay", wake.attr("theme"))
        assertEquals("true", wake.attr("excludeFromRecents"))
        assertEquals("true", wake.attr("noHistory"))
        // Its own empty affinity, so starting it never brings PocketPrint's
        // main task forward over the caller.
        // getAttributeNS answers "" for a missing attribute too, hence both.
        assertTrue(wake.hasAttributeNS(android, "taskAffinity"))
        assertEquals("", wake.attr("taskAffinity"))
        assertFalse(wake.hasAttributeNS(android, "launchMode"))
    }

    @Test
    fun `it does not start the rebind a person's launch does`() {
        // That rebind disables the print service for twenty seconds, which
        // would take the printer out of the caller's dialog.
        val source = File("src/main/java/com/gulshan/pocketprint/WakeActivity.kt").readText()
            .lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/") }
            .joinToString("\n")

        assertFalse(source.contains("PrintServiceRebind"))
        assertFalse(source.contains("StopCanary"))
    }
}
