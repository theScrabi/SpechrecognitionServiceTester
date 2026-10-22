package org.schabi.speachrecognitionservicetester.harness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** The events [RecognizerSession.collectUntil] received; [completed] is false on timeout. */
data class Collected(val events: List<RecognizerEvent>, val completed: Boolean) {
    val last: RecognizerEvent? get() = events.lastOrNull()

    inline fun <reified T : RecognizerEvent> all(): List<T> = events.filterIsInstance<T>()
    inline fun <reified T : RecognizerEvent> first(): T? = events.firstNotNullOfOrNull { it as? T }
}

/**
 * A [SpeechRecognizer] bound to [target] whose [RecognitionListener] callbacks are queued as
 * [RecognizerEvent]s. Must be created and used on the main thread (the callbacks arrive there).
 *
 * @param log Receives one line per callback (only the first `onRmsChanged` is logged).
 */
class RecognizerSession(
    context: Context,
    target: ComponentName,
    private val name: String,
    private val log: (String) -> Unit,
) {
    val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, target)
    private val events = Channel<RecognizerEvent>(Channel.UNLIMITED)
    private var rmsLogged = false

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle) = emit(RecognizerEvent.ReadyForSpeech(params))
            override fun onBeginningOfSpeech() = emit(RecognizerEvent.BeginningOfSpeech)
            override fun onRmsChanged(rmsdB: Float) = emit(RecognizerEvent.RmsChanged(rmsdB))
            override fun onBufferReceived(buffer: ByteArray) = emit(RecognizerEvent.BufferReceived(buffer.size))
            override fun onEndOfSpeech() = emit(RecognizerEvent.EndOfSpeech)
            override fun onError(error: Int) = emit(RecognizerEvent.Error(error))
            override fun onResults(results: Bundle) = emit(RecognizerEvent.Results(results))
            override fun onPartialResults(partialResults: Bundle) = emit(RecognizerEvent.PartialResults(partialResults))
            override fun onEvent(eventType: Int, params: Bundle?) = emit(RecognizerEvent.Event(eventType, params))
            override fun onSegmentResults(segmentResults: Bundle) = emit(RecognizerEvent.SegmentResults(segmentResults))
            override fun onEndOfSegmentedSession() = emit(RecognizerEvent.EndOfSegmentedSession)
            override fun onLanguageDetection(results: Bundle) = emit(RecognizerEvent.LanguageDetection(results))
        })
    }

    private fun emit(event: RecognizerEvent) {
        if (event is RecognizerEvent.RmsChanged) {
            if (!rmsLogged) log("$name: ${describe(event)} (further RMS updates not logged)")
            rmsLogged = true
        } else {
            log("$name: ${describe(event)}")
        }
        events.trySend(event)
    }

    fun startListening(intent: Intent) {
        log("$name: startListening()")
        recognizer.startListening(intent)
    }

    fun stopListening() {
        log("$name: stopListening()")
        recognizer.stopListening()
    }

    fun cancel() {
        log("$name: cancel()")
        recognizer.cancel()
    }

    /** Waits up to [timeoutMs] for an event matching [predicate]; `null` on timeout. */
    suspend fun await(timeoutMs: Long, predicate: (RecognizerEvent) -> Boolean): RecognizerEvent? =
        withTimeoutOrNull(timeoutMs) {
            var event: RecognizerEvent
            do {
                event = events.receive()
            } while (!predicate(event))
            event
        }

    /** Collects every event until one matches [stop] (included) or [timeoutMs] passes. */
    suspend fun collectUntil(
        timeoutMs: Long,
        stop: (RecognizerEvent) -> Boolean = { it.isTerminal },
    ): Collected {
        val collected = mutableListOf<RecognizerEvent>()
        val completed = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val event = events.receive()
                collected += event
                if (stop(event)) break
            }
            true
        } ?: false
        return Collected(collected, completed)
    }

    fun destroy() {
        recognizer.destroy()
        events.close()
    }
}
