package com.gulshan.pocketprint.transport

import com.example.tscdll.TSCActivity

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

    private class Library(private val tsc: TSCActivity) : TscLibrary {
        override fun openport(mac: String): String = tsc.openport(mac)
        override fun sendcommand(bytes: ByteArray): String = tsc.sendcommand(bytes)
        override fun status(waitMs: Int): String = tsc.status(waitMs)
        override fun printername(waitMs: Int): String = tsc.printername(waitMs)
        override fun closeport(delayMs: Int): String = tsc.closeport(delayMs)

        override fun discardUnread() {
            val stream = runCatching { inputStreamField?.get(tsc) as? java.io.InputStream }.getOrNull()
                ?: return
            runCatching {
                val scratch = ByteArray(1024)
                while (stream.available() > 0 && stream.read(scratch) > 0) { /* thrown away */ }
            }
        }
    }
}
