package org.schabi.speachrecognitionservicetester.harness

import android.os.Bundle
import android.speech.SpeechRecognizer

/** One [android.speech.RecognitionListener] callback, as received by [RecognizerSession]. */
sealed interface RecognizerEvent {
    data class ReadyForSpeech(val params: Bundle) : RecognizerEvent
    data object BeginningOfSpeech : RecognizerEvent
    data class RmsChanged(val rmsdB: Float) : RecognizerEvent
    data class BufferReceived(val size: Int) : RecognizerEvent
    data object EndOfSpeech : RecognizerEvent
    data class Error(val code: Int) : RecognizerEvent
    data class Results(val bundle: Bundle) : RecognizerEvent
    data class PartialResults(val bundle: Bundle) : RecognizerEvent
    data class Event(val type: Int, val params: Bundle?) : RecognizerEvent

    /** API 33+. */
    data class SegmentResults(val bundle: Bundle) : RecognizerEvent

    /** API 33+. */
    data object EndOfSegmentedSession : RecognizerEvent

    /** API 34+. */
    data class LanguageDetection(val bundle: Bundle) : RecognizerEvent

    /** Ends a recognition session: no further callbacks are expected afterwards. */
    val isTerminal: Boolean
        get() = this is Results || this is Error || this is EndOfSegmentedSession
}

/** The hypotheses of a results bundle (`RESULTS_RECOGNITION`). */
val Bundle.recognitionResults: List<String>
    get() = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

/** The confidence scores of a results bundle (`CONFIDENCE_SCORES`), or `null` if absent. */
val Bundle.confidenceScores: FloatArray?
    get() = getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

/** Log line for [event]. */
fun describe(event: RecognizerEvent): String = when (event) {
    is RecognizerEvent.ReadyForSpeech -> "onReadyForSpeech"
    RecognizerEvent.BeginningOfSpeech -> "onBeginningOfSpeech"
    is RecognizerEvent.RmsChanged -> "onRmsChanged(%.1f dB)".format(event.rmsdB)
    is RecognizerEvent.BufferReceived -> "onBufferReceived(${event.size} bytes)"
    RecognizerEvent.EndOfSpeech -> "onEndOfSpeech"
    is RecognizerEvent.Error -> "onError(${errorName(event.code)})"
    is RecognizerEvent.Results -> "onResults${describeResults(event.bundle)}"
    is RecognizerEvent.PartialResults -> "onPartialResults${describeResults(event.bundle)}"
    is RecognizerEvent.Event -> "onEvent(${event.type})"
    is RecognizerEvent.SegmentResults -> "onSegmentResults${describeResults(event.bundle)}"
    RecognizerEvent.EndOfSegmentedSession -> "onEndOfSegmentedSession"
    is RecognizerEvent.LanguageDetection -> "onLanguageDetection(${describeBundle(event.bundle)})"
}

fun describeResults(bundle: Bundle): String {
    val scores = bundle.confidenceScores
    return if (scores == null) "(${bundle.recognitionResults})"
    else "(${bundle.recognitionResults}, confidence=${scores.toList()})"
}

@Suppress("DEPRECATION")
fun describeBundle(bundle: Bundle): String =
    bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" }

/** `SpeechRecognizer.ERROR_*` name of [code]. Literal values so older constants need no API check. */
fun errorName(code: Int): String = when (code) {
    1 -> "ERROR_NETWORK_TIMEOUT"
    2 -> "ERROR_NETWORK"
    3 -> "ERROR_AUDIO"
    4 -> "ERROR_SERVER"
    5 -> "ERROR_CLIENT"
    6 -> "ERROR_SPEECH_TIMEOUT"
    7 -> "ERROR_NO_MATCH"
    8 -> "ERROR_RECOGNIZER_BUSY"
    9 -> "ERROR_INSUFFICIENT_PERMISSIONS"
    10 -> "ERROR_TOO_MANY_REQUESTS"
    11 -> "ERROR_SERVER_DISCONNECTED"
    12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
    13 -> "ERROR_LANGUAGE_UNAVAILABLE"
    14 -> "ERROR_CANNOT_CHECK_SUPPORT"
    15 -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
    else -> "error $code"
} + " ($code)"
