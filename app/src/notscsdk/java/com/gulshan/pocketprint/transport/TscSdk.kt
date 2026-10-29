package com.gulshan.pocketprint.transport

/**
 * The stand-in for a build without `app/libs/tscsdk.jar`: nothing offers the
 * TSC library, and a printer that was set up to use it says why it cannot.
 *
 * Its twin under `src/tscsdk` is the real one; see docs/TSC_SDK.md.
 */
object TscSdk {

    const val isBundled = false

    fun library(): TscLibrary = throw TransportException(
        "This build of PocketPrint does not include TSC's library, so a printer set to " +
            "connect through it cannot be reached. Open this printer's settings and turn off " +
            "\"Connect through TSC's library\", or install a build that includes it " +
            "(docs/TSC_SDK.md).",
    )
}
