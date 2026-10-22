package org.schabi.speachrecognitionservicetester.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.schabi.speachrecognitionservicetester.model.TestCase
import org.schabi.speachrecognitionservicetester.model.TestResult
import org.schabi.speachrecognitionservicetester.model.TestStatus

/**
 * Offers to run [test]; while it runs shows the live log with Stop/Abort, afterwards the outcome.
 * Can't be dismissed while the test runs.
 */
@Composable
fun TestDialog(
    test: TestCase,
    result: TestResult,
    isRunning: Boolean,
    otherRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAbort: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isRunning, dismissOnClickOutside = !isRunning),
        title = { Text(test.title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Section("About this test", MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(test.description, style = MaterialTheme.typography.bodyMedium)
                }
                test.instructions?.let {
                    Section("What to do", MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (result.log.isNotEmpty()) {
                    Section("Log", MaterialTheme.colorScheme.surfaceContainerLowest) { LogView(result.log) }
                }
                if (!isRunning && (result.status == TestStatus.Passed || result.status == TestStatus.Failed)) {
                    val colors = statusColors(result.status)
                    Section(
                        if (result.status == TestStatus.Passed) "✔ Passed" else "✘ Failed",
                        colors.container,
                        colors.content,
                    ) {
                        Text(result.detail.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                if (otherRunning) {
                    Text("Another test is still running.", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            when {
                isRunning && test.needsUserStop -> Button(onClick = onStop) { Text("Stop") }
                isRunning -> TextButton(onClick = {}, enabled = false) { Text("Running…") }
                else -> Button(onClick = onStart, enabled = !otherRunning) {
                    Text(if (result.status == TestStatus.NotTested) "Start test" else "Run again")
                }
            }
        },
        dismissButton = {
            if (isRunning) TextButton(onClick = onAbort) { Text("Abort") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** A titled card separating one part of the dialog from the others. */
@Composable
internal fun Section(
    title: String,
    container: Color,
    content: Color = contentColorFor(container),
    body: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            body()
        }
    }
}

@Composable
internal fun LogView(lines: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { state.animateScrollToItem(lines.lastIndex) }
    LazyColumn(
        state = state,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp),
    ) {
        items(lines) {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
