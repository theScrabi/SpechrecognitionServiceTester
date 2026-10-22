package org.schabi.speachrecognitionservicetester.model

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionPart
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.speachrecognitionservicetester.harness.Collected
import org.schabi.speachrecognitionservicetester.harness.PipedAudio
import org.schabi.speachrecognitionservicetester.harness.RECORD_SAMPLE_RATE
import org.schabi.speachrecognitionservicetester.harness.RecognizerEvent
import org.schabi.speachrecognitionservicetester.harness.TestHarness
import org.schabi.speachrecognitionservicetester.harness.confidenceScores
import org.schabi.speachrecognitionservicetester.harness.describe
import org.schabi.speachrecognitionservicetester.harness.describeBundle
import org.schabi.speachrecognitionservicetester.harness.errorName
import org.schabi.speachrecognitionservicetester.harness.pcm16Bytes
import org.schabi.speachrecognitionservicetester.harness.recognitionResults
import org.schabi.speachrecognitionservicetester.harness.recordPcm16

private const val SPEAK =
    "Press Start, wait for onReadyForSpeech in the log, then say a sentence such as " +
            "\"The quick brown fox jumps over the lazy dog\"."

/** Every functionality of the RecognitionService interface, in API-level order. */
val TEST_CATALOG: List<TestCase> = listOf(
    TestCase(
        id = "discovery",
        title = "Service discovery",
        description = "Resolves the android.speech.RecognitionService intent and reads the service's " +
                "android.speech meta-data (recognition-service, settingsActivity).",
        minSdk = 8,
    ) {
        val t = target ?: return@TestCase fail("No RecognitionService found - is Parakeetype installed?")
        log("Found ${t.component.flattenToShortString()} (\"${t.label}\")")
        log("exported=${t.exported}, meta-data=${t.hasMetaData}, settingsActivity=${t.settingsActivity}")
        when {
            !t.exported -> fail("Service is not exported, other apps can't bind it")
            !t.hasMetaData -> fail("No android.speech meta-data")
            t.settingsActivity.isNullOrEmpty() -> pass("Found; meta-data has no settingsActivity")
            else -> pass("Found; settingsActivity=${t.settingsActivity}")
        }
    },
    TestCase(
        id = "available",
        title = "isRecognitionAvailable",
        description = "SpeechRecognizer.isRecognitionAvailable(): reports whether any recognition " +
                "service is installed.",
        minSdk = 8,
    ) {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        log("isRecognitionAvailable = $available")
        if (available) pass("true") else fail("false")
    },
    TestCase(
        id = "bind",
        title = "Bind & start session",
        description = "createSpeechRecognizer(context, component) binds the service; startListening() " +
                "must lead to onReadyForSpeech. The session is cancelled right after.",
        minSdk = 8,
    ) {
        val session = newSession()
        session.startListening(recognizerIntent())
        val event = session.await(15_000) { it is RecognizerEvent.ReadyForSpeech || it.isTerminal }
        session.cancel()
        when (event) {
            null -> fail("No onReadyForSpeech within 15 s")
            is RecognizerEvent.ReadyForSpeech -> pass("Bound, onReadyForSpeech received")
            else -> fail("Got ${describe(event)} instead of onReadyForSpeech")
        }
    },
    TestCase(
        id = "basic",
        title = "Basic recognition",
        description = "startListening() with a free-form intent. Expects onReadyForSpeech, " +
                "onBeginningOfSpeech, onRmsChanged, onEndOfSpeech and onResults with a non-empty " +
                "RESULTS_RECOGNITION.",
        minSdk = 8,
        instructions = SPEAK,
    ) {
        val collected = listen(recognizerIntent())
        val results = collected.resultsOrFailure().let { it.second ?: return@TestCase it.first!! }
        val missing = listOf(
            "onReadyForSpeech" to collected.first<RecognizerEvent.ReadyForSpeech>(),
            "onBeginningOfSpeech" to collected.first<RecognizerEvent.BeginningOfSpeech>(),
            "onRmsChanged" to collected.first<RecognizerEvent.RmsChanged>(),
            "onEndOfSpeech" to collected.first<RecognizerEvent.EndOfSpeech>(),
        ).filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) fail("Got \"${results.first()}\" but no ${missing.joinToString()}")
        else pass("\"${results.first()}\"")
    },
    TestCase(
        id = "confidence",
        title = "Confidence scores",
        description = "onResults must carry CONFIDENCE_SCORES with one score per hypothesis.",
        minSdk = 8,
        instructions = SPEAK,
    ) {
        val collected = listen(recognizerIntent { putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) })
        collected.resultsOrFailure().first?.let { return@TestCase it }
        val bundle = (collected.last as RecognizerEvent.Results).bundle
        val results = bundle.recognitionResults
        val scores = bundle.confidenceScores
        when {
            scores == null -> fail("No CONFIDENCE_SCORES in the results")
            scores.size != results.size -> fail("${scores.size} scores for ${results.size} hypotheses")
            else -> pass("Scores ${scores.toList()} for ${results.size} hypothesis(es)")
        }
    },
    TestCase(
        id = "partial",
        title = "Partial results",
        description = "EXTRA_PARTIAL_RESULTS = true: expects at least one onPartialResults before onResults.",
        minSdk = 8,
        instructions = "Press Start and say a long sentence (several seconds) so the service has time " +
                "to report partial results.",
    ) {
        val collected = listen(recognizerIntent { putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) })
        collected.resultsOrFailure().first?.let { return@TestCase it }
        val partials = collected.all<RecognizerEvent.PartialResults>()
        if (partials.isEmpty()) fail("onResults arrived without any onPartialResults")
        else pass("${partials.size} partial result(s), last: ${partials.last().bundle.recognitionResults}")
    },
    TestCase(
        id = "stop",
        title = "stopListening()",
        description = "Calls stopListening() 2 s after onBeginningOfSpeech while the user is still " +
                "talking. The service must still deliver onResults for the audio so far.",
        minSdk = 8,
        instructions = "Press Start and talk continuously without pausing until the test finishes; " +
                "it stops listening for you.",
    ) {
        val session = newSession()
        session.startListening(recognizerIntent())
        val begin = session.await(15_000) { it is RecognizerEvent.BeginningOfSpeech || it.isTerminal }
            ?: return@TestCase fail("No onBeginningOfSpeech within 15 s")
        if (begin.isTerminal) return@TestCase fail("Session ended before speech: ${describe(begin)}")
        val early = session.collectUntil(2_000)
        if (early.completed) {
            return@TestCase fail("Session ended on its own before stopListening() - keep talking. (${describe(early.last!!)})")
        }
        session.stopListening()
        val collected = session.collectUntil(20_000)
        collected.resultsOrFailure().let { (failure, results) ->
            failure ?: pass("onResults after stopListening(): \"${results!!.first()}\"")
        }
    },
    TestCase(
        id = "cancel",
        title = "cancel()",
        description = "Calls cancel() 1 s into a session. Afterwards no onResults or onError may arrive " +
                "(checked for 5 s).",
        minSdk = 8,
        instructions = "Press Start and talk continuously; the test cancels after 1 s.",
    ) {
        val session = newSession()
        session.startListening(recognizerIntent())
        val ready = session.await(15_000) { it is RecognizerEvent.ReadyForSpeech || it.isTerminal }
            ?: return@TestCase fail("No onReadyForSpeech within 15 s")
        if (ready.isTerminal) return@TestCase fail("Session ended early: ${describe(ready)}")
        val early = session.collectUntil(1_000)
        if (early.completed) return@TestCase fail("Session ended before cancel(): ${describe(early.last!!)}")
        session.cancel()
        val after = session.collectUntil(5_000) { it is RecognizerEvent.Results || it is RecognizerEvent.Error }
        if (after.completed) fail("Got ${describe(after.last!!)} after cancel()")
        else pass("No onResults/onError within 5 s after cancel()")
    },
    TestCase(
        id = "busy",
        title = "Concurrent session (busy)",
        description = "Starts a second SpeechRecognizer while the first is listening. The service must " +
                "either reject it with ERROR_RECOGNIZER_BUSY or really run both (getMaxConcurrentSessionsCount > 1).",
        minSdk = 8,
    ) {
        val first = newSession("recognizer 1")
        first.startListening(recognizerIntent())
        val ready = first.await(15_000) { it is RecognizerEvent.ReadyForSpeech || it.isTerminal }
            ?: return@TestCase fail("First session: no onReadyForSpeech within 15 s")
        if (ready.isTerminal) return@TestCase fail("First session ended early: ${describe(ready)}")
        val second = newSession("recognizer 2")
        second.startListening(recognizerIntent())
        val event = second.await(10_000) { it is RecognizerEvent.ReadyForSpeech || it.isTerminal }
        first.cancel()
        second.cancel()
        when {
            event == null -> fail("Second session got no response within 10 s")
            event is RecognizerEvent.Error && event.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                pass("Second session rejected with ERROR_RECOGNIZER_BUSY")
            event is RecognizerEvent.ReadyForSpeech -> pass("Service ran a second session concurrently")
            else -> fail("Second session: ${describe(event)}")
        }
    },
    TestCase(
        id = "silence",
        title = "Silence → timeout",
        description = "Nobody speaks. The service must end the session with ERROR_SPEECH_TIMEOUT or " +
                "ERROR_NO_MATCH instead of listening forever.",
        minSdk = 8,
        instructions = "Press Start and stay completely silent (mute the room) until the test finishes.",
    ) {
        val collected = listen(recognizerIntent(), timeoutMs = 40_000)
        val last = collected.last
        when {
            !collected.completed -> fail("Still listening after 40 s of silence")
            last is RecognizerEvent.Error && last.code in listOf(
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH,
            ) -> pass(errorName(last.code))
            last is RecognizerEvent.Results -> fail("Got results although nobody spoke: ${last.bundle.recognitionResults}")
            else -> fail("Unexpected ${describe(last!!)}")
        }
    },
    TestCase(
        id = "silenceLength",
        title = "Silence length extra",
        description = "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 4000: after a short word the " +
                "service must wait ~4 s of silence before ending the utterance.",
        minSdk = 8,
        instructions = "Press Start, say ONE short word (e.g. \"hello\"), then stay silent.",
    ) {
        val session = newSession()
        session.startListening(recognizerIntent {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        })
        val begin = session.await(15_000) { it is RecognizerEvent.BeginningOfSpeech || it.isTerminal }
            ?: return@TestCase fail("No onBeginningOfSpeech within 15 s")
        if (begin.isTerminal) return@TestCase fail("Session ended before speech: ${describe(begin)}")
        val start = SystemClock.elapsedRealtime()
        var end = 0L
        val collected = session.collectUntil(30_000) {
            if (it is RecognizerEvent.EndOfSpeech && end == 0L) end = SystemClock.elapsedRealtime()
            it.isTerminal
        }
        if (end == 0L) end = SystemClock.elapsedRealtime()
        collected.resultsOrFailure().first?.let { return@TestCase it }
        val elapsed = end - start
        log("Speech start → end of utterance: $elapsed ms")
        if (elapsed >= 4_000) pass("Utterance ended after $elapsed ms (≥ 4 s)")
        else fail("Utterance ended after $elapsed ms, the 4 s silence length was ignored")
    },
    TestCase(
        id = "language",
        title = "EXTRA_LANGUAGE (de-DE)",
        description = "Requests German with EXTRA_LANGUAGE = \"de-DE\". The service must either recognise " +
                "it or reject it with a language error.",
        minSdk = 8,
        instructions = "Press Start and say a German sentence, e.g. \"Guten Morgen, wie geht es dir?\".",
    ) {
        val collected = listen(recognizerIntent { putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE") })
        collected.resultsOrFailure().let { (failure, results) -> failure ?: pass("\"${results!!.first()}\"") }
    },
    TestCase(
        id = "buffer",
        title = "onBufferReceived",
        description = "Optional callback: the service may hand the recorded audio to the client via " +
                "onBufferReceived during a session.",
        minSdk = 8,
        instructions = SPEAK,
    ) {
        val collected = listen(recognizerIntent())
        val buffers = collected.all<RecognizerEvent.BufferReceived>()
        if (buffers.isNotEmpty()) pass("${buffers.size} buffer(s), ${buffers.sumOf { it.size }} bytes")
        else fail("Service never called onBufferReceived (optional callback)")
    },
    TestCase(
        id = "support",
        title = "checkRecognitionSupport",
        description = "SpeechRecognizer.checkRecognitionSupport(): the service reports installed, pending, " +
                "supported and online languages via onCheckRecognitionSupport.",
        minSdk = 33,
    ) {
        if (Build.VERSION.SDK_INT >= 33) checkSupport() else fail("Requires API 33")
    },
    TestCase(
        id = "download",
        title = "triggerModelDownload",
        description = "SpeechRecognizer.triggerModelDownload(intent): asks the service to download the " +
                "model. Passes if the call is accepted without an error.",
        minSdk = 33,
    ) {
        if (Build.VERSION.SDK_INT >= 33) triggerDownload() else fail("Requires API 33")
    },
    TestCase(
        id = "segmented",
        title = "Segmented session",
        description = "EXTRA_SEGMENTED_SESSION: the service delivers every utterance via onSegmentResults " +
                "and ends with onEndOfSegmentedSession after stopListening().",
        minSdk = 33,
        instructions = "Press Start, say two or three sentences with short pauses in between, then " +
                "press Stop (auto-stop after 45 s).",
        needsUserStop = true,
    ) {
        if (Build.VERSION.SDK_INT >= 33) segmentedSession() else fail("Requires API 33")
    },
    TestCase(
        id = "audioSource",
        title = "Client audio (16 kHz mono)",
        description = "EXTRA_AUDIO_SOURCE: the tester records 5 s itself and pipes the PCM16 16 kHz mono " +
                "audio to the service, which must transcribe it.",
        minSdk = 33,
        instructions = "Press Start and speak a sentence during the 5 s recording.",
    ) {
        if (Build.VERSION.SDK_INT >= 33) clientAudio(upsample = 1, channels = 1) else fail("Requires API 33")
    },
    TestCase(
        id = "audioSourceResampled",
        title = "Client audio (48 kHz stereo)",
        description = "EXTRA_AUDIO_SOURCE with EXTRA_AUDIO_SOURCE_SAMPLING_RATE = 48000 and " +
                "EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = 2: the service must convert the format itself.",
        minSdk = 33,
        instructions = "Press Start and speak a sentence during the 5 s recording.",
    ) {
        if (Build.VERSION.SDK_INT >= 33) clientAudio(upsample = 3, channels = 2) else fail("Requires API 33")
    },
    TestCase(
        id = "audioSourceUnsupported",
        title = "Client audio (unsupported format)",
        description = "EXTRA_AUDIO_SOURCE_ENCODING = ENCODING_MP3: the service must reject the format " +
                "with ERROR_CLIENT instead of hanging or crashing.",
        minSdk = 33,
    ) {
        if (Build.VERSION.SDK_INT >= 33) unsupportedClientAudio() else fail("Requires API 33")
    },
    TestCase(
        id = "downloadListener",
        title = "triggerModelDownload + listener",
        description = "triggerModelDownload(intent, executor, ModelDownloadListener): the service must " +
                "report onProgress, onScheduled, onSuccess or a meaningful onError.",
        minSdk = 34,
    ) {
        if (Build.VERSION.SDK_INT >= 34) downloadWithListener() else fail("Requires API 34")
    },
    TestCase(
        id = "languageDetection",
        title = "Language detection",
        description = "EXTRA_ENABLE_LANGUAGE_DETECTION (allowed: en-US, de-DE): expects onLanguageDetection " +
                "with DETECTED_LANGUAGE.",
        minSdk = 34,
        instructions = SPEAK,
    ) {
        if (Build.VERSION.SDK_INT >= 34) languageDetection(switch = false) else fail("Requires API 34")
    },
    TestCase(
        id = "languageSwitch",
        title = "Language switch",
        description = "EXTRA_ENABLE_LANGUAGE_SWITCH = balanced: expects onLanguageDetection with a " +
                "LANGUAGE_SWITCH_RESULT when the spoken language changes.",
        minSdk = 34,
        instructions = "Press Start, say a sentence in English and continue in German without a pause.",
    ) {
        if (Build.VERSION.SDK_INT >= 34) languageDetection(switch = true) else fail("Requires API 34")
    },
    TestCase(
        id = "wordTiming",
        title = "Word timing & confidence",
        description = "EXTRA_REQUEST_WORD_TIMING and EXTRA_REQUEST_WORD_CONFIDENCE: expects RECOGNITION_PARTS " +
                "(RecognitionPart list) in onResults.",
        minSdk = 34,
        instructions = SPEAK,
    ) {
        if (Build.VERSION.SDK_INT >= 34) wordTiming() else fail("Requires API 34")
    },
)

/** Runs one session with [intent] until onResults/onError. */
private suspend fun TestHarness.listen(intent: Intent, timeoutMs: Long = 30_000): Collected {
    val session = newSession()
    session.startListening(intent)
    return session.collectUntil(timeoutMs)
}

/**
 * The hypotheses of a completed session, or the failure explaining why there are none:
 * exactly one of the pair is non-null.
 */
private fun Collected.resultsOrFailure(): Pair<TestOutcome?, List<String>?> {
    val last = last
    return when {
        !completed -> fail("Timed out waiting for onResults" + (last?.let { "; last event: ${describe(it)}" } ?: "")) to null
        last is RecognizerEvent.Error -> fail("Service reported ${errorName(last.code)}") to null
        last !is RecognizerEvent.Results -> fail("Session ended with ${describe(last!!)} instead of onResults") to null
        last.bundle.recognitionResults.none { it.isNotBlank() } -> fail("onResults with empty RESULTS_RECOGNITION") to null
        else -> null to last.bundle.recognitionResults
    }
}

@RequiresApi(33)
private suspend fun TestHarness.checkSupport(): TestOutcome {
    val session = newSession()
    val answer = CompletableDeferred<Any>()
    log("checkRecognitionSupport()")
    session.recognizer.checkRecognitionSupport(
        recognizerIntent(), context.mainExecutor,
        object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                answer.complete(recognitionSupport)
            }

            override fun onError(error: Int) {
                answer.complete(error)
            }
        },
    )
    return when (val result = withTimeoutOrNull(10_000) { answer.await() }) {
        null -> fail("No callback within 10 s")
        is Int -> fail("onError(${errorName(result)})")
        is RecognitionSupport -> {
            log("installed: ${result.installedOnDeviceLanguages}")
            log("pending:   ${result.pendingOnDeviceLanguages}")
            log("supported: ${result.supportedOnDeviceLanguages}")
            log("online:    ${result.onlineLanguages}")
            val languages = result.installedOnDeviceLanguages + result.pendingOnDeviceLanguages +
                    result.supportedOnDeviceLanguages + result.onlineLanguages
            if (languages.isEmpty()) fail("onSupportResult without any language")
            else pass(
                "installed ${result.installedOnDeviceLanguages.size}, supported " +
                        "${result.supportedOnDeviceLanguages.size}, online ${result.onlineLanguages.size} languages"
            )
        }
        else -> fail("Unexpected answer $result")
    }
}

