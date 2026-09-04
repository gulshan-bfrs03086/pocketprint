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
import androidx.compose.ui.unit.dp

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
        Text("PocketPrint", style = MaterialTheme.typography.headlineMedium)

        Text(
            "Printing from Android to a Bluetooth thermal printer, without a cloud " +
                "service, an account, or a PC in the middle. Everything happens on " +
                "this device and your own network.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Section(
            "Bluetooth, when you set up a printer",
            "To find the printer and to send it the job. Android calls part of this " +
                "a location permission on versions before Android 12, which is a " +
                "quirk of how Bluetooth scanning used to work — PocketPrint does not " +
                "ask for, use, or store your location.",
        )

        Section(
            "Notifications, when you print",
            "A print job runs in the background so that leaving the app does not " +
                "kill the transfer. The notification is how you see progress and how " +
                "you cancel a job that has gone wrong.",
        )

        Section(
            "Nothing else",
            "No account, no analytics, no network calls except to your own printer. " +
                "Documents are read through Android's file picker, so PocketPrint " +
                "never asks for access to your storage or your photos.",
        )

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) { Text("Get started") }

        Text(
            "Nothing is requested yet. Each permission is asked for at the moment " +
                "it is needed, and the app keeps working without them - it just " +
                "cannot do the part that needs them.",
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
