package com.echoflow.app.monitor

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin TextToSpeech wrapper. Anything spoken before the engine is ready is spoken once it is. */
class Announcer(context: Context) {
    private var ready = false
    private var queued: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.ENGLISH
            queued?.let { speak(it) }
            queued = null
        }
    }

    fun speak(text: String) {
        if (!ready) {
            queued = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "echo-${System.nanoTime()}")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
