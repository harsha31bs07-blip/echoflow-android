package com.echoflow.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.echoflow.app.BuildConfig
import com.echoflow.app.EchoRuntime
import com.echoflow.app.orchestrator.Mode
import com.echoflow.app.orchestrator.UiState
import com.echoflow.core.bus.EchoEvent
import com.echoflow.core.flow.Flow
import com.echoflow.core.runlog.RunRecord
import com.echoflow.core.safety.GuardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Home screen: a two-step setup checklist, a command box with live status and examples,
 * what EchoFlow has learned, the last run, a safety note and (collapsed) advanced switches.
 */
class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private val jobs = mutableListOf<Job>()

    private lateinit var kit: Kit

    // Setup
    private lateinit var setupCard: LinearLayout
    private lateinit var readyCard: LinearLayout
    private lateinit var accessStep: CheckStep
    private lateinit var micStep: CheckStep

    // Ask EchoFlow
    private lateinit var statusBox: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var statusHeadline: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusActions: LinearLayout
    private lateinit var input: EditText
    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var examplesTitle: TextView
    private lateinit var examplesBox: LinearLayout

    // Learned flows, last run, advanced
    private lateinit var flowsBox: LinearLayout
    private lateinit var lastRunBox: LinearLayout
    private lateinit var advancedToggle: TextView
    private lateinit var advancedBox: LinearLayout
    private lateinit var debugInfo: TextView

    private var showRunDetails = false

    /** One row of the setup checklist: an indicator circle, a title, and actions shown until done. */
    private class CheckStep(val row: LinearLayout, val indicator: TextView, val title: TextView, val note: TextView, val actions: LinearLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoRuntime.init(this)
        kit = Kit(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(kit.header("EchoFlow", "Teach it once. Say it. It's done, and it never pays."))
        val column = kit.column(16, 16)
        root.addView(column)

        buildSetup(column)
        buildAsk(column)

        column.addView(kit.heading("What I've learned"))
        flowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)

        column.addView(kit.heading("Last run"))
        lastRunBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(column::addView)

        column.addView(kit.space(8))
        column.addView(safetyCard())

        buildAdvanced(column)
        column.addView(kit.space(24))

        setContentView(kit.screen(this, root))
    }

    override fun onStart() {
        super.onStart()
        refresh()
        jobs += scope.launch {
            EchoRuntime.bus.events.collect { e -> if (e is EchoEvent.SafetyTripped || e is EchoEvent.SafetyRearmed) refresh() }
        }
        jobs += scope.launch {
            EchoRuntime.orchestrator.state.collect { s ->
                showState(s)
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

    // ---------------------------------------------------------------- building

    private fun buildSetup(column: LinearLayout) {
        setupCard = kit.card {
            addView(kit.text("Two quick steps", 20f, bold = true).apply { isAccessibilityHeading = true })
            addView(kit.caption("EchoFlow needs these once before it can help.").apply { setPadding(0, kit.dp(2), 0, kit.dp(4)) })
        }
        accessStep = checkStep(1, "Turn on EchoFlow in Accessibility", "Lets EchoFlow see the screen and tap for you.")
        accessStep.actions.apply {
            addView(kit.primaryButton("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            // Android 13+ greys out the toggle for apps installed from a file until this is allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addView(kit.caption("Greyed out? Open App info → ⋮ (top right) → \"Allow restricted settings\", then turn EchoFlow on again.").apply {
                    setPadding(0, kit.dp(10), 0, 0)
                })
                addView(kit.secondaryButton("Open App info") { openAppInfo() })
            }
        }
        setupCard.addView(accessStep.row)
        setupCard.addView(kit.divider())
        micStep = checkStep(2, "Allow the microphone", "So you can speak your commands.")
        micStep.actions.addView(kit.primaryButton("Allow microphone") {
            // Denied with "don't ask again": Android won't show the prompt, so open the settings page.
            if (EchoRuntime.prefs.micAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                openAppInfo()
            } else {
                EchoRuntime.prefs.micAsked = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        })
        micStep.actions.addView(kit.caption("You can also type every command and answer instead.").apply { setPadding(0, kit.dp(8), 0, 0) })
        setupCard.addView(micStep.row)
        column.addView(setupCard)

        readyCard = kit.card(Palette.MINT_TINT) {
            addView(kit.row(
                kit.iconCircle("✓", Palette.MINT, 32).apply { setTextColor(Color.WHITE) },
                kit.text("You're all set. Tap the 🎤 bubble in any app and speak.", 15f, MINT_DARK, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            ))
        }
        column.addView(readyCard)
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun checkStep(number: Int, title: String, note: String): CheckStep {
        val indicator = kit.iconCircle("$number", Palette.CORAL, 32).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val titleView = kit.text(title, 16f, bold = true)
        val noteView = kit.caption(note)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(titleView)
            addView(noteView)
            addView(actions)
        }
        val row = kit.row(indicator, body, gravity = Gravity.TOP).apply { setPadding(0, kit.dp(10), 0, kit.dp(4)) }
        return CheckStep(row, indicator, titleView, noteView, actions)
    }

    private fun markStep(step: CheckStep, number: Int, done: Boolean) {
        step.indicator.text = if (done) "✓" else "$number"
        step.indicator.background = kit.rounded(if (done) Palette.MINT else Palette.CORAL, 16f)
        step.indicator.contentDescription = if (done) "Done" else "Step $number, not done yet"
        step.title.setTextColor(if (done) Palette.MUTED else Palette.TEXT)
        step.note.visibility = if (done) View.GONE else View.VISIBLE
        step.actions.visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun buildAsk(column: LinearLayout) {
        column.addView(kit.heading("Ask EchoFlow"))
        val card = kit.card()

        // Live status
        statusBadge = kit.text("●", 13f, Palette.MINT, bold = true).apply {
            setPadding(kit.dp(8), kit.dp(3), kit.dp(8), kit.dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = kit.dp(8) }
        }
        statusHeadline = kit.text("Ready", 18f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusDetail = kit.caption("").apply { setPadding(0, kit.dp(4), 0, 0) }
        statusActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12))
            background = kit.rounded(Palette.BG, 14f)
            addView(kit.row(statusBadge, statusHeadline, gravity = Gravity.CENTER_VERTICAL))
            addView(statusDetail)
            addView(statusActions)
            // Screen readers announce status changes (listening, learning, questions).
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(statusBox)

        // Command box + Send
        input = EditText(this).apply {
            hint = "Type a command"
            setHintTextColor(Palette.MUTED)
            setTextColor(Palette.TEXT)
            textSize = 15f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            minHeight = kit.dp(52)
            setPadding(kit.dp(18), kit.dp(12), kit.dp(18), kit.dp(12))
            background = kit.rounded(Color.WHITE, 26f, Palette.LINE, 1.5f)
            setOnFocusChangeListener { v, focused ->
                v.background = kit.rounded(Color.WHITE, 26f, if (focused) Palette.CORAL else Palette.LINE, 1.5f)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            contentDescription = "Command"
        }
        input.setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        val send = kit.primaryButton("Send") { sendTyped() }.apply {
            contentDescription = "Send command"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = kit.dp(8) }
        }
        card.addView(kit.row(input, send).apply { setPadding(0, kit.dp(14), 0, 0) })

        // Try saying
        examplesTitle = kit.text("Try saying", 13f, Palette.MUTED, bold = true).apply { setPadding(0, kit.dp(16), 0, 0) }
        card.addView(examplesTitle)
        examplesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(examplesBox)

        column.addView(card)
    }

    private fun safetyCard() = kit.card(Palette.MINT_TINT) {
        addView(kit.row(
            kit.iconCircle("🛡", Color.WHITE, 36),
            kit.text("EchoFlow never pays. It stops on payment, OTP, password and login screens and says \"Your turn\".", 14f, MINT_DARK).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        ))
    }

    private fun buildAdvanced(column: LinearLayout) {
        advancedToggle = kit.text("Advanced  ›", 15f, Palette.MUTED, bold = true).apply {
            minHeight = kit.dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(kit.dp(4), kit.dp(8), kit.dp(4), kit.dp(8))
            isClickable = true
            accessibilityDelegate = Kit.ROLE_BUTTON
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), null, kit.rounded(Color.WHITE, 12f))
            contentDescription = "Advanced settings, collapsed"
            setOnClickListener { setAdvancedOpen(advancedBox.visibility != View.VISIBLE) }
        }
        column.addView(advancedToggle)

        advancedBox = kit.card {
            // Optional LLM: the user's own key, so the APK ships without one.
            addView(kit.text("Smarter matching (optional)", 15f, Palette.TEXT, bold = true))
            addView(kit.caption("Paste a Gemini API key (free at aistudio.google.com) and EchoFlow understands looser wordings without asking \"Do you want me to…?\" first. " +
                "Gemini only sees your command and the names of your learned flows, never the screen, and it never taps anything. The key stays on this phone."))
            keyInput = EditText(this@MainActivity).apply {
                hint = "Gemini API key"
                setHintTextColor(Palette.MUTED)
                setTextColor(Palette.TEXT)
                textSize = 15f
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                minHeight = kit.dp(48)
                setPadding(kit.dp(14), kit.dp(10), kit.dp(14), kit.dp(10))
                background = kit.rounded(Color.WHITE, 14f, Palette.LINE, 1.5f)
                setText(EchoRuntime.prefs.geminiKey)
                contentDescription = "Gemini API key"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(10) }
            }
            addView(keyInput)
            addView(kit.row(
                kit.primaryButton("Save key", Palette.MINT) {
                    EchoRuntime.prefs.geminiKey = keyInput.text.toString()
                    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(keyInput.windowToken, 0)
                    refresh()
                }.apply { layoutParams = halfWidth(end = 4) },
                kit.secondaryButton("Remove", Palette.RED) {
                    EchoRuntime.prefs.geminiKey = ""
                    keyInput.setText("")
                    refresh()
                }.apply { layoutParams = halfWidth(start = 4) },
            ))
            keyStatus = kit.caption("")
            addView(keyStatus)
            addView(kit.divider())
            addView(kit.caption("For demos and debugging."))
            addView(switchRow("Show safety monitor overlay", EchoRuntime.prefs.monitorEnabled) { EchoRuntime.prefs.monitorEnabled = it })
            addView(switchRow("Speak each screen classification", EchoRuntime.prefs.speakEnabled) { EchoRuntime.prefs.speakEnabled = it })
            addView(kit.divider())
            debugInfo = kit.text("", 12f, Palette.MUTED).apply { typeface = Typeface.MONOSPACE }
            addView(debugInfo)
        }
        advancedBox.visibility = View.GONE
        column.addView(advancedBox)
    }

    private fun setAdvancedOpen(open: Boolean) {
        advancedBox.visibility = if (open) View.VISIBLE else View.GONE
        advancedToggle.text = if (open) "Advanced  ⌄" else "Advanced  ›"
        advancedToggle.contentDescription = if (open) "Advanced settings, expanded" else "Advanced settings, collapsed"
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Palette.TEXT)
        isChecked = checked
        minHeight = kit.dp(48)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.MINT, Color.WHITE))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.MINT, Palette.LINE))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    // ---------------------------------------------------------------- actions

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        EchoRuntime.orchestrator.onTyped(t)
        input.setText("")
        hideKeyboard()
    }

    private fun send(command: String) {
        EchoRuntime.orchestrator.onTyped(command)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun openFlow(f: Flow) {
        startActivity(Intent(this, FlowInspectorActivity::class.java).putExtra(FlowInspectorActivity.EXTRA_ID, f.id))
    }

    // ---------------------------------------------------------------- state

    private fun showState(s: UiState) {
        statusActions.removeAllViews()
        val detail: String?
        when (s.mode) {
            Mode.IDLE -> {
                badge("●", Palette.MINT, null)
                headline("Ready", Palette.TEXT, Palette.BG)
                detail = s.status.takeIf { it.isNotBlank() }
            }
            Mode.LISTENING -> {
                badge("●", Palette.CORAL, null)
                headline("Listening…", Palette.TEXT, Palette.CORAL_TINT)
                detail = "Say your command now."
            }
            Mode.TEACHING -> {
                badge("● REC", Color.WHITE, Palette.CORAL)
                headline("Learning: ${s.status}", CORAL_DARK, Palette.CORAL_TINT)
                detail = "Do it in the app and stop before paying, then tap ✓ Done."
                statusActions.addView(kit.row(
                    kit.primaryButton("✓ Done", Palette.MINT) { EchoRuntime.orchestrator.onDonePressed() }.apply { layoutParams = halfWidth(end = 4) },
                    kit.secondaryButton("Stop", Palette.RED) { EchoRuntime.orchestrator.onStopPressed() }.apply { layoutParams = halfWidth(start = 4) },
                ))
            }
            Mode.RUNNING -> {
                badge("●", Palette.MINT, null)
                headline("Working on it…", Palette.TEXT, Palette.MINT_TINT)
                detail = s.status.takeIf { it.isNotBlank() && it != "Working on it…" }
                statusActions.addView(kit.secondaryButton("Stop", Palette.RED) { EchoRuntime.orchestrator.onStopPressed() })
            }
            Mode.ASKING -> {
                badge("?", Palette.INK, Palette.GOLD)
                headline(s.question ?: s.status, GOLD_DARK, Palette.GOLD_TINT)
                detail = if (s.choices.isEmpty()) "Answer out loud, or type your answer below." else "Answer out loud, tap a choice, or type below."
                if (s.choices.isNotEmpty()) {
                    statusActions.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        s.choices.forEach { c -> addView(tapChip(c, Palette.INK, Color.WHITE) { EchoRuntime.orchestrator.onChoice(c) }) }
                    })
                }
            }
        }
        statusDetail.text = detail ?: ""
        statusDetail.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        statusActions.visibility = if (statusActions.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun badge(label: String, color: Int, fill: Int?) {
        statusBadge.text = label
        statusBadge.setTextColor(color)
        statusBadge.background = fill?.let { kit.rounded(it, 12f) }
        statusBadge.setPadding(if (fill != null) kit.dp(8) else 0, kit.dp(3), kit.dp(if (fill != null) 8 else 2), kit.dp(3))
    }

    private fun headline(value: String, color: Int, boxTint: Int) {
        statusHeadline.text = value
        statusHeadline.setTextColor(color)
        statusBox.background = kit.rounded(boxTint, 14f)
    }

    private fun halfWidth(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        topMargin = kit.dp(10)
        marginStart = kit.dp(start)
        marginEnd = kit.dp(end)
    }

    private fun refresh() {
        val connected = EchoRuntime.service != null
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        markStep(accessStep, 1, connected)
        markStep(micStep, 2, mic)
        setupCard.visibility = if (connected && mic) View.GONE else View.VISIBLE
        readyCard.visibility = if (connected && mic) View.VISIBLE else View.GONE

        val guard = when (val state = EchoRuntime.guard.currentState) {
            GuardState.Armed -> "armed"
            is GuardState.Tripped -> "handed off (${state.trip.kind})"
        }
        val snapshot = EchoRuntime.snapshots.current()
        val geminiOn = EchoRuntime.prefs.geminiKey.isNotBlank() || BuildConfig.GEMINI_API_KEY.isNotBlank()
        keyStatus.text = when {
            EchoRuntime.prefs.geminiKey.isNotBlank() -> "✓ Gemini is on with your key."
            geminiOn -> "✓ Gemini is on (key built into this APK)."
            else -> "Off: EchoFlow matches commands on the phone and confirms looser wordings first."
        }
        debugInfo.text = "Accessibility service: ${if (connected) "on" else "off"} · Mic: ${if (mic) "allowed" else "not allowed"}\n" +
            "Screen: ${snapshot?.packageName ?: "—"} · ${snapshot?.diagnostics?.summary() ?: "—"}\n" +
            "Verdict: ${EchoRuntime.guard.lastVerdict?.summary() ?: "—"} · Guard: $guard\n" +
            "Paraphrase AI (Gemini): ${if (!geminiOn) "off — no key, local matching only" else "on (${BuildConfig.GEMINI_MODEL})"}" +
            "\nRestricted settings hint: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "may apply" else "n/a"}"
        refreshFlowsAndRuns()
    }

    private fun refreshFlowsAndRuns() {
        val flows = EchoRuntime.orchestrator.flows.all()
        showExamples(flows)

        flowsBox.removeAllViews()
        if (flows.isEmpty()) flowsBox.addView(emptyFlowsCard())
        flows.forEach { flowsBox.addView(flowCard(it)) }

        lastRunBox.removeAllViews()
        lastRunBox.addView(lastRunCard(EchoRuntime.orchestrator.runs.last()))
    }

    // ---------------------------------------------------------------- examples

    private fun showExamples(flows: List<Flow>) {
        examplesBox.removeAllViews()
        if (flows.isNotEmpty()) {
            examplesTitle.text = "Try saying"
            flows.mapNotNull { it.examples.firstOrNull() }.distinct().take(3).forEach { ex ->
                examplesBox.addView(tapChip("“${ex.replaceFirstChar { it.uppercase() }}”", Palette.INK, Palette.CORAL_TINT) { send(ex) })
            }
        } else {
            examplesTitle.text = "Try teaching (tap to fill the box, then Send)"
            TEACH_EXAMPLES.forEach { ex ->
                examplesBox.addView(tapChip("“${ex.replaceFirstChar { it.uppercase() }}”", Palette.INK, Palette.BG) {
                    input.setText(ex)
                    input.setSelection(ex.length)
                    input.requestFocus()
                })
            }
        }
        listOf("What can you do?", "Did the last run succeed?").forEach { q ->
            examplesBox.addView(tapChip(q, Palette.INK, Palette.CORAL_TINT) { send(q) })
        }
    }

    /** A chip big enough to tap comfortably (48dp+), wrapping to two lines for long commands. */
    private fun tapChip(label: String, color: Int, tint: Int, onClick: () -> Unit) = kit.chip(label, color, tint, onClick).apply {
        textSize = 14f
        minHeight = kit.dp(48)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(kit.dp(14), kit.dp(8), kit.dp(14), kit.dp(8))
        (layoutParams as LinearLayout.LayoutParams).topMargin = kit.dp(8)
        contentDescription = "Say: $label"
    }

    // ---------------------------------------------------------------- learned flows

    private fun flowCard(f: Flow): View {
        val app = f.appLabel ?: f.appPackage
        val (circle, letterColor) = appColors(f)
        val icon = kit.iconCircle(app.firstOrNull()?.uppercase() ?: "?", circle, 44).apply {
            setTextColor(letterColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val chips = mutableListOf<View>(
            kit.chip(app, Palette.INK, Palette.LINE),
            kit.chip("${f.steps.size} steps", Palette.INK, Palette.LINE),
        )
        f.slots.forEach { chips += kit.chip(Words.slotName(it.name), MINT_DARK, Palette.MINT_TINT) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(kit.text(Words.template(f.template, appLabel = f.appLabel), 16f, bold = true))
            addView(kit.chipRows(chips, perRow = 3))
        }
        val chevron = kit.text("›", 24f, Palette.MUTED).apply { setPadding(kit.dp(8), 0, 0, 0) }
        return kit.card {
            addView(kit.row(icon, body, chevron, gravity = Gravity.CENTER_VERTICAL))
            minimumHeight = kit.dp(64)
            isClickable = true
            foreground = RippleDrawable(ColorStateList.valueOf(0x22000000), null, kit.rounded(Color.WHITE, 18f))
            contentDescription = "${Words.template(f.template, appLabel = f.appLabel).toString().replace(Regex("\\s+"), " ").trim()}, in $app, ${f.steps.size} steps. Open details."
            setOnClickListener { openFlow(f) }
        }
    }

    /** Circle colour and letter colour per app. */
    private fun appColors(f: Flow): Pair<Int, Int> {
        val key = "${f.appPackage} ${f.appLabel ?: ""}".lowercase()
        return when {
            "zomato" in key -> 0xFFE23744.toInt() to Color.WHITE
            "swiggy" in key -> 0xFFFC8019.toInt() to Palette.INK
            "amazon" in key -> Palette.GOLD to Palette.INK
            else -> Palette.INK to Color.WHITE
        }
    }

    private fun emptyFlowsCard() = kit.card {
        addView(kit.text("Nothing learned yet", 17f, bold = true))
        addView(kit.caption("Teach me a task once and I'll do it again whenever you ask.").apply { setPadding(0, kit.dp(2), 0, kit.dp(6)) })
        listOf(
            "Say \"teach\" and your command.",
            "Do it in the app, stop before paying.",
            "Tap ✓ Done.",
        ).forEachIndexed { i, step ->
            addView(kit.row(
                kit.iconCircle("${i + 1}", Palette.CORAL_TINT, 28).apply {
                    setTextColor(CORAL_DARK)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                },
                kit.body(step).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            ).apply { setPadding(0, kit.dp(6), 0, 0) })
        }
    }

    // ---------------------------------------------------------------- last run

    private fun lastRunCard(run: RunRecord?) = kit.card {
        if (run == null) {
            addView(kit.body("No runs yet."))
            addView(kit.caption("After you ask EchoFlow to do something, you'll see how it went here."))
            return@card
        }
        val (label, color, tint) = Words.status(run.status)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(run.startedAtMs))
        addView(kit.row(
            kit.chip(label, color, tint).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 },
            kit.caption(time),
        ))
        addView(kit.body(run.spokenSummary()).apply { setPadding(0, kit.dp(10), 0, 0) })
        addView(kit.secondaryButton("🔊  Say it") { send("what happened last time") }.apply {
            contentDescription = "Say what happened last time"
        })
        if (run.events.isNotEmpty()) {
            val events = kit.text(run.events.joinToString("\n") { "• $it" }, 12f, Palette.MUTED).apply {
                typeface = Typeface.MONOSPACE
                setPadding(kit.dp(12), kit.dp(10), kit.dp(12), kit.dp(10))
                background = kit.rounded(Palette.BG, 10f)
                visibility = if (showRunDetails) View.VISIBLE else View.GONE
            }
            val toggle = kit.text(if (showRunDetails) "Hide details" else "Details  ›", 14f, Palette.INK_SOFT, bold = true).apply {
                minHeight = kit.dp(48)
                accessibilityDelegate = Kit.ROLE_BUTTON
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, kit.dp(6), 0, kit.dp(6))
                isClickable = true
                contentDescription = if (showRunDetails) "Hide run details" else "Show run details"
            }
            toggle.setOnClickListener {
                showRunDetails = !showRunDetails
                events.visibility = if (showRunDetails) View.VISIBLE else View.GONE
                toggle.text = if (showRunDetails) "Hide details" else "Details  ›"
                toggle.contentDescription = if (showRunDetails) "Hide run details" else "Show run details"
            }
            addView(toggle)
            addView(events)
        }
    }

    private companion object {
        /** Darker shades of the palette for text, so it stays readable on white and tints. */
        const val MINT_DARK = 0xFF0E7C70.toInt()
        const val CORAL_DARK = 0xFFC8431F.toInt()
        const val GOLD_DARK = 0xFF8A5A00.toInt()

        val TEACH_EXAMPLES = listOf(
            "teach order a margherita pizza from dominos on zomato",
            "teach add a phone charger to my cart on amazon",
        )
    }
}
