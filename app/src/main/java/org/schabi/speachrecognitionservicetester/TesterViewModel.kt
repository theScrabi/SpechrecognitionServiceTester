package org.schabi.speachrecognitionservicetester

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.speachrecognitionservicetester.harness.RecognizerTarget
import org.schabi.speachrecognitionservicetester.harness.TargetServices
import org.schabi.speachrecognitionservicetester.harness.TestHarness
import org.schabi.speachrecognitionservicetester.model.TEST_CATALOG
import org.schabi.speachrecognitionservicetester.model.TestCase
import org.schabi.speachrecognitionservicetester.model.TestOutcome
import org.schabi.speachrecognitionservicetester.model.TestResult
import org.schabi.speachrecognitionservicetester.model.TestStatus

/** Progress of [TesterViewModel.runAll]: test [index] of [total] is [testId]. */
data class RunAllState(
    val index: Int,
    val total: Int,
    val testId: String,
    val waitingForUser: Boolean,
    val finished: Boolean = false,
)

/** Runs the [TEST_CATALOG] against the selected recognition service, one test at a time. */
class TesterViewModel(application: Application) : AndroidViewModel(application) {
    val tests: List<TestCase> = TEST_CATALOG

    private val _results = MutableStateFlow(initialResults())
    val results: StateFlow<Map<String, TestResult>> = _results.asStateFlow()

    private val _runningId = MutableStateFlow<String?>(null)
    val runningId: StateFlow<String?> = _runningId.asStateFlow()

    private val _targets = MutableStateFlow<List<RecognizerTarget>>(emptyList())
    val targets: StateFlow<List<RecognizerTarget>> = _targets.asStateFlow()

    private val _selectedTarget = MutableStateFlow<RecognizerTarget?>(null)
    val selectedTarget: StateFlow<RecognizerTarget?> = _selectedTarget.asStateFlow()

    private val _runAll = MutableStateFlow<RunAllState?>(null)
    val runAll: StateFlow<RunAllState?> = _runAll.asStateFlow()

    private var job: Job? = null
    private var testJob: Job? = null
    private val userDecisions = Channel<Boolean>(Channel.CONFLATED)
    private var harness: TestHarness? = null

    init {
        refreshTargets()
    }

    private fun initialResults() = tests.associate {
        it.id to TestResult(if (it.isSupportedOnDevice) TestStatus.NotTested else TestStatus.Unsupported)
    }

    /** Re-queries the installed services; keeps the selection, else defaults to Parakeetype. */
    fun refreshTargets() {
        val found = TargetServices.query(getApplication())
        _targets.value = found
        val selected = _selectedTarget.value?.component
        _selectedTarget.value = found.firstOrNull { it.component == selected }
            ?: found.firstOrNull { it.component == TargetServices.PARAKEETYPE }
    }

    fun selectTarget(target: RecognizerTarget) {
        if (target.component == _selectedTarget.value?.component || _runningId.value != null) return
        _selectedTarget.value = target
        _results.value = initialResults()
    }

    fun run(test: TestCase) {
        if (job?.isActive == true || !test.isSupportedOnDevice) return
        job = viewModelScope.launch { execute(test) }
    }

    /**
     * Runs every test supported on this phone in order. Before a test that needs the user (it has
     * instructions) it waits for [startNext] or [skipNext].
     */
    fun runAll() {
        if (job?.isActive == true) return
        val queue = tests.filter { it.isSupportedOnDevice }
        job = viewModelScope.launch {
            try {
                for ((index, test) in queue.withIndex()) {
                    val needsUser = test.instructions != null
                    _runAll.value = RunAllState(index, queue.size, test.id, waitingForUser = needsUser)
                    if (needsUser) {
                        while (userDecisions.tryReceive().isSuccess) Unit // drop stale clicks
                        if (!userDecisions.receive()) continue
                        _runAll.update { it?.copy(waitingForUser = false) }
                    }
                    // A skipped test cancels only this child job, not the whole run.
                    coroutineScope { testJob = launch { execute(test) } }
                    testJob = null
                }
                _runAll.update { it?.copy(finished = true) }
            } catch (e: CancellationException) {
                _runAll.value = null
                throw e
            }
        }
    }

    /** Run-all: starts the test waiting for the user. */
    fun startNext() {
        userDecisions.trySend(true)
    }

    /** Run-all: skips the test waiting for the user, or cancels the one running. */
    fun skipNext() {
        val running = testJob
        if (running != null) running.cancel() else userDecisions.trySend(false)
    }

    fun closeRunAll() {
        if (_runAll.value?.finished == true) _runAll.value = null
    }

    private suspend fun execute(test: TestCase) {
        val started = SystemClock.elapsedRealtime()
        update(test.id) { TestResult(TestStatus.Running) }
        _runningId.value = test.id
        val harness = TestHarness(getApplication(), _selectedTarget.value) { line ->
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            update(test.id) { it.copy(log = it.log + "[%5.1fs] %s".format(seconds, line)) }
        }
        this.harness = harness
        try {
            val outcome = try {
                test.run(harness)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestOutcome(false, "${e::class.simpleName}: ${e.message}")
            }
            harness.log((if (outcome.passed) "✔ PASSED: " else "✘ FAILED: ") + outcome.detail)
            update(test.id) {
                it.copy(status = if (outcome.passed) TestStatus.Passed else TestStatus.Failed, detail = outcome.detail)
            }
        } catch (e: CancellationException) {
            harness.log("Cancelled")
            update(test.id) { it.copy(status = TestStatus.NotTested, detail = "Cancelled") }
            throw e
        } finally {
            harness.close()
            this.harness = null
            _runningId.value = null
        }
    }

    /** The dialog's "Stop" button. */
    fun userStop() {
        harness?.userStop()
    }

    /** Aborts the single test, or the whole run-all. */
    fun abort() {
        job?.cancel()
    }

    private fun update(id: String, transform: (TestResult) -> TestResult) {
        _results.update { it + (id to transform(it.getValue(id))) }
    }
}
