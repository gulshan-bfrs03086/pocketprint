package com.gulshan.pocketprint.model

/**
 * This printer, as setup or discovery just built it, with what the user had
 * already told the saved printer of the same id carried across.
 *
 * Setup builds its printer from a freshly discovered device, so everything the
 * user may have changed since is back at its default - and saving it replaces
 * the saved record by id. The one that matters is [Printer.stock]: a printer
 * loaded with continuous paper and set to Continuous was silently put back to
 * "gap, 3 mm", and every job after that began `GAP 3 mm, 0 mm`. The printer
 * keeps what a job tells it, so it went looking for a gap that is not on the
 * roll and stopped with a paper fault. Nothing on screen said the setting had
 * changed.
 *
 * Carried across, because only the user can know them and setup cannot detect
 * them:
 *  - [Printer.stock]: sensing, gap, darkness and speed;
 *  - [PrinterCapabilities.compressedBitmaps], which no probe can detect either;
 *  - the Bluetooth link, when it is the same device: a printer fixed to
 *    Classic because Auto picked the wrong radio should stay on Classic;
 *  - custom label sizes typed into printer settings, which setup's catalogue
 *    would otherwise drop.
 *
 * Not carried across, because setup has just measured or chosen them: the
 * language, the label size setup was asked for, the head width and the resolution
 * (the head width is derived from it, so keeping one without the other would
 * leave them disagreeing), the model, and [Printer.testPrintConfirmed], which is
 * about the test label this setup is about to print.
 *
 * [existing] is the saved printer, or null for a printer being saved for the
 * first time, which is returned as it is. A record with a different id is not
 * this printer's, and is ignored.
 */
fun Printer.keepingSettingsOf(existing: Printer?): Printer {
    if (existing == null || existing.id != id) return this

    val mine = address
    val theirs = existing.address
    val sameBluetoothDevice = mine is PrinterAddress.Bluetooth &&
        theirs is PrinterAddress.Bluetooth &&
        mine.mac.equals(theirs.mac, ignoreCase = true)

    val customSizes = existing.capabilities.mediaSizes
        .filter { it.isCustom }
        .filter { custom -> capabilities.mediaSizes.none { it.sameDimensionsAs(custom) } }

    return copy(
        address = if (sameBluetoothDevice) theirs else mine,
        stock = existing.stock,
        capabilities = capabilities.copy(
            mediaSizes = capabilities.mediaSizes + customSizes,
            compressedBitmaps = existing.capabilities.compressedBitmaps,
        ),
    )
}

private fun MediaSize.sameDimensionsAs(other: MediaSize): Boolean =
    widthMicrons == other.widthMicrons && heightMicrons == other.heightMicrons

/**
 * What the setup card's paper chips show.
 *
 * A choice made on the card is shown as chosen. Until then it shows what the
 * printer about to be set up already is - so it never says Gap over a printer
 * that Set up will leave on Continuous - and that printer is only known when
 * there is exactly one candidate: its saved sensing, or Gap for a printer that
 * has never been saved, which is what it will get. With several candidates it
 * depends on which one is picked, and with none the printer is still to be
 * paired, so nothing is shown as selected rather than something that may be wrong.
 *
 * What is *applied* is a different thing: only a choice that was made (see
 * [asSavedAfterSetup]). The displayed value is never applied as if it were one.
 */
fun setupSensing(chosen: MediaSensing?, saved: List<Printer>, candidates: List<Printer>): MediaSensing? {
    if (chosen != null) return chosen
    val only = candidates.singleOrNull() ?: return null
    return saved.firstOrNull { it.id == only.id }?.stock?.sensing ?: MediaSensing.GAP
}

/** This printer loaded with [sensing] paper; unchanged when none is given. */
fun Printer.loadedWith(sensing: MediaSensing?): Printer =
    if (sensing == null) this else copy(stock = stock.copy(sensing = sensing))

/**
 * The printer as setup should save it: what setup built, with the saved printer's
 * settings carried across ([keepingSettingsOf]) and then the paper the person
 * chose on the card, if they chose one.
 *
 * In that order, and in one place. The choice has to come *after* the carrying
 * across, which puts the saved stock back; applied before it, it printed a
 * correct test label and was then saved as the old setting.
 *
 * [chosen] is null when the card was not touched, and then the saved printer's
 * paper stands.
 */
fun Printer.asSavedAfterSetup(saved: Printer?, chosen: MediaSensing?): Printer =
    keepingSettingsOf(saved).loadedWith(chosen)
