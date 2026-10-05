package com.echoflow.app.voice

import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** One speech configuration for command replies and background announcements. */
internal object TtsPolicy {
    const val TAG = "EchoVoice"
    const val RATE = 1.38f
    private val locale = Locale("en", "IN")

    /** Returns whether the engine can speak the requested language. */
    fun configure(tts: TextToSpeech, owner: String): Boolean {
        val languageResult = tts.setLanguage(locale)
        val voices = tts.voices.orEmpty().sortedBy { it.name }
        Log.i(TAG, "tts event=inventory owner=$owner defaultEngine=${tts.defaultEngine} " +
            "installedEngines=${tts.engines.joinToString(",") { it.name }} count=${voices.size}")
        voices.forEach { voice ->
            Log.i(TAG, "tts event=available_voice owner=$owner name=${voice.name} " +
                "locale=${voice.locale.toLanguageTag()} network=${voice.isNetworkConnectionRequired} " +
                "quality=${voice.quality} latency=${voice.latency}")
        }

        // Edge's narration voice is not automatically a voice installed in Android's engine.
        // Select Prabhat/Prabhath only when the engine actually exposes an Indian English voice.
        val preferred = voices.firstOrNull {
            it.locale.language.equals("en", ignoreCase = true) &&
                it.locale.country.equals("IN", ignoreCase = true) &&
                (it.name.contains("Prabhat", ignoreCase = true) ||
                    it.name.contains("Prabhath", ignoreCase = true))
        }
        val voiceResult = if (languageResult >= TextToSpeech.LANG_AVAILABLE && preferred != null) {
            tts.setVoice(preferred)
        } else null
        val rateResult = tts.setSpeechRate(RATE)
        val selected = tts.voice
        Log.i(TAG, "tts event=configured owner=$owner requestedLocale=${locale.toLanguageTag()} " +
            "languageResult=$languageResult languageStatus=${languageStatus(languageResult)} " +
            "preferred=${preferred?.name ?: "unavailable"} voiceResult=${voiceResult ?: "engine_default"} " +
            "actualVoice=${selected?.name ?: "unavailable"} " +
            "actualLocale=${selected?.locale?.toLanguageTag() ?: "unavailable"} " +
            "rate=$RATE rateResult=$rateResult")
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "tts event=configuration_error owner=$owner reason=language_unavailable code=$languageResult")
        }
        if (voiceResult == TextToSpeech.ERROR || rateResult == TextToSpeech.ERROR) {
            Log.w(TAG, "tts event=configuration_error owner=$owner voiceResult=$voiceResult rateResult=$rateResult")
        }
        return languageResult >= TextToSpeech.LANG_AVAILABLE
    }

    private fun languageStatus(result: Int): String = when (result) {
        TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE"
        TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA"
        TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"
        else -> "UNKNOWN"
    }
}
