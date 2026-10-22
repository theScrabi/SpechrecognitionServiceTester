package org.schabi.speachrecognitionservicetester.harness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

/**
 * What a test runs with: the target service, a log shown live in the test dialog, recognizer
 * sessions (destroyed by [close]) and the user's "Stop" button.
 */
class TestHarness(
    val context: Context,
    val target: RecognizerTarget?,
    private val logSink: (String) -> Unit,
) : Closeable {
    private val sessions = mutableListOf<RecognizerSession>()
    private val closeables = mutableListOf<Closeable>()
    private val userStops = Channel<Unit>(Channel.CONFLATED)

    /** For helpers outliving a single call (e.g. pipe writers); cancelled by [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val component: ComponentName
        get() = checkNotNull(target) { "No recognition service selected / installed" }.component

    private var closed = false

    /** Lines arriving after [close] (callbacks still queued for destroyed recognizers) are dropped. */
    fun log(message: String) {
        if (!closed) logSink(message)
    }

    fun newSession(name: String = "recognizer"): RecognizerSession =
        RecognizerSession(context, component, name, ::log).also { sessions += it }

    fun <T : Closeable> track(closeable: T): T = closeable.also { closeables += it }

    /** Called from the UI's "Stop" button. */
    fun userStop() {
        userStops.trySend(Unit)
    }

    /** Waits until the user presses "Stop"; false when [timeoutMs] passed first. */
    suspend fun awaitUserStop(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { userStops.receive() } != null

    /** A free-form `ACTION_RECOGNIZE_SPEECH` intent, customised by [extras]. */
    fun recognizerIntent(extras: Intent.() -> Unit = {}) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            extras()
        }

    override fun close() {
        closed = true
        sessions.forEach { runCatching { it.destroy() } }
        sessions.clear()
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
        scope.cancel()
    }
}
