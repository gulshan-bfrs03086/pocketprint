package com.gulshan.pocketprint.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.gulshan.pocketprint.model.MediaSize

/**
 * Whether there is enough width to lay out for more than one hand.
 *
 * 600dp is Android's own break between a phone and a tablet, and it is the same
 * number a phone crosses when it is unfolded or turned on its side. No window
 * size class library for one threshold.
 */
@Composable
fun isWideScreen(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/**
 * Keeps content to a readable width on a wide screen.
 *
 * Everything here is a single column, which on a tablet means a line of text
 * running the full width of a ten-inch display. Android 16 already ignores
 * orientation locks on large screens and 17 removes the opt-out, so landscape
 * on a tablet is not something this app gets to avoid any more.
 */
@Composable
fun AdaptiveContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 720.dp)) { content() }
    }
}

/**
 * Saves an enum by name.
 *
 * A value the current build no longer has restores as null, which
 * rememberSaveable treats as "use the initial value" — the right answer for a
 * form field after an update changed the options underneath it.
 */
fun <T : Enum<T>> enumSaver(entries: List<T>): Saver<T, String> = Saver(
    save = { it.name },
    restore = { name -> entries.firstOrNull { it.name == name } },
)

/**
 * Saves a media size whole rather than by id, because a custom size the user
 * typed in is not in any table to look it up in.
 */
val MediaSizeSaver: Saver<MediaSize, Any> = listSaver(
    save = { listOf(it.id, it.label, it.widthMicrons, it.heightMicrons) },
    restore = {
        MediaSize(
            id = it[0] as String,
            label = it[1] as String,
            widthMicrons = it[2] as Int,
            heightMicrons = it[3] as Int,
        )
    },
)
