package org.schabi.speachrecognitionservicetester.model

import android.os.Build
import org.schabi.speachrecognitionservicetester.harness.TestHarness

enum class TestStatus { NotTested, Running, Passed, Failed, Unsupported }

data class TestOutcome(val passed: Boolean, val detail: String)

fun pass(detail: String) = TestOutcome(true, detail)
fun fail(detail: String) = TestOutcome(false, detail)

/** State of one test card: [log] holds the lines of the latest run. */
data class TestResult(
    val status: TestStatus,
    val detail: String? = null,
    val log: List<String> = emptyList(),
)

/**
 * One functionality of the RecognitionService interface.
 *
 * @param minSdk API level the client side of the functionality needs.
 * @param instructions What the user has to do while the test runs, if anything.
 * @param needsUserStop The test waits for the dialog's "Stop" button.
 */
class TestCase(
    val id: String,
    val title: String,
    val description: String,
    val minSdk: Int,
    val instructions: String? = null,
    val needsUserStop: Boolean = false,
    val run: suspend TestHarness.() -> TestOutcome,
) {
    val isSupportedOnDevice: Boolean
        get() = Build.VERSION.SDK_INT >= minSdk
}

/** Marketing name of the Android release with API level [api]. */
fun androidVersionName(api: Int): String = when (api) {
    in 0..7 -> "2.1"
    8 -> "2.2"
    31 -> "12"
    32 -> "12L"
    33 -> "13"
    34 -> "14"
    35 -> "15"
    36 -> "16"
    37 -> "17"
    else -> "API $api"
}
