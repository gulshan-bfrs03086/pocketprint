package com.gulshan.pocketprint.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.R

/**
 * Shown once, before anything is asked for.
 *
 * Every permission used to be requested on the very first frame, before the
 * user had seen what the app was for. A permission dialog with no context is a
 * dialog about nothing, and refusing it is the reasonable response — but two
 * refusals is permanent on modern Android, so the app could be made
 * permanently unable to see a Bluetooth printer before it had shown a single
 * screen.
 *
 * So this asks for nothing. It says what the app does, what it will need and
 * when, and what it deliberately does not do. The actual requests happen later,
 * at the moment each one is needed.
 */
@Composable
fun FirstRunScreen(onContinue: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        Text(
            stringResource(R.string.first_run_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        Section(
            stringResource(R.string.first_run_bluetooth_title),
            stringResource(R.string.first_run_bluetooth_body),
        )

        Section(
            stringResource(R.string.first_run_notifications_title),
            stringResource(R.string.first_run_notifications_body),
        )

        Section(
            stringResource(R.string.first_run_nothing_title),
            stringResource(R.string.first_run_nothing_body),
        )

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) { Text(stringResource(R.string.first_run_start)) }

        Text(
            stringResource(R.string.first_run_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
