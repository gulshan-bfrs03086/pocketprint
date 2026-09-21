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
