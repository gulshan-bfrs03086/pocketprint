package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.print.PrinterAutoSetup
import com.gulshan.pocketprint.print.SetupProgress
import com.gulshan.pocketprint.print.StepState
import com.gulshan.pocketprint.print.TestLabelOutcome

/**
 * The entry point for one-tap setup. Deliberately prominent: for a thermal
 * printer this is the path that works, and hunting through discovery, saving,
 * and then correcting the command language by hand is the path that does not.
 */
@Composable
fun AutoSetupCard(
    candidateCount: Int,
    stock: MediaSize,
    onStockChange: (MediaSize) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.setup_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(R.string.setup_card_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.setup_stock_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            ChipRow(
                items = MediaSize.LABELS,
                selected = stock,
                label = { it.label },
                onSelect = onStockChange,
            )
            Text(
                stringResource(R.string.setup_stock_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = onStart,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    stringResource(
                        if (candidateCount == 1) {
                            R.string.setup_start_one
                        } else {
                            R.string.setup_start_many
                        },
                    ),
                )
            }
        }
    }
}

/** Device picker, shown only when there is more than one Bluetooth candidate. */
@Composable
fun AutoSetupPicker(
    candidates: List<Printer>,
    onDismiss: () -> Unit,
    onPick: (Printer) -> Unit,
    /** Null on a device with no companion device picker. */
    onPairNew: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(
                            if (onPairNew != null) {
                                R.string.setup_picker_empty
                            } else {
                                // The old route, still the only one on Android 7.
                                R.string.setup_picker_empty_legacy
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                candidates.forEach { printer ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(printer) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(printer.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            printer.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Where this used to send people to Android Settings to hunt
            // through a list of MAC addresses, it now opens the system's own
            // picker - which scans, shows the devices, and pairs, without this
            // app holding a scan permission or knowing what is nearby.
            if (onPairNew != null) {
                TextButton(onClick = onPairNew) { Text(stringResource(R.string.setup_pair_new)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Live progress. Each step shows what actually happened, not just a spinner. */
@Composable
fun AutoSetupDialog(
    progress: SetupProgress,
    onDismiss: () -> Unit,
    onOutcome: (TestLabelOutcome) -> Unit,
    alternateDialect: String?,
    onRetestOtherDialect: () -> Unit,
) {
    val succeeded = progress.finished && progress.error == null
    val printedTest = progress.steps.any {
        it.id == PrinterAutoSetup.STEP_TEST && it.state == StepState.DONE
    }
    var answer by remember(progress.printer?.id) { mutableStateOf<TestLabelOutcome?>(null) }

    AlertDialog(
        onDismissRequest = { if (progress.finished) onDismiss() },
        title = {
            Text(
                stringResource(
                    when {
                        !progress.finished -> R.string.setup_running
                        succeeded -> R.string.setup_ready
                        else -> R.string.setup_failed
                    },
                ),
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                progress.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.Top) {
                        StepIcon(step.state)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                step.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (step.state == StepState.SKIPPED) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            step.detail?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = if (step.state == StepState.FAILED) {
                                        FontFamily.Monospace
                                    } else {
                                        FontFamily.Default
                                    },
                                    color = if (step.state == StepState.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }

                if (succeeded && printedTest) {
                    TestLabelQuestion(
                        answer = answer,
                        alternateDialect = alternateDialect,
                        onAnswer = { answer = it; onOutcome(it) },
                        onRetest = { answer = null; onRetestOtherDialect() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = progress.finished) {
                Text(
                    stringResource(
                        if (succeeded) R.string.action_done else R.string.action_close,
                    ),
                )
            }
        },
    )
}

/**
 * The one question the protocol cannot answer.
 *
 * A thermal printer reports paper loaded, head down and no error whether it
 * just printed a perfect label, fed a blank one because the stock is ordinary
 * paper, or spat out a page of command text because it is not listening in the
 * dialect it was sent. Those three faults have nothing in common except that
 * the printer cannot tell them apart — so the person holding the label is
 * asked, and the answer is acted on.
 *
 * Not asking cost a whole field-test session. Six jobs reported as completed,
 * the investigation went to the command language, and the fault was the paper.
 */
@Composable
private fun TestLabelQuestion(
    answer: TestLabelOutcome?,
    alternateDialect: String?,
    onAnswer: (TestLabelOutcome) -> Unit,
    onRetest: () -> Unit,
) {
    when (answer) {
        null -> {
            Text(
                stringResource(R.string.test_label_question),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.test_label_question_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnswerRow(stringResource(R.string.test_label_answer_correct)) {
                onAnswer(TestLabelOutcome.CORRECT)
            }
            AnswerRow(stringResource(R.string.test_label_answer_garbled)) {
                onAnswer(TestLabelOutcome.GARBLED)
            }
            AnswerRow(stringResource(R.string.test_label_answer_nothing)) {
                onAnswer(TestLabelOutcome.NOTHING)
            }
        }

        TestLabelOutcome.CORRECT -> Advice(
            stringResource(R.string.test_label_confirmed_title),
            stringResource(R.string.test_label_confirmed_body),
        )

        TestLabelOutcome.NOTHING -> {
            Advice(
                stringResource(R.string.test_label_media_title),
                stringResource(R.string.test_label_media_body),
            )
        }

        TestLabelOutcome.GARBLED -> {
            Advice(
                stringResource(R.string.test_label_dialect_title),
                stringResource(R.string.test_label_dialect_body),
            )
            if (alternateDialect != null) {
                Button(
                    onClick = onRetest,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.test_label_switch_dialect, alternateDialect))
                }
            } else {
                Text(
                    stringResource(R.string.test_label_dialect_manual),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnswerRow(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Advice(heading: String, body: String) {
    Text(
        heading,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepIcon(state: StepState) {
    when (state) {
        StepState.RUNNING -> CircularProgressIndicator(
            Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        StepState.DONE -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.step_done),
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(20.dp),
        )
        StepState.FAILED -> Icon(
            Icons.Filled.Error,
            contentDescription = stringResource(R.string.step_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        StepState.SKIPPED -> Icon(
            Icons.Filled.RemoveCircleOutline,
            contentDescription = stringResource(R.string.step_skipped),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        StepState.PENDING -> Icon(
            Icons.Filled.RadioButtonUnchecked,
            contentDescription = stringResource(R.string.step_pending),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
