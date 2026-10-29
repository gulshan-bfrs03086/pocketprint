package com.gulshan.pocketprint

/**
 * The name the system's "Add printer using PocketPrint" entry launches us by.
 *
 * Android's print dialog and Settings > Printing both list that entry for a
 * print service, and start the activity named by `addPrintersActivity` in
 * print_service.xml - with no extras and no action, so nothing in the intent
 * says why the app was opened. That attribute used to name MainActivity, which
 * is also the launcher and the service's settings page, so a person who tapped
 * "Add printer" arrived wherever the app happened to open and had to work out
 * for themselves that adding one was on the Print tab.
 *
 * The manifest therefore declares a second name for the same activity, an
 * `activity-alias`, and print_service.xml points at that. It is the one thing
 * about a launch the app can read back: the intent keeps the component it was
 * started by, so [isLaunchedVia] on `intent.component.className` is the whole
 * of the signal. No new screen, no second activity to keep in step with
 * MainActivity's theme and splash - which an alias inherits from its target.
 *
 * AddPrintersEntryTest holds the three places this name has to agree - here,
 * the manifest and print_service.xml - because a mismatch breaks quietly: the
 * entry still opens the app, just no longer on the right screen.
 */
object AddPrintersEntry {
    const val CLASS_NAME = "com.gulshan.pocketprint.AddPrintersEntry"

    fun isLaunchedVia(componentClassName: String?): Boolean = componentClassName == CLASS_NAME
}
