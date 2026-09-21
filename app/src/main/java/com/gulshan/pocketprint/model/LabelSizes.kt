package com.gulshan.pocketprint.model

/**
 * The stock the Labels screen offers when [printer] is the one selected.
 *
 * The catalogue, and in front of it whatever the user has told this printer it
 * is loaded with. Printer settings has always let a size be typed in, and the
 * Labels screen never read what came out: the picker was bound to the fixed
 * catalogue, so a custom roll could be configured for a printer and then not
 * be chosen for a label. Anyone with stock the catalogue does not carry was
 * stuck at exactly the point they had already done the work to get unstuck.
 *
 * Their own sizes go first. They are what this printer is loaded with, which
 * makes them the likeliest choice, and at the far end of a row now fourteen
 * catalogue sizes long they would need a scroll to find. It costs the standard
 * sizes a shifted position for someone with a custom one, and that someone
 * could not pick it at all before.
 *
 * Only *custom* sizes come across, not everything in the printer's list. That
 * list also holds whatever it was set to default to, which can be A4 - a size
 * that means nothing on a label roll and would only be a wrong chip to tap.
 *
 * A custom size that is the same stock as a catalogue entry is not offered
 * twice. Typing in 50 x 25 was the only way to print that roll before 1.5.0
 * added it, so those entries exist on real printers now, and a row reading
 * "50 x 25 mm" beside "Label 50 x 25 mm" is two names for one roll - the exact
 * thing that stops a picker meaning anything. It stays in printer settings,
 * where it was entered; this is only about not listing it twice here.
 *
 * [selected] is always kept in the result. The choices change when the printer
 * does - picking one with no custom sizes drops another's - and a selection
 * that vanishes from its own row would still be what gets printed, with
 * nothing on screen saying so. Appended rather than dropped, it stays visible
 * and selected until the user picks something else.
 */
fun labelSizesFor(printer: Printer?, selected: MediaSize? = null): List<MediaSize> {
    val catalogue = MediaSize.LABELS

    val own = printer?.capabilities?.mediaSizes.orEmpty()
        .filter { it.isCustom }
        .filter { custom ->
            catalogue.none {
                it.widthMicrons == custom.widthMicrons && it.heightMicrons == custom.heightMicrons
            }
        }

    val offered = (own + catalogue).distinctBy { it.id }
    return if (selected == null || offered.any { it.id == selected.id }) offered else offered + selected
}

/**
 * The size [printer] is loaded with, as the Labels screen would offer it, or
 * null when its list holds nothing that is label stock.
 *
 * The first entry in a printer's sizes is what it was set to: setup puts the
 * stock the user picked at the front, and the settings dialog does the same
 * with its Default stock. Everything after that is what else it can take.
 *
 * "First" is not quite "first entry", for two reasons that both come from the
 * list being shared with paper. A printer can be defaulted to A4, which is
 * nothing a label screen offers, so entries are tried in order until one is
 * something it does. And the match is on dimensions rather than identity,
 * because the same roll has more than one name: a 4 x 6 photo size is the
 * catalogue's 4 x 6 label, and a typed-in 50 x 25 is the catalogue's, which
 * [labelSizesFor] deliberately does not list a second time. Returning the
 * printer's own entry there would be a size the row does not contain.
 */
fun defaultLabelSize(printer: Printer): MediaSize? {
    val offered = labelSizesFor(printer)
    for (size in printer.capabilities.mediaSizes) {
        offered.firstOrNull {
            it.widthMicrons == size.widthMicrons && it.heightMicrons == size.heightMicrons
        }?.let { return it }
    }
    return null
}

/**
 * The size the Labels screen should be on once [printer] has been selected.
 *
 * The screen used to open on 100 x 50 mm and stay there whatever the printer
 * was loaded with, so a printer set up for 4 x 6 printed at 100 x 50 unless
 * somebody noticed and tapped another chip. On a printer that finds labels by
 * their gap that is not a cosmetic slip: it feeds past the gap it should have
 * stopped at, and stops with a paper fault.
 *
 * The printer's size is where the screen *starts*, not what it insists on. The
 * size row sits above the printer row, so choosing a size and then a printer is
 * the ordinary order, and replacing a size somebody deliberately picked because
 * they then tapped the printer would be the surprise this exists to remove,
 * pointing the other way. Once [chosenByUser], [current] stands.
 *
 * A printer with no label size of its own leaves [current] alone rather than
 * guessing.
 */
fun sizeWhenPrinterSelected(
    current: MediaSize,
    chosenByUser: Boolean,
    printer: Printer?,
): MediaSize {
    if (chosenByUser || printer == null) return current
    return defaultLabelSize(printer) ?: current
}
