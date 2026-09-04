package com.gulshan.pocketprint.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.model.JobState
import com.gulshan.pocketprint.print.JobError
import com.gulshan.pocketprint.ui.components.InfoBanner
import com.gulshan.pocketprint.ui.vm.PrintersViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val UNFINISHED = setOf(JobState.QUEUED, JobState.RENDERING, JobState.SENDING)

@Composable
fun JobsScreen(viewModel: PrintersViewModel) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val message by viewModel.jobsMessage.collectAsStateWithLifecycle()
    val formatter = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.jobs_title), style = MaterialTheme.typography.titleMedium)
                if (jobs.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearJobs() }) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }

        message?.let { text ->
            item {
                Column {
                    InfoBanner(text)
                    TextButton(onClick = { viewModel.clearJobsMessage() }) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
        }

        if (jobs.isEmpty()) {
            item { InfoBanner(stringResource(R.string.jobs_empty)) }
        }

        items(jobs, key = { it.id }) { job ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        job.documentName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${job.printerName} - ${formatter.format(Date(job.createdAtEpochMs))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when (job.state) {
                            // "Printed" is claimed only where a printer said so.
                            // Everything else that left the device is "Sent",
                            // and the row carries the reason underneath.
                            JobState.COMPLETED -> stringResource(
                                R.string.jobs_printed,
                                (job.bytesSent / 1024).toInt(),
                            )
                            JobState.SENT -> stringResource(
                                R.string.jobs_sent_unconfirmed,
                                (job.bytesSent / 1024).toInt(),
                            )
                            JobState.FAILED -> job.error ?: stringResource(R.string.jobs_failed)

                            else -> job.state.name.lowercase()
                                .replaceFirstChar { it.uppercase() }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (job.state) {
                            JobState.FAILED -> MaterialTheme.colorScheme.error
                            JobState.COMPLETED -> Color(0xFF2E7D32)
                            JobState.SENT -> Color(0xFF8A6D00)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // What the transport said is kept as it is - it is what
                    // makes a printer report useful - but what to do about it
                    // goes first, because "read failed, socket might closed" is
                    // an accurate description of a file descriptor and no help
                    // at all to somebody holding a printer.
                    JobError.explain(job.error)?.let { advice ->
                        Text(
                            advice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    job.note?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // A job that has not finished is one somebody may need to
                    // stop. Before this, a printer that stopped reading meant
                    // force-stopping the app.
                    if (job.state in UNFINISHED) {
                        TextButton(
                            onClick = { viewModel.cancelJob(job) },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    } else if (job.replayable) {
                        TextButton(
                            onClick = { viewModel.reprint(job) },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.jobs_reprint))
                        }
                    }
                }
            }
        }
    }
}
