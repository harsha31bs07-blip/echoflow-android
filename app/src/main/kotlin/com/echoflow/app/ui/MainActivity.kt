package com.echoflow.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.echoflow.app.EchoRuntime
import com.echoflow.core.bus.EchoEvent
import com.echoflow.core.safety.GuardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Onboarding + safety monitor controls. Built in code (no layouts) to keep step 1 dependency-free. */
class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var busJob: Job? = null

    private lateinit var serviceStatus: TextView
    private lateinit var restrictedHint: TextView
    private lateinit var lastScreen: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoRuntime.init(this)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: String, size: Float = 15f) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }.also(column::addView)

        text("EchoFlow — safety monitor (build step 1)", 20f)
        serviceStatus = text("")
        column.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        restrictedHint = text(
            "Android 13+: if EchoFlow is greyed out in Accessibility settings, open App info → ⋮ menu → " +
                "\"Allow restricted settings\", then enable it again.",
            13f,
        )
        column.addView(Button(this).apply {
            text = "Open App info"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        column.addView(Switch(this).apply {
            text = "Safety monitor overlay"
            isChecked = EchoRuntime.prefs.monitorEnabled
            setOnCheckedChangeListener { _, checked -> EchoRuntime.prefs.monitorEnabled = checked }
        })
        column.addView(Switch(this).apply {
            text = "Speak each classification"
            isChecked = EchoRuntime.prefs.speakEnabled
            setOnCheckedChangeListener { _, checked -> EchoRuntime.prefs.speakEnabled = checked }
        })

        lastScreen = text("", 13f)
        text(
            "How to validate: open each target app and walk to its payment, OTP, login and cart screens. " +
                "The overlay must turn red on every payment/OTP/password/login screen and stay green elsewhere. " +
                "Tap Dump on any screen to save a redacted fixture to Download/EchoFlow/ " +
                "(see docs/SAFETY_FIXTURES.md).",
            13f,
        )

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        refresh()
        busJob = scope.launch {
            EchoRuntime.bus.events.collect { event ->
                if (event is EchoEvent.SnapshotUpdated || event is EchoEvent.SafetyTripped || event is EchoEvent.SafetyRearmed) refresh()
            }
        }
    }

    override fun onStop() {
        busJob?.cancel()
        busJob = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val connected = EchoRuntime.service != null
        serviceStatus.text = if (connected) "Accessibility service: ON" else "Accessibility service: OFF — enable \"EchoFlow automation\""
        restrictedHint.visibility = if (!connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE

        val verdict = EchoRuntime.guard.lastVerdict
        val guard = when (val state = EchoRuntime.guard.currentState) {
            GuardState.Armed -> "armed"
            is GuardState.Tripped -> "handed off (${state.trip.kind})"
        }
        lastScreen.text = "Last screen: ${EchoRuntime.snapshots.current()?.packageName ?: "—"}\n" +
            "Verdict: ${verdict?.summary() ?: "—"}\nGuard: $guard"
    }
}
