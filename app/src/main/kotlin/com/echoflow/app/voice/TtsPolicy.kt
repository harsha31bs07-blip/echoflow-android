package com.echoflow.app.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.echoflow.app.BuildConfig
import java.util.Locale

/** One speech configuration for command replies and background announcements. */
internal object TtsPolicy {
    const val TAG = "EchoVoice"
    /** The engine's natural pace. Faster rates sounded rushed and clipped on the phone speaker. */
    const val RATE = 1.0f
    private val locale = Locale("en", "IN")

    /** What the user said, for logs: the words only in debug builds, a length in release builds. */
    fun shown(text: String?): String = if (BuildConfig.DEBUG) text.orEmpty() else "<${text?.length ?: 0} chars>"

    /** Returns whether the engine can speak the requested language. */
    fun configure(tts: TextToSpeech, owner: String): Boolean {
        val languageResult = tts.setLanguage(locale)
        val voices = tts.voices.orEmpty().sortedBy { it.name }
        Log.i(TAG, "tts event=inventory owner=$owner defaultEngine=${tts.defaultEngine} " +
            "installedEngines=${tts.engines.joinToString(",") { it.name }} count=${voices.size}")
        if (BuildConfig.DEBUG) voices.forEach { voice ->
            Log.i(TAG, "tts event=available_voice owner=$owner name=${voice.name} " +
                "locale=${voice.locale.toLanguageTag()} network=${voice.isNetworkConnectionRequired} " +
                "quality=${voice.quality} latency=${voice.latency}")
        }

        // Keep the engine's own Indian English voice when it is installed on the phone; otherwise take an
        // installed (offline, high quality) one, so replies never wait on the network or sound degraded.
        val current = tts.voice
        val preferred = if (current != null && isGood(current)) null else voices.firstOrNull(::isGood)
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

    private fun isGood(voice: Voice): Boolean =
        voice.locale.language.equals("en", ignoreCase = true) &&
            voice.locale.country.equals("IN", ignoreCase = true) &&
            !voice.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty() &&
            voice.quality >= Voice.QUALITY_HIGH

    private fun languageStatus(result: Int): String = when (result) {
        TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE"
        TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA"
        TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"
        else -> "UNKNOWN"
    }
}
