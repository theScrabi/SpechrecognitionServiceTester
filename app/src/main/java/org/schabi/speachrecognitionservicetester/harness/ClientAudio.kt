package org.schabi.speachrecognitionservicetester.harness

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val RECORD_SAMPLE_RATE = 16_000

/** Records [durationMs] of 16 kHz mono PCM16 from the microphone. */
suspend fun recordPcm16(context: Context, durationMs: Int): ShortArray = withContext(Dispatchers.IO) {
    check(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    ) { "The tester has no RECORD_AUDIO permission" }
    val minBuffer = AudioRecord.getMinBufferSize(
        RECORD_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    @Suppress("MissingPermission")
    val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, RECORD_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 8192),
    )
    try {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord could not be initialized" }
        val samples = ShortArray(RECORD_SAMPLE_RATE * durationMs / 1000)
        record.startRecording()
        var offset = 0
        while (offset < samples.size) {
            ensureActive()
            val read = record.read(samples, offset, minOf(1600, samples.size - offset))
            check(read >= 0) { "AudioRecord.read failed: $read" }
            offset += read
        }
        record.stop()
        samples
    } finally {
        record.release()
    }
}

/** Little-endian PCM16 bytes of [samples], each frame repeated [upsample] times on [channels] channels. */
fun pcm16Bytes(samples: ShortArray, upsample: Int = 1, channels: Int = 1): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * upsample * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (sample in samples) repeat(upsample * channels) { buffer.putShort(sample) }
    return buffer.array()
}

/**
 * A pipe whose [readSide] goes into `EXTRA_AUDIO_SOURCE`; [bytes] are written into it on an IO
 * coroutine, then the write side is closed (end of input).
 */
class PipedAudio(scope: CoroutineScope, bytes: ByteArray) : Closeable {
    private val pipe = ParcelFileDescriptor.createPipe()
    val readSide: ParcelFileDescriptor = pipe[0]

    private val writer: Job = scope.launch(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            try {
                var offset = 0
                while (offset < bytes.size && isActive) {
                    val count = minOf(3200, bytes.size - offset)
                    out.write(bytes, offset, count)
                    offset += count
                }
            } catch (_: IOException) {
                // The service closed its end (e.g. rejected the format).
            }
        }
    }

    override fun close() {
        writer.cancel()
        readSide.close()
    }
}
