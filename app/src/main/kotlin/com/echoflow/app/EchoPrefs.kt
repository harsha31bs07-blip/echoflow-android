package com.echoflow.app

import android.content.Context
import android.content.SharedPreferences

class EchoPrefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("echoflow", Context.MODE_PRIVATE)

    var monitorEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITOR, true)
        set(value) = sp.edit().putBoolean(KEY_MONITOR, value).apply()

    var speakEnabled: Boolean
        get() = sp.getBoolean(KEY_SPEAK, true)
        set(value) = sp.edit().putBoolean(KEY_SPEAK, value).apply()

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val KEY_MONITOR = "safety_monitor"
        const val KEY_SPEAK = "speak"
    }
}