@RequiresApi(33)
private suspend fun TestHarness.triggerDownload(): TestOutcome {
    val session = newSession()
    log("triggerModelDownload()")
    session.recognizer.triggerModelDownload(recognizerIntent { putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") })
    val error = session.await(3_000) { it is RecognizerEvent.Error } as RecognizerEvent.Error?
    return if (error != null) fail("onError(${errorName(error.code)})")
    else pass("Accepted, no error within 3 s (this overload reports nothing else)")
}

@RequiresApi(33)
private suspend fun TestHarness.segmentedSession(): TestOutcome {
    val session = newSession()
    session.startListening(recognizerIntent {
        // Segments end on silence; without a silence-length extra the session runs until stopped.
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
    })
    log("Speak several sentences, then press Stop")
    if (!awaitUserStop(45_000)) log("No Stop within 45 s - stopping automatically")
    session.stopListening()
    val collected = session.collectUntil(20_000)
    val segments = collected.all<RecognizerEvent.SegmentResults>()
    val last = collected.last
    return when {
        !collected.completed -> fail("No onEndOfSegmentedSession within 20 s after stopListening() (${segments.size} segment(s))")
        last is RecognizerEvent.Error -> fail("Service reported ${errorName(last.code)} after ${segments.size} segment(s)")
        last is RecognizerEvent.Results -> fail("Got onResults instead of onSegmentResults/onEndOfSegmentedSession")
        segments.isEmpty() -> fail("onEndOfSegmentedSession without any onSegmentResults")
        else -> pass("${segments.size} segment(s): " + segments.joinToString(" | ") { it.bundle.recognitionResults.firstOrNull().orEmpty() })
    }
}

@RequiresApi(33)
private suspend fun TestHarness.clientAudio(upsample: Int, channels: Int): TestOutcome {
    val rate = RECORD_SAMPLE_RATE * upsample
    log("● Recording 5 s - speak now")
    val samples = recordPcm16(context, 5_000)
    log("Recording done; piping PCM16 $rate Hz, $channels ch to the service")
    val audio = track(PipedAudio(scope, pcm16Bytes(samples, upsample, channels)))
    val collected = listen(recognizerIntent {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio.readSide)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
    })
    return collected.resultsOrFailure().let { (failure, results) -> failure ?: pass("\"${results!!.first()}\"") }
}

@RequiresApi(33)
private suspend fun TestHarness.unsupportedClientAudio(): TestOutcome {
    val audio = track(PipedAudio(scope, ByteArray(16_000)))
    val collected = listen(recognizerIntent {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio.readSide)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_MP3)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RECORD_SAMPLE_RATE)
    }, timeoutMs = 15_000)
    val last = collected.last
    return when {
        !collected.completed -> fail("No error within 15 s")
        last is RecognizerEvent.Error && last.code == SpeechRecognizer.ERROR_CLIENT -> pass("Rejected with ERROR_CLIENT")
        else -> fail("Expected ERROR_CLIENT, got ${describe(last!!)}")
    }
}

