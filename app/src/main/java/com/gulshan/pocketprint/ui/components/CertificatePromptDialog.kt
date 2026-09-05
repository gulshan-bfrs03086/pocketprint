package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.ipp.PrinterTrust
import com.gulshan.pocketprint.ui.vm.CertificatePrompt
import java.text.DateFormat
import java.util.Date

/**
 * The one decision the trust store cannot make.
 *
 * Shows what the printer presented - who it claims to be, its fingerprint, how
 * long it is valid - and says plainly that a self-signed certificate is what
 * every printer has and also what an impostor would have. The fingerprint is
 * the thing to compare against the printer's own status page; the dialog
 * exists to put it in front of the person who can.
 */
@Composable
fun CertificatePromptDialog(
    prompt: CertificatePrompt,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = prompt.printer.displayName
    val title = when {
        prompt.alreadyTrusted -> R.string.certificate_title_trusted
        prompt.previousPin != null -> R.string.certificate_title_changed
        else -> R.string.certificate_title_untrusted
    }
    val body = when {
        prompt.alreadyTrusted -> stringResource(R.string.certificate_body_trusted, name)
        prompt.previousPin != null ->
            stringResource(R.string.certificate_body_changed, PrinterTrust.display(prompt.previousPin))
        else -> stringResource(R.string.certificate_body_untrusted, name)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(body)
                if (!prompt.alreadyTrusted) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.certificate_subject, prompt.subject),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.certificate_fingerprint),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        PrinterTrust.display(prompt.fingerprint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.certificate_expires,
                            DateFormat.getDateInstance().format(Date(prompt.notAfterEpochMs)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (prompt.alreadyTrusted) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.certificate_close)) }
            } else {
                TextButton(onClick = onTrust) { Text(stringResource(R.string.certificate_trust)) }
            }
        },
        dismissButton = {
            if (!prompt.alreadyTrusted) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.certificate_not_now)) }
            }
        },
    )
}
