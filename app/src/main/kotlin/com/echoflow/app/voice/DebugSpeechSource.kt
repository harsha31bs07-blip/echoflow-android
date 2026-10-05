package com.echoflow.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import com.echoflow.app.BuildConfig
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-use supplied audio for an automated debug recording. Android's recognition service
 * transcribes the audio; this class never supplies a transcript or recognition result.
 */
internal class DebugSpeechSource private constructor(
    private val file: File,
    private val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
) : Closeable {
    val id: String = file.nameWithoutExtension
    val bytes: Long = file.length()
    // Debug-private engine diagnostics; this changes only supplied-audio sessions.
    val language: String = runCatching {
        File(file.parentFile, "language.code").readText().trim()
    }.getOrNull()?.takeIf { it in setOf("en-IN", "en-US") } ?: "en-IN"
    val preferOffline: Boolean = runCatching {
        File(file.parentFile, "offline.mode").readText().trim() == "true"
    }.getOrDefault(false)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var worker: Thread? = null

    fun attach(intent: Intent) {
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        // A supplied stream ends when its descriptor reaches EOF; the service returns
        // genuine completed segments through RecognitionListener's API 33 callbacks.
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
    }

    /** Streams twenty milliseconds at a time, at the source's real sample rate. */
    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        worker = Thread({
            val origin = SystemClock.elapsedRealtime()
            var sent = 0L
            var outcome = "eof"
            Log.i(TtsPolicy.TAG, "audio_feed event=start sourceId=$id elapsedRealtimeMs=$origin")
            try {
                file.inputStream().use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                        val chunk = ByteArray(CHUNK_BYTES)
                        while (!closed.get()) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            output.write(chunk, 0, count)
                            sent += count
                            val nextAt = origin + sent * 1_000L / BYTES_PER_SECOND
                            val delay = nextAt - SystemClock.elapsedRealtime()
                            if (delay > 0) Thread.sleep(delay)
                        }
                    }
                }
                if (closed.get()) outcome = "cancelled"
            } catch (failure: InterruptedException) {
                outcome = "cancelled"
                Thread.currentThread().interrupt()
            } catch (failure: IOException) {
                outcome = if (closed.get()) "cancelled" else "error"
                if (outcome == "error") {
                    Log.w(TtsPolicy.TAG, "audio_feed event=error sourceId=$id", failure)
                }
            } finally {
                // EOF ends the supplied stream. Keep the read descriptor until the recognition
                // callback, so the asynchronous service can finish consuming its duplicated FD.
                runCatching { writeSide.close() }
                file.delete()
                Log.i(TtsPolicy.TAG, "audio_feed event=end sourceId=$id bytes=$sent outcome=$outcome " +
                    "elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
            }
        }, "EchoDemoAudio-$id").apply { isDaemon = true }
        worker?.start()
    }

    override fun close() = close("recognition_complete")

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        worker?.interrupt()
        runCatching { readSide.close() }
        runCatching { writeSide.close() }
        file.delete()
        Log.i(TtsPolicy.TAG, "audio_feed event=closed sourceId=$id reason=$reason " +
            "elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val CHUNK_BYTES = BYTES_PER_SECOND / 50
        private const val MAX_BYTES = BYTES_PER_SECOND * 15L

        /** No file is read or consumed by release builds or devices below Android 13. */
        fun consume(context: Context): DebugSpeechSource? {
            if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val directory = File(context.filesDir, "demo-audio")
            val next = File(directory, "next.pcm")
            if (!next.isFile) return null
            val active = File(directory, "d${System.nanoTime()}.pcm")
            if (!next.renameTo(active)) {
                Log.w(TtsPolicy.TAG, "audio_feed event=consume_failed reason=rename")
                return null
            }
            val length = active.length()
            if (length !in 2L..MAX_BYTES || length % 2L != 0L) {
                Log.w(TtsPolicy.TAG, "audio_feed event=consume_failed reason=invalid_pcm_length bytes=$length")
                active.delete()
                return null
            }
            return try {
                val pipe = ParcelFileDescriptor.createPipe()
                DebugSpeechSource(active, pipe[0], pipe[1])
            } catch (failure: IOException) {
                active.delete()
                Log.w(TtsPolicy.TAG, "audio_feed event=consume_failed reason=pipe", failure)
                null
            }
        }
    }
}
