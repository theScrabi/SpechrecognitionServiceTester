package org.schabi.speachrecognitionservicetester.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import org.schabi.speachrecognitionservicetester.RunAllState
import org.schabi.speachrecognitionservicetester.model.TestCase
import org.schabi.speachrecognitionservicetester.model.TestResult
import org.schabi.speachrecognitionservicetester.model.TestStatus

/**
 * Progress of "run all": the current test with its instructions and live log. Waits for
 * Start/Skip before tests that need the user; shows a summary once all tests ran.
 */
@Composable
fun RunAllDialog(
    state: RunAllState,
    tests: List<TestCase>,
    results: Map<String, TestResult>,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onAbort: () -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit,
) {
    val test = tests.first { it.id == state.testId }
    val result = results.getValue(test.id)
    val running = !state.waitingForUser && !state.finished
    AlertDialog(
        onDismissRequest = { if (state.finished) onClose() },
        properties = DialogProperties(dismissOnBackPress = state.finished, dismissOnClickOutside = state.finished),
        title = {
            Text(if (state.finished) "All tests done" else "Run all - test ${state.index + 1} of ${state.total}")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                val done = if (state.finished) state.total else state.index
                LinearProgressIndicator(progress = { done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                if (state.finished) {
                    Summary(results.values)
                } else {
                    Text(test.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Section("About this test", MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(test.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    test.instructions?.let {
                        Section("What to do", MaterialTheme.colorScheme.secondaryContainer) {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (running && result.log.isNotEmpty()) {
                        Section("Log", MaterialTheme.colorScheme.surfaceContainerLowest) { LogView(result.log) }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.finished -> Button(onClick = onClose) { Text("Close") }
                state.waitingForUser -> Button(onClick = onStart) { Text("Start") }
                test.needsUserStop -> Button(onClick = onStop) { Text("Stop") }
                else -> TextButton(onClick = {}, enabled = false) { Text("Running…") }
            }
        },
        dismissButton = {
            if (state.finished) {
                TextButton(onClick = onExport) { Text("Save .md") }
            } else {
                Row {
                    TextButton(onClick = onAbort) { Text("Abort all") }
                    TextButton(onClick = onSkip) { Text("Skip") }
                }
            }
        },
    )
}

@Composable
private fun Summary(results: Collection<TestResult>) {
    val count = { status: TestStatus -> results.count { it.status == status } }
    Section("Summary", MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text("✔ ${count(TestStatus.Passed)} passed", color = statusColors(TestStatus.Passed).accent)
        Text("✘ ${count(TestStatus.Failed)} failed", color = statusColors(TestStatus.Failed).accent)
        Text("○ ${count(TestStatus.NotTested)} skipped / not tested")
        Text("⊘ ${count(TestStatus.Unsupported)} not testable on this phone")
    }
}
