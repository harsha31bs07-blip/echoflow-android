package com.echoflow.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What the listening panel shows while the microphone is open. */
sealed interface SpeechUi {
    /** The microphone is open. */
    data object Ready : SpeechUi
    /** Voice level, 0 (silence) to 1 (loud). */
    data class Level(val value: Float) : SpeechUi
    /** The words heard so far. */
    data class Partial(val text: String) : SpeechUi
    /** The speaker stopped; recognising. */
    data object Thinking : SpeechUi
    /** Listening is over ([text] null: nothing understood or cancelled). */
    data class Ended(val text: String?) : SpeechUi
}

/**
 * Push-to-talk speech recognition plus text-to-speech that can be awaited, so a question is
 * fully spoken before the microphone opens for the answer.
 */
class VoiceIO(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var recognizer: SpeechRecognizer? = null
    private var activeSpeechSource: DebugSpeechSource? = null
    @Volatile private var ttsReady = false

    /** Partial transcript / listening state, for the bubble. */
    @Volatile var onStatus: (String) -> Unit = {}

    /** Live speech events for the listening panel (main thread). */
    @Volatile var onSpeechUi: (SpeechUi) -> Unit = {}

    /** The listen() waiting for a result, so cancelListening() can end it at once. */
    @Volatile private var waiting: CompletableDeferred<String?>? = null

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        Log.i(TtsPolicy.TAG, "tts event=init owner=VoiceIO status=$status")
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = runCatching { TtsPolicy.configure(tts, "VoiceIO") }.getOrElse {
                Log.e(TtsPolicy.TAG, "tts event=configuration_error owner=VoiceIO", it)
                false
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {
                    Log.i(TtsPolicy.TAG, "tts event=start owner=VoiceIO id=$id elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                }
                override fun onDone(id: String) {
                    Log.i(TtsPolicy.TAG, "tts event=done owner=VoiceIO id=$id elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                    pending.remove(id)?.complete(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) {
                    Log.w(TtsPolicy.TAG, "tts event=error owner=VoiceIO id=$id code=unknown elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                    pending.remove(id)?.complete(Unit)
                }
                override fun onError(id: String, errorCode: Int) {
                    Log.w(TtsPolicy.TAG, "tts event=error owner=VoiceIO id=$id code=$errorCode elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                    pending.remove(id)?.complete(Unit)
                }
                // Interrupted (stopSpeaking, or a newer utterance): done too. Without this, an
                // interrupted speak() waited out its whole timeout (up to ~10 s).
                override fun onStop(id: String, interrupted: Boolean) {
                    Log.i(TtsPolicy.TAG, "tts event=stop owner=VoiceIO id=$id interrupted=$interrupted elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                    pending.remove(id)?.complete(Unit)
                }
            })
        }
        ready.complete(ttsReady)
    }

    /** Speaks and returns when finished (or after a timeout). */
    suspend fun speak(text: String) {
        if (!ready.await()) return
        val id = "u${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.i(TtsPolicy.TAG, "tts event=queued owner=VoiceIO id=$id chars=${text.length} status=$status")
        if (status == TextToSpeech.ERROR) pending.remove(id)?.complete(Unit)
        try {
            if (withTimeoutOrNull(4_000L + text.length * 90L) { done.await() } == null) {
                Log.w(TtsPolicy.TAG, "tts event=await_timeout owner=VoiceIO id=$id")
            }
        } finally {
            pending.remove(id)
        }
    }

    fun speakAsync(text: String) {
        if (ttsReady) {
            val id = "a${System.nanoTime()}"
            val status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            Log.i(TtsPolicy.TAG, "tts event=queued owner=VoiceIO id=$id chars=${text.length} status=$status")
        }
    }

    fun stopSpeaking() {
        Log.i(TtsPolicy.TAG, "tts event=stop_requested owner=VoiceIO status=${tts.stop()}")
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    /** Listens once; returns the best transcript or null (silence, error, timeout). */
    suspend fun listen(timeoutMs: Long = 9_000): String? = withContext(Dispatchers.Main) {
        if (!recognitionAvailable) {
            Log.w(TtsPolicy.TAG, "asr event=unavailable")
            return@withContext null
        }
        val session = "r${System.nanoTime()}"
        val started = SystemClock.elapsedRealtime()
        val language = if (com.echoflow.app.EchoRuntime.prefs.hindiSpeech) "hi-IN" else "en-IN"
        var rmsSamples = 0
        var peakRms = Float.NEGATIVE_INFINITY
        var lastPartial: String? = null
        val completedSegments = mutableListOf<String>()
        fun summary(): String = "elapsedMs=${SystemClock.elapsedRealtime() - started} " +
            "elapsedRealtimeMs=${SystemClock.elapsedRealtime()} " +
            "rmsSamples=$rmsSamples peakRmsDb=" +
            if (rmsSamples == 0) "unavailable" else String.format(Locale.US, "%.2f", peakRms)
        val result = CompletableDeferred<String?>()
        waiting = result
        lastCancelled = false
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        activeSpeechSource?.close("superseded")
        val source = DebugSpeechSource.consume(app)
        val recognitionLanguage = source?.language ?: language
        activeSpeechSource = source
        Log.i(TtsPolicy.TAG, "asr event=input_source session=$session mode=" +
            if (source == null) "microphone elapsedRealtimeMs=${SystemClock.elapsedRealtime()}" else
                "supplied_audio sourceId=${source.id} bytes=${source.bytes} sampleRate=16000 " +
                    "channels=1 encoding=PCM_16BIT elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TtsPolicy.TAG, "asr event=ready session=$session ${summary()}")
                onStatus("Listening…")
                onSpeechUi(SpeechUi.Ready)
            }
            override fun onBeginningOfSpeech() {
                Log.i(TtsPolicy.TAG, "asr event=begin_speech session=$session ${summary()}")
            }
            // Roughly -2 dB (silence) to 10 dB (loud speech) -> 0..1.
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB.isFinite()) {
                    rmsSamples++
                    peakRms = maxOf(peakRms, rmsdB)
                }
                onSpeechUi(SpeechUi.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                Log.i(TtsPolicy.TAG, "asr event=end_speech session=$session ${summary()}")
                onStatus("Thinking…")
                onSpeechUi(SpeechUi.Thinking)
            }
            override fun onError(error: Int) {
                Log.w(TtsPolicy.TAG, "asr event=error session=$session code=$error " +
                    "name=${recognitionErrorName(error)} ${summary()}")
                source?.close("recognition_error")
                result.complete(null)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                if (source != null && text.isNullOrBlank()) {
                    Log.i(TtsPolicy.TAG, "asr event=empty_results session=$session " +
                        "keys=${results?.keySet()?.joinToString(",").orEmpty()} ${summary()}")
                    // Segmented recognition completes in onEndOfSegmentedSession.
                    // Partial transcripts are deliberately never substituted for a final result.
                    return
                }
                Log.i(TtsPolicy.TAG, "asr event=final session=$session confidence=${confidence ?: "unavailable"} " +
                    "text=${TtsPolicy.shown(text)} ${summary()}")
                text?.let { onSpeechUi(SpeechUi.Partial(it)) }
                source?.close("recognition_result")
                result.complete(text)
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                if (source == null) return
                val text = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                Log.i(TtsPolicy.TAG, "asr event=segment session=$session text=${TtsPolicy.shown(text)} ${summary()}")
                if (text != null) {
                    completedSegments.add(text)
                    onSpeechUi(SpeechUi.Partial(completedSegments.joinToString(" ")))
                }
            }
            override fun onEndOfSegmentedSession() {
                if (source == null) return
                val text = completedSegments.joinToString(" ").takeIf { it.isNotBlank() }
                Log.i(TtsPolicy.TAG, "asr event=final session=$session callback=segmented_final " +
                    "segments=${completedSegments.size} text=${TtsPolicy.shown(text)} ${summary()}")
                source.close("segmented_session_end")
                result.complete(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    if (it != lastPartial) {
                        Log.i(TtsPolicy.TAG, "asr event=partial session=$session text=${TtsPolicy.shown(it)}")
                        lastPartial = it
                    }
                    onStatus("“$it”")
                    onSpeechUi(SpeechUi.Partial(it))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.i(TtsPolicy.TAG, "asr event=engine_event session=$session type=$eventType")
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        source?.attach(intent)
        Log.i(TtsPolicy.TAG, "asr event=start_requested session=$session language=$recognitionLanguage " +
            "preferOffline=${source?.preferOffline ?: false} timeoutMs=$timeoutMs")
        try {
            r.startListening(intent)
            source?.start()
        } catch (failure: RuntimeException) {
            Log.e(TtsPolicy.TAG, "asr event=start_failed session=$session ${summary()}", failure)
            source?.close("start_failed")
            result.complete(null)
        }
        val text = try {
            withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            source?.close("listen_finished")
            if (activeSpeechSource === source) activeSpeechSource = null
        }
        if (!result.isCompleted) {
            Log.w(TtsPolicy.TAG, "asr event=await_timeout session=$session ${summary()}")
        }
        // A newer listen() may already have started (✕, then a quick tap): then this one's end
        // must not touch the panel or the recognizer, which now belong to the new session.
        val current = waiting === result
        if (current) {
            if (text == null) r.cancel()
            waiting = null
            // Let the final words show for a moment before the panel goes.
            onSpeechUi(SpeechUi.Ended(text))
        }
        text
    }

    /** True when the last listen() ended because the user tapped ✕ (not silence). */
    @Volatile var lastCancelled = false
        private set

    /** Stops listening now; the waiting listen() returns null straight away. */
    fun cancelListening() = main.post {
        Log.i(TtsPolicy.TAG, "asr event=cancel_requested active=${waiting?.isActive == true}")
        if (waiting?.isActive == true) lastCancelled = true
        activeSpeechSource?.close("cancel_requested")
        activeSpeechSource = null
        recognizer?.cancel()
        waiting?.complete(null)
    }

    fun shutdown() {
        main.post {
            activeSpeechSource?.close("shutdown")
            activeSpeechSource = null
            recognizer?.destroy()
            recognizer = null
        }
        tts.stop()
        tts.shutdown()
    }

    private fun recognitionErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "UNKNOWN"
    }
}
