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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
                "Set up a label or receipt printer",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Pairs, connects, asks the printer which command language it " +
                    "speaks, configures the label size, prints a test label, and " +
                    "adds it to Android's print dialog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Label stock loaded in the printer",
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
                "This has to match the labels actually loaded. Set it too long and " +
                    "the printer feeds forward looking for a gap that is not there, " +
                    "then stops with a paper fault.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Button(
                onClick = onStart,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (candidateCount == 1) "Set up my printer" else "Set up a printer")
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which printer?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text(
                        "No paired Bluetooth printers found. Pair the printer in " +
                            "Android Settings first — the PIN is usually 0000 — then " +
                            "come back and try again.",
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
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                when {
                    !progress.finished -> "Setting up..."
                    succeeded -> "Ready to print"
                    else -> "Setup failed"
                },
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
                Text(if (succeeded) "Done" else "Close")
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
                "Did a label come out?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "The printer cannot answer this one. Whatever happened, it " +
                    "reports paper loaded, head down and no error.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnswerRow("It printed and looks right") { onAnswer(TestLabelOutcome.CORRECT) }
            AnswerRow("It printed, but the output is wrong") {
                onAnswer(TestLabelOutcome.GARBLED)
            }
            AnswerRow("Nothing, or a blank label") { onAnswer(TestLabelOutcome.NOTHING) }
        }

        TestLabelOutcome.CORRECT -> Advice(
            "Confirmed.",
            "This printer is now marked as confirmed by an actual label. That is " +
                "the only confirmation Bluetooth and USB can give — neither " +
                "protocol reports what the printer did with the bytes.",
        )

        TestLabelOutcome.NOTHING -> {
            Advice(
                "That is almost always the paper.",
                "Thermal printers have no ink: they mark heat-sensitive stock, on " +
                    "one side only. Ordinary paper labels, or a thermal roll " +
                    "loaded upside down, feed perfectly and stay white. Check the " +
                    "stock, then print another test from the printer's settings.",
            )
        }

        TestLabelOutcome.GARBLED -> {
            Advice(
                "That is the command language.",
                "The printer is not listening in the dialect it was sent, so it is " +
                    "printing the commands instead of obeying them.",
            )
            if (alternateDialect != null) {
                Button(
                    onClick = onRetest,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Switch to $alternateDialect and try again")
                }
            } else {
                Text(
                    "Change it in the printer's settings, where there is a " +
                        "test-page button.",
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
            contentDescription = "done",
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(20.dp),
        )
        StepState.FAILED -> Icon(
            Icons.Filled.Error,
            contentDescription = "failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        StepState.SKIPPED -> Icon(
            Icons.Filled.RemoveCircleOutline,
            contentDescription = "skipped",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        StepState.PENDING -> Icon(
            Icons.Filled.RadioButtonUnchecked,
            contentDescription = "pending",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
