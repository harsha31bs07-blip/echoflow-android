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

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "IN")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { pending.remove(id)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { pending.remove(id)?.complete(Unit) }
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

    fun stopSpeaking() = tts.stop()

    val recognitionAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    /** Listens once; returns the best transcript or null (silence, error, timeout). */
    suspend fun listen(timeoutMs: Long = 9_000): String? = withContext(Dispatchers.Main) {
        if (!recognitionAvailable) return@withContext null
        val result = CompletableDeferred<String?>()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onStatus("Listening…")
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onStatus("Thinking…")
            override fun onError(error: Int) { result.complete(null) }
            override fun onResults(results: Bundle?) {
                result.complete(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onStatus("“$it”") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
        val text = withTimeoutOrNull(timeoutMs) { result.await() }
        if (text == null) r.cancel()
        text
    }

    fun cancelListening() = main.post { recognizer?.cancel() }

    fun shutdown() {
        main.post { recognizer?.destroy(); recognizer = null }
        tts.stop()
        tts.shutdown()
    }
}