@RequiresApi(34)
private suspend fun TestHarness.downloadWithListener(): TestOutcome {
    val session = newSession()
    val answer = CompletableDeferred<TestOutcome>()
    log("triggerModelDownload(intent, executor, listener)")
    session.recognizer.triggerModelDownload(
        recognizerIntent { putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") }, context.mainExecutor,
        object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
                log("onProgress($completedPercent)")
                answer.complete(pass("onProgress($completedPercent)"))
            }

            override fun onSuccess() {
                log("onSuccess")
                answer.complete(pass("onSuccess"))
            }

            override fun onScheduled() {
                log("onScheduled")
                answer.complete(pass("onScheduled"))
            }

            override fun onError(error: Int) {
                log("onError(${errorName(error)})")
                answer.complete(fail("onError(${errorName(error)})"))
            }
        },
    )
    return withTimeoutOrNull(15_000) { answer.await() } ?: fail("No listener callback within 15 s")
}

@RequiresApi(34)
private suspend fun TestHarness.languageDetection(switch: Boolean): TestOutcome {
    val languages = arrayListOf("en-US", "de-DE")
    val collected = listen(recognizerIntent {
        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, languages)
        if (switch) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, languages)
        }
    }, timeoutMs = 40_000)
    val detections: List<Bundle> = collected.all<RecognizerEvent.LanguageDetection>().map { it.bundle }
    val relevant = if (switch) detections.filter { it.containsKey(SpeechRecognizer.LANGUAGE_SWITCH_RESULT) } else detections
    val last = collected.last
    return when {
        relevant.isNotEmpty() -> pass(describeBundle(relevant.last()))
        last is RecognizerEvent.Error -> fail("Service reported ${errorName(last.code)}")
        !collected.completed -> fail("Timed out without onLanguageDetection")
        switch && detections.isNotEmpty() -> fail("onLanguageDetection without LANGUAGE_SWITCH_RESULT")
        else -> fail("Session ended without onLanguageDetection")
    }
}

@RequiresApi(34)
private suspend fun TestHarness.wordTiming(): TestOutcome {
    val collected = listen(recognizerIntent {
        putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
        putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true)
    })
    collected.resultsOrFailure().first?.let { return it }
    val bundle = (collected.last as RecognizerEvent.Results).bundle
    val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
    if (parts.isNullOrEmpty()) return fail("onResults has no RECOGNITION_PARTS")
    parts.forEach { log("part \"${it.rawText}\" @${it.timestampMillis} ms, confidence=${it.confidenceLevel}") }
    return pass("${parts.size} part(s): " + parts.take(5).joinToString { "${it.rawText}@${it.timestampMillis}ms" })
}
