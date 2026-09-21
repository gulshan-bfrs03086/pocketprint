package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.medium,
            )
            .padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * For things that have gone wrong and that the user needs to know about, as
 * opposed to things they might like to know. Deliberately louder than
 * [InfoBanner]: the one case this exists for is data the app could not read
 * back, which the user has no other way of finding out about.
 */
@Composable
fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.shapes.medium,
            )
            .padding(12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * Horizontally scrolling single-select chip row, which keeps the selection
 * visible.
 *
 * The scrolling-into-view is not a flourish. There are sixteen label stocks
 * now, and sixteen chips are several screens wide, so the selected one is
 * routinely off the right-hand edge - which makes a picker that cannot show
 * what is picked. It bites hardest on the way in: a selection restored across
 * a rotation, or the size a printer was set up with, would otherwise come back
 * apparently unselected, and the obvious repair is to pick a size that was
 * already correct.
 *
 * It moves the row as little as that takes. The first version put the selection
 * at the leading edge every time, which is the simplest thing that keeps it
 * visible and hides everything before it: a language row of TSPL, ZPL, ESC/POS,
 * PCL opened with its first two chips cut off because the third was selected,
 * and a size the user had added in front of the catalogue was scrolled out of
 * sight by the very selection it sat beside. A chip that is already fully on
 * screen is left alone; one that is not is brought in at the nearest edge; one
 * too far away to have been laid out at all is jumped to with a third of the row
 * left in front of it, so what precedes it is still visibly there to scroll to.
 */
@Composable
fun <T> ChipRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val index = items.indexOf(selected)

    // Keyed on the list as well, because the choices narrow under the user -
    // picking a printer cuts the language row down to what it speaks - and the
    // same selection then sits at a different index.
    LaunchedEffect(index, items.size) {
        if (index < 0) return@LaunchedEffect

        // Nothing has a position until the row has been measured once.
        snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }

        val info = state.layoutInfo
        val start = info.viewportStartOffset
        val end = info.viewportEndOffset
        val chip = info.visibleItemsInfo.firstOrNull { it.index == index }
        when {
            // Instant, not animated: from a cold start this is a jump across
            // most of the row, and watching it slide in from the far end is
            // worse than not seeing it move.
            chip == null -> state.scrollToItem(index, scrollOffset = -(end - start) / 3)
            chip.offset < start -> state.animateScrollBy((chip.offset - start).toFloat())
            chip.offset + chip.size > end ->
                state.animateScrollBy((chip.offset + chip.size - end).toFloat())
        }
    }

    LazyRow(
        modifier.fillMaxWidth(),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items) { _, item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = {
                    Text(label(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}
