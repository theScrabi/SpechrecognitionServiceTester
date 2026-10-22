package org.schabi.speachrecognitionservicetester.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.speachrecognitionservicetester.TesterViewModel
import org.schabi.speachrecognitionservicetester.harness.RecognizerTarget
import org.schabi.speachrecognitionservicetester.harness.TargetServices
import org.schabi.speachrecognitionservicetester.model.TestResult
import org.schabi.speachrecognitionservicetester.model.TestStatus
import org.schabi.speachrecognitionservicetester.report.buildMarkdownReport
import org.schabi.speachrecognitionservicetester.report.reportFileName

private fun Context.hasMicPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestListScreen(viewModel: TesterViewModel = viewModel()) {
    val context = LocalContext.current
    var hasMic by remember { mutableStateOf(context.hasMicPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMic = it
    }
    LaunchedEffect(Unit) {
        if (!hasMic) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshTargets()
        hasMic = context.hasMicPermission()
        onPauseOrDispose {}
    }

    val results by viewModel.results.collectAsStateWithLifecycle()
    val runningId by viewModel.runningId.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val selected by viewModel.selectedTarget.collectAsStateWithLifecycle()
    val runAll by viewModel.runAll.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val report = { buildMarkdownReport(context, selected, viewModel.tests, results) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = report()
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) "Report saved" else "Could not save the report", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RecognitionService Tester") },
                actions = {
                    IconButton(onClick = viewModel::runAll, enabled = runningId == null && selected != null) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Run all tests")
                    }
                    IconButton(onClick = viewModel::refreshTargets) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh services")
                    }
                    ExportMenu(
                        onShare = { context.shareReport(report()) },
                        onSave = { saveLauncher.launch(reportFileName(selected)) },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                TargetSelector(targets, selected, enabled = runningId == null, onSelect = viewModel::selectTarget)
            }
            if (!hasMic) {
                item {
                    Banner("The tester needs the microphone permission: SpeechRecognizer clients must hold RECORD_AUDIO.") {
                        TextButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Grant")
                        }
                    }
                }
            }
            if (selected == null) {
                item {
                    Banner(
                        if (targets.isEmpty()) "No RecognitionService is installed on this phone."
                        else "Parakeetype is not installed. Install it, or pick another service above."
                    )
                }
            }
            item { Summary(results.values) }
            items(viewModel.tests, key = { it.id }) { test ->
                TestCard(test, results.getValue(test.id), onClick = { openId = test.id })
            }
        }
    }

    runAll?.let { state ->
        RunAllDialog(
            state = state,
            tests = viewModel.tests,
            results = results,
            onStart = viewModel::startNext,
            onSkip = viewModel::skipNext,
            onStop = viewModel::userStop,
            onAbort = viewModel::abort,
            onClose = viewModel::closeRunAll,
            onExport = { saveLauncher.launch(reportFileName(selected)) },
        )
    }

    if (runAll == null) openId?.let { id ->
        val test = viewModel.tests.first { it.id == id }
        TestDialog(
            test = test,
            result = results.getValue(id),
            isRunning = runningId == id,
            otherRunning = runningId != null && runningId != id,
            onStart = { viewModel.run(test) },
            onStop = viewModel::userStop,
            onAbort = viewModel::abort,
            onDismiss = { openId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelector(
    targets: List<RecognizerTarget>,
    selected: RecognizerTarget?,
    enabled: Boolean,
    onSelect: (RecognizerTarget) -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    OutlinedCard(
        onClick = { sheetOpen = true },
        enabled = enabled && targets.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Service under test", style = MaterialTheme.typography.labelMedium)
                Text(selected?.label ?: "none", style = MaterialTheme.typography.titleMedium)
                Text(
                    (selected?.component ?: TargetServices.PARAKEETYPE).packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose service")
        }
    }
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Text(
                "Choose the service to test",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(targets, key = { it.component.flattenToString() }) { target ->
                    TargetOption(target, isSelected = target.component == selected?.component) {
                        sheetOpen = false
                        onSelect(target)
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetOption(target: RecognizerTarget, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colors.primaryContainer else colors.surfaceContainerHigh,
        ),
        border = if (isSelected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(target.label, style = MaterialTheme.typography.titleMedium)
                Text(target.component.packageName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    target.component.className.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.primary)
        }
    }
}

/** Top-bar menu exporting the results as Markdown. */
@Composable
private fun ExportMenu(onShare: () -> Unit, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Share, contentDescription = "Export results")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Share as Markdown") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text("Save as .md file") },
                leadingIcon = { Icon(Icons.Filled.Create, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSave()
                },
            )
        }
    }
}

private fun Context.shareReport(markdown: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "RecognitionService test report")
        putExtra(Intent.EXTRA_TEXT, markdown)
    }
    startActivity(Intent.createChooser(send, "Share test report"))
}

@Composable
private fun Banner(text: String, action: @Composable () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action()
        }
    }
}

@Composable
private fun Summary(results: Collection<TestResult>) {
    val count = { status: TestStatus -> results.count { it.status == status } }
    Text(
        "✔ ${count(TestStatus.Passed)} passed   ✘ ${count(TestStatus.Failed)} failed   " +
                "○ ${count(TestStatus.NotTested)} not tested   ⊘ ${count(TestStatus.Unsupported)} not testable",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
