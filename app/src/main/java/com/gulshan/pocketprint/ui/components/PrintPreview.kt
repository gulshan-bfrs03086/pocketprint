package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.ui.vm.PreviewState

/**
 * What the printer will actually mark.
 *
 * Deliberately not a rendering of the document: it is the packed one-bit raster
 * read back, so a photo that dithers to mud and a hairline that falls under the
 * threshold and disappears both show up here rather than on a label that has
 * already been consumed finding out.
 */
@Composable
fun PrintPreviewDialog(state: PreviewState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preview_title, state.printerName)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.size(28.dp))

                    state.bitmap != null -> {
                        Text(
                            stringResource(R.string.preview_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = stringResource(
                                R.string.preview_content_description,
                            ),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                // The raster is black on white whatever the app's
                                // theme is, so give it a white ground rather than
                                // letting a dark surface swallow the paper.
                                .background(Color.White)
                                .heightIn(max = 420.dp),
                        )
                        Text(
                            stringResource(
                                R.string.preview_dimensions,
                                state.bitmap.width,
                                state.bitmap.height,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Text(
                        state.message ?: stringResource(R.string.preview_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
