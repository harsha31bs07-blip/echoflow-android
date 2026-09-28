package com.echoflow.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.echoflow.app.BuildConfig
import com.echoflow.app.EchoRuntime
import com.echoflow.core.bus.EchoEvent
import com.echoflow.core.safety.GuardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Home screen: setup, a typed command box, learned flows, last run, and debug switches. */
class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private val jobs = mutableListOf<Job>()

    private lateinit var serviceStatus: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var micButton: Button
    private lateinit var orchestratorStatus: TextView
    private lateinit var flowsBox: LinearLayout
    private lateinit var lastRun: TextView
    private lateinit var debugInfo: TextView
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoRuntime.init(this)
        pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(text("EchoFlow", 24f, bold = true))
        column.addView(text("Teach a flow once by voice and taps. Replay it by voice. EchoFlow always stops before payment, OTP, password and login screens.", 13f))

        serviceStatus = text("", 15f, bold = true).also(column::addView)
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)
        setupBox.addView(button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        setupBox.addView(text("Android 13+: if EchoFlow is greyed out, open App info → ⋮ → \"Allow restricted settings\", then enable it again.", 12f))
        setupBox.addView(button("Open App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        micButton = button("Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }.also(column::addView)

        column.addView(section("Command"))
        column.addView(text("Use the floating Speak button inside any app, or type here. Examples: \"teach order 2 garlic bread\", \"order 3 paneer tikka\", \"what happened last time\".", 12f))
        val input = EditText(this).apply {
            hint = "Type a command"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = { val t = input.text.toString().trim(); if (t.isNotEmpty()) { EchoRuntime.orchestrator.onTyped(t); input.setText("") } }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        column.addView(input)
        column.addView(button("Send") { go() })
        orchestratorStatus = text("", 14f).also(column::addView)

        column.addView(section("Learned flows"))
        flowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)

        column.addView(section("Last run"))
        lastRun = text("", 13f).also(column::addView)
        column.addView(button("Say it") {
            EchoRuntime.orchestrator.onTyped("what happened last time")
        })

        column.addView(section("Safety monitor (debug)"))
        column.addView(Switch(this).apply {
            text = "Show safety monitor overlay"
            isChecked = EchoRuntime.prefs.monitorEnabled
            setOnCheckedChangeListener { _, checked -> EchoRuntime.prefs.monitorEnabled = checked }
        })
        column.addView(Switch(this).apply {
            text = "Speak each screen classification"
            isChecked = EchoRuntime.prefs.speakEnabled
            setOnCheckedChangeListener { _, checked -> EchoRuntime.prefs.speakEnabled = checked }
        })
        debugInfo = text("", 12f).also(column::addView)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        refresh()
        jobs += scope.launch {
            EchoRuntime.bus.events.collect { e -> if (e is EchoEvent.SafetyTripped || e is EchoEvent.SafetyRearmed) refresh() }
        }
        jobs += scope.launch {
            EchoRuntime.orchestrator.state.collect { s ->
                orchestratorStatus.text = "${s.mode}: ${s.status}"
                refreshFlowsAndRuns()
            }
        }
    }

    override fun onStop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val connected = EchoRuntime.service != null
        serviceStatus.text = if (connected) "✅ Accessibility service is on" else "⚠️ Turn on \"EchoFlow automation\" in Accessibility settings"
        setupBox.visibility = if (connected) View.GONE else View.VISIBLE
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micButton.visibility = if (mic) View.GONE else View.VISIBLE
        val guard = when (val state = EchoRuntime.guard.currentState) {
            GuardState.Armed -> "armed"
            is GuardState.Tripped -> "handed off (${state.trip.kind})"
        }
        val snapshot = EchoRuntime.snapshots.current()
        debugInfo.text = "Screen: ${snapshot?.packageName ?: "—"} · ${snapshot?.diagnostics?.summary() ?: "—"}\n" +
            "Verdict: ${EchoRuntime.guard.lastVerdict?.summary() ?: "—"} · Guard: $guard\n" +
            "Paraphrase AI (Gemini): ${if (BuildConfig.GEMINI_API_KEY.isBlank()) "off — no key, local matching only" else "on (${BuildConfig.GEMINI_MODEL})"}" +
            "\nRestricted settings hint: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "may apply" else "n/a"}"
        refreshFlowsAndRuns()
    }

    private fun refreshFlowsAndRuns() {
        val flows = EchoRuntime.orchestrator.flows.all()
        flowsBox.removeAllViews()
        if (flows.isEmpty()) flowsBox.addView(text("Nothing yet. Say \"teach …\" and show me.", 13f))
        flows.forEach { f ->
            flowsBox.addView(button("${f.template}  ·  ${f.appLabel ?: f.appPackage}  ·  ${f.steps.size} steps") {
                startActivity(Intent(this, FlowInspectorActivity::class.java).putExtra(FlowInspectorActivity.EXTRA_ID, f.id))
            })
        }
        val last = EchoRuntime.orchestrator.runs.last()
        lastRun.text = last?.let {
            "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.startedAtMs))} — ${it.status}\n${it.spokenSummary()}" +
                if (it.events.isNotEmpty()) "\nEvents: " + it.events.joinToString("; ") else ""
        } ?: "No runs yet."
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, pad / 4, 0, pad / 4)
    }

    private fun section(title: String) = text(title, 18f, bold = true).apply { setPadding(0, pad, 0, pad / 4) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
