package com.gulshan.pocketprint

import android.app.Activity
import android.os.Bundle

/**
 * Lets an app that prints through PocketPrint take it out of Android's stopped
 * state before it opens the print dialog.
 *
 * A force-stop - from App info, from `am force-stop`, or from the background
 * cleaner on a zltd N7L, which did it unprompted with the Darkstore app in
 * front - leaves the package stopped until something starts one of its
 * components by name. While it is stopped, that ROM holds the print
 * framework's bind to PocketPrintService without starting a process, so every
 * print dialog searches and never lists a PocketPrint printer. Opening the app
 * clears it, which is what a person had to be told to do.
 *
 * Starting an activity by its exact name is the one way another app can clear
 * the stopped state on Android 9: broadcasts to a stopped package are dropped
 * and a service bind is exactly what is being held. So this is an activity
 * that draws nothing and finishes in onCreate. The caller starts it, gives the
 * process a moment, and then prints; the dialog's bind then reaches a package
 * that is no longer stopped and connects as it would on any other day.
 *
 * Deliberately not [com.gulshan.pocketprint.printservice.PrintServiceRebind]:
 * that is for a person opening the app, and it disables the print service for
 * twenty seconds when it decides to rebind - which, here, would take the
 * printer out of the very dialog the caller is about to open. Nor does it
 * schedule the StopCanary, so the next time a person opens the app it still
 * knows a stop happened and can repair a binding that a stop left dead.
 *
 * Exported with no intent filter, like AddPrintersEntry: reachable by this
 * name only. It reads nothing from the intent and does nothing but exist for a
 * moment, so there is nothing for an arbitrary caller to misuse.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.NoDisplay requires finishing before onResume.
        finish()
    }

    companion object {
        const val CLASS_NAME = "com.gulshan.pocketprint.WakeActivity"
    }
}
