package com.echoflow.app.monitor

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.echoflow.app.voice.TtsPolicy

/** Thin TextToSpeech wrapper. Anything spoken before the engine is ready is spoken once it is. */
class Announcer(context: Context) {
    private var ready = false
    private var queued: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        Log.i(TtsPolicy.TAG, "tts event=init owner=Announcer status=$status")
        if (status == TextToSpeech.SUCCESS) {
            ready = runCatching { TtsPolicy.configure(tts, "Announcer") }.getOrElse {
                Log.e(TtsPolicy.TAG, "tts event=configuration_error owner=Announcer", it)
                false
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {
                    Log.i(TtsPolicy.TAG, "tts event=start owner=Announcer id=$id elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                }
                override fun onDone(id: String) {
                    Log.i(TtsPolicy.TAG, "tts event=done owner=Announcer id=$id elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) {
                    Log.w(TtsPolicy.TAG, "tts event=error owner=Announcer id=$id code=unknown elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                }
                override fun onError(id: String, errorCode: Int) {
                    Log.w(TtsPolicy.TAG, "tts event=error owner=Announcer id=$id code=$errorCode elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                }
                override fun onStop(id: String, interrupted: Boolean) {
                    Log.i(TtsPolicy.TAG, "tts event=stop owner=Announcer id=$id interrupted=$interrupted elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                }
            })
        }
        if (ready) {
            queued?.let { speak(it) }
            queued = null
        }
    }

    fun speak(text: String) {
        if (!ready) {
            queued = text
            return
        }
        val id = "echo-${System.nanoTime()}"
        val status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.i(TtsPolicy.TAG, "tts event=queued owner=Announcer id=$id chars=${text.length} status=$status")
    }

    fun shutdown() {
        Log.i(TtsPolicy.TAG, "tts event=stop_requested owner=Announcer status=${tts.stop()}")
        tts.shutdown()
    }
}
