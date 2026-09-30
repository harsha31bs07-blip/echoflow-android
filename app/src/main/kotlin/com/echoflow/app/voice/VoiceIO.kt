package com.echoflow.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    /** Partial transcript / listening state, for the bubble. */
    @Volatile var onStatus: (String) -> Unit = {}

    /** Live speech events for the listening panel (main thread). */
    @Volatile var onSpeechUi: (SpeechUi) -> Unit = {}

    /** The listen() waiting for a result, so cancelListening() can end it at once. */
    @Volatile private var waiting: CompletableDeferred<String?>? = null

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "IN")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { pending.remove(id)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { pending.remove(id)?.complete(Unit) }
                // Interrupted (stopSpeaking, or a newer utterance): done too. Without this, an
                // interrupted speak() waited out its whole timeout (up to ~10 s).
                override fun onStop(id: String, interrupted: Boolean) { pending.remove(id)?.complete(Unit) }
            })
        }
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    /** Speaks and returns when finished (or after a timeout). */
    suspend fun speak(text: String) {
        if (!ready.await()) return
        val id = "u${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(4_000L + text.length * 90L) { done.await() }
    }

    fun speakAsync(text: String) {
        if (ready.isCompleted) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "a${System.nanoTime()}")
    }

    fun stopSpeaking() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    /** Listens once; returns the best transcript or null (silence, error, timeout). */
    suspend fun listen(timeoutMs: Long = 9_000): String? = withContext(Dispatchers.Main) {
        if (!recognitionAvailable) return@withContext null
        val result = CompletableDeferred<String?>()
        waiting = result
        lastCancelled = false
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatus("Listening…")
                onSpeechUi(SpeechUi.Ready)
            }
            override fun onBeginningOfSpeech() = Unit
            // Roughly -2 dB (silence) to 10 dB (loud speech) -> 0..1.
            override fun onRmsChanged(rmsdB: Float) = onSpeechUi(SpeechUi.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                onStatus("Thinking…")
                onSpeechUi(SpeechUi.Thinking)
            }
            override fun onError(error: Int) { result.complete(null) }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                text?.let { onSpeechUi(SpeechUi.Partial(it)) }
                result.complete(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    onStatus("“$it”")
                    onSpeechUi(SpeechUi.Partial(it))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (com.echoflow.app.EchoRuntime.prefs.hindiSpeech) "hi-IN" else "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
        val text = withTimeoutOrNull(timeoutMs) { result.await() }
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
        if (waiting?.isActive == true) lastCancelled = true
        recognizer?.cancel()
        waiting?.complete(null)
    }

    fun shutdown() {
        main.post { recognizer?.destroy(); recognizer = null }
        tts.stop()
        tts.shutdown()
    }
}
