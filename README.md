# SpeechRecognitionService Tester

An Android app that checks how well an installed speech recognizer, an implementation of
[`android.speech.RecognitionService`](https://developer.android.com/reference/android/speech/RecognitionService),
works for other apps that use it.

It was written to test [Parakeetype](../parakeetype)'s on-device recognizer. Because it only talks to the
service through the public `SpeechRecognizer` API, it works with any recognizer installed on the phone.

## Why

A `RecognitionService` is used by other apps through `SpeechRecognizer`, `RecognitionListener` and the
`RecognizerIntent` extras. That interface has grown over the years: segmented sessions, client-supplied
audio and support checks arrived in Android 13 (API 33); language detection, word timing and download
listeners in Android 14 (API 34). A recognizer can look fine in its own app and still misbehave for
other apps, for example by never reporting partial results, ignoring `stopListening()` or hanging on
unsupported audio.

This app acts as such a client app. It calls each part of the interface and shows which ones the
service handles correctly.

## What it shows

One card per function:

| Card | Meaning |
|------|---------|
| White, empty circle | Not tested yet |
| Green, ✔ | Test passed |
| Red, ✘ | Test failed; the card shows why |
| Gray, 🔒 | Not testable on this phone: the function needs a newer Android version (the card names the required API level) |

Tap a card to see what the test checks and what you have to do (e.g. speak a sentence), then start it.
While it runs, the dialog shows a live log of every callback the service sends.

## Tests

| # | Test | Needs | Checks |
|---|------|-------|--------|
| 1 | Service discovery | all APIs | Service is found, exported, has `android.speech` meta-data / `settingsActivity` |
| 2 | `isRecognitionAvailable` | all APIs | Returns true |
| 3 | Bind & start session | all APIs | `createSpeechRecognizer(ctx, component)` + `startListening` → `onReadyForSpeech` |
| 4 | Basic recognition | all APIs | Full callback sequence and non-empty `RESULTS_RECOGNITION` |
| 5 | Confidence scores | all APIs | `CONFIDENCE_SCORES`, one per hypothesis |
| 6 | Partial results | all APIs | `EXTRA_PARTIAL_RESULTS` → `onPartialResults` |
| 7 | `stopListening()` | all APIs | Results are still delivered after stopping mid-speech |
| 8 | `cancel()` | all APIs | No results/errors after cancelling |
| 9 | Concurrent session | all APIs | Second session → `ERROR_RECOGNIZER_BUSY` (or real concurrency) |
| 10 | Silence → timeout | all APIs | `ERROR_SPEECH_TIMEOUT` / `ERROR_NO_MATCH` instead of listening forever |
| 11 | Silence length extra | all APIs | `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` is honoured |
| 12 | `EXTRA_LANGUAGE` (de-DE) | all APIs | Results or a proper language error |
| 13 | `onBufferReceived` | all APIs | Optional raw-audio callback |
| 14 | `checkRecognitionSupport` | API 33 | Installed / supported / online languages |
| 15 | `triggerModelDownload` | API 33 | Call is accepted without error |
| 16 | Segmented session | API 33 | `onSegmentResults` + `onEndOfSegmentedSession` |
| 17 | Client audio 16 kHz mono | API 33 | `EXTRA_AUDIO_SOURCE`: the app records audio itself and pipes it to the service |
| 18 | Client audio 48 kHz stereo | API 33 | Same, but the service has to convert the format |
| 19 | Client audio, unsupported format | API 33 | Rejected with `ERROR_CLIENT` |
| 20 | `triggerModelDownload` + listener | API 34 | `ModelDownloadListener` gets progress/success/scheduled or a meaningful error |
| 21 | Language detection | API 34 | `EXTRA_ENABLE_LANGUAGE_DETECTION` → `onLanguageDetection` |
| 22 | Language switch | API 34 | `EXTRA_ENABLE_LANGUAGE_SWITCH` → `LANGUAGE_SWITCH_RESULT` |
| 23 | Word timing & confidence | API 34 | `RECOGNITION_PARTS` in the results |

Some tests check optional functions (13, 20–23). A red card there means the service doesn't
offer that function, not necessarily that it is broken.

## Features

- **Service picker**: lists every installed recognition service; Parakeetype is selected by default.
- **Run all** (▶ in the top bar): runs every test this phone supports, in order. Tests that need no
  user action run on their own. Before a test that needs you (speaking, staying silent, pressing
  Stop), it pauses and shows the instructions with *Start* / *Skip*. *Abort all* stops the run.
- **Markdown export** (share icon in the top bar): *Share as Markdown*, or *Save as .md file*, writes a
  report with the device, Android version, service and version, a summary table, and every test's
  result and log.

## Building and running

Requirements: Android Studio / AGP 9.4, compileSdk 37. The app runs on Android 12 (API 31) and newer,
the same minimum as Parakeetype.

```sh
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run the `app` configuration.

## Before testing

- Grant the tester the **microphone** permission (it asks on start). Apps using `SpeechRecognizer`
  must hold `RECORD_AUDIO`.
- The **recognizer itself** also needs whatever it requires to work. For Parakeetype: a model
  imported in its app and the microphone permission granted to Parakeetype. Without them the service
  correctly answers with `ERROR_LANGUAGE_UNAVAILABLE` or `ERROR_INSUFFICIENT_PERMISSIONS`.
- Most tests need a real voice. On an emulator, enable host audio input for the virtual microphone in the
  emulator's extended controls.
