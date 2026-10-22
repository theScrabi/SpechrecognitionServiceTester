package org.schabi.speachrecognitionservicetester.report

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.schabi.speachrecognitionservicetester.harness.RecognizerTarget
import org.schabi.speachrecognitionservicetester.model.TestCase
import org.schabi.speachrecognitionservicetester.model.TestResult
import org.schabi.speachrecognitionservicetester.model.TestStatus
import org.schabi.speachrecognitionservicetester.model.androidVersionName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File name suggested when saving the report, e.g. `recognition-test-parakeetype-20261022-1615.md`. */
fun reportFileName(target: RecognizerTarget?): String {
    val app = target?.component?.packageName?.substringAfterLast('.') ?: "none"
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
    return "recognition-test-$app-$stamp.md"
}

/** The results of all [tests] as a Markdown document: device info, summary table, then per-test details and logs. */
fun buildMarkdownReport(
    context: Context,
    target: RecognizerTarget?,
    tests: List<TestCase>,
    results: Map<String, TestResult>,
): String = buildString {
    val count = { status: TestStatus -> results.values.count { it.status == status } }

    appendLine("# RecognitionService test report")
    appendLine()
    appendLine("| | |")
    appendLine("|---|---|")
    if (target != null) {
        appendLine("| Service | ${cell(target.label)} (`${target.component.flattenToShortString()}`) |")
        serviceVersion(context, target.component.packageName)?.let { appendLine("| Service version | ${cell(it)} |") }
    } else {
        appendLine("| Service | none installed / selected |")
    }
    appendLine("| Device | ${cell("${Build.MANUFACTURER} ${Build.MODEL}")} |")
    appendLine("| Android | ${androidVersionName(Build.VERSION.SDK_INT)} (API ${Build.VERSION.SDK_INT}) |")
    appendLine("| Generated | ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())} |")
    appendLine()

    appendLine("## Summary")
    appendLine()
    appendLine(
        "✔ ${count(TestStatus.Passed)} passed · ✘ ${count(TestStatus.Failed)} failed · " +
                "○ ${count(TestStatus.NotTested)} not tested · ⊘ ${count(TestStatus.Unsupported)} not testable"
    )
    appendLine()
    appendLine("| # | Test | API | Result | Detail |")
    appendLine("|---|------|-----|--------|--------|")
    tests.forEachIndexed { index, test ->
        val result = results.getValue(test.id)
        appendLine(
            "| ${index + 1} | ${cell(test.title)} | ${apiLabel(test.minSdk)} | ${statusLabel(result.status)} | " +
                    "${cell(result.detail.orEmpty())} |"
        )
    }
    appendLine()

    appendLine("## Details")
    tests.forEachIndexed { index, test ->
        val result = results.getValue(test.id)
        appendLine()
        appendLine("### ${index + 1}. ${test.title} - ${statusLabel(result.status)}")
        appendLine()
        appendLine("*Requires: ${apiLabel(test.minSdk)}*")
        appendLine()
        appendLine(test.description)
        result.detail?.let {
            appendLine()
            appendLine("**Result:** $it")
        }
        if (result.log.isNotEmpty()) {
            appendLine()
            appendLine("```text")
            result.log.forEach(::appendLine)
            appendLine("```")
        }
    }
}

private fun serviceVersion(context: Context, packageName: String): String? = try {
    val info = context.packageManager.getPackageInfo(packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun statusLabel(status: TestStatus) = when (status) {
    TestStatus.NotTested -> "○ not tested"
    TestStatus.Running -> "… running"
    TestStatus.Passed -> "✔ passed"
    TestStatus.Failed -> "✘ failed"
    TestStatus.Unsupported -> "⊘ not testable on this phone"
}

private fun apiLabel(minSdk: Int) = if (minSdk <= 8) "all APIs" else "API $minSdk+"

/** Text safe inside a Markdown table cell. */
private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")
