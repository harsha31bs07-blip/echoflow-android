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

    /**
     * Siri-style bubble: tucked into a small edge handle when idle, a glow around the screen
     * while busy. Off = the full panel stays on screen.
     */
    var minimalBubble: Boolean
        get() = sp.getBoolean(KEY_MINIMAL, true)
        set(value) = sp.edit().putBoolean(KEY_MINIMAL, value).apply()

    /** Where the edge handle sits: right or left edge, and its centre as a fraction of screen height. */
    var handleOnRight: Boolean
        get() = sp.getBoolean(KEY_HANDLE_RIGHT, true)
        set(value) = sp.edit().putBoolean(KEY_HANDLE_RIGHT, value).apply()

    var handleY: Float
        get() = sp.getFloat(KEY_HANDLE_Y, 0.77f)
        set(value) = sp.edit().putFloat(KEY_HANDLE_Y, value.coerceIn(0.08f, 0.92f)).apply()

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
        const val KEY_MINIMAL = "minimal_bubble"
        const val KEY_HANDLE_RIGHT = "handle_right"
        const val KEY_HANDLE_Y = "handle_y"
    }
}
