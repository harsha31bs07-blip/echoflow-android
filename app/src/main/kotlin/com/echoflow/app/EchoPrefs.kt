package com.echoflow.app

import android.content.Context
import android.content.SharedPreferences

class EchoPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("echoflow", Context.MODE_PRIVATE)

    var monitorEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITOR, false)
        set(value) = sp.edit().putBoolean(KEY_MONITOR, value).apply()

    var speakEnabled: Boolean
        get() = sp.getBoolean(KEY_SPEAK, false)
        set(value) = sp.edit().putBoolean(KEY_SPEAK, value).apply()

    /**
     * The user's own Gemini API key, pasted in the app (never built into the APK). Stored in this
     * app's private preferences; backups are off (allowBackup="false"), so it stays on the phone.
     */
    var geminiKey: String
        get() = sp.getString(KEY_GEMINI, "").orEmpty()
        set(value) = sp.edit().putString(KEY_GEMINI, value.trim()).apply()

    /** The microphone permission was requested at least once (to spot "denied for good"). */
    var micAsked: Boolean
        get() = sp.getBoolean(KEY_MIC_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_MIC_ASKED, value).apply()

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val KEY_MONITOR = "safety_monitor"
        const val KEY_SPEAK = "speak"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_MIC_ASKED = "mic_asked"
    }
}
