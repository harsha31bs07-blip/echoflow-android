package com.echoflow.app.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import com.echoflow.app.EchoRuntime
import com.echoflow.app.orchestrator.Mode
import com.echoflow.app.orchestrator.Orchestrator
import com.echoflow.app.orchestrator.UiState

/**
 * Floating control panel (TYPE_ACCESSIBILITY_OVERLAY, so no overlay permission): Speak, Done
 * (while teaching), Stop, choice buttons for questions, and a status line. It works on top of
 * any app, which is where teaching and replay happen.
 *
 * Look: a compact dark ink card (at most ~300dp wide) with a coloured mode dot and status on top,
 * answer chips when EchoFlow asks something, and a row of round controls.
 */
class EchoBubble(
    private val service: AccessibilityService,
    private val orchestrator: Orchestrator,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val kit = Kit(service)
    private var root: FrameLayout? = null
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var speak: Button
    private lateinit var done: Button
    private lateinit var stop: Button
    private lateinit var move: Button
    private lateinit var choices: LinearLayout
    private lateinit var choiceScroll: HorizontalScrollView
    private var pulse: ObjectAnimator? = null
    private var atBottom = true

    // Siri-style minimal mode: a small handle at the edge when idle, a glow while busy.
    private lateinit var panel: LinearLayout
    private lateinit var handle: FrameLayout
    private val glow = EdgeGlow(service)
    private val main = Handler(Looper.getMainLooper())
    private var collapsed = false
    private var shownOnThisScreen = true
    private var lastMode = Mode.IDLE
    private val collapse = Runnable { if (lastMode == Mode.IDLE && minimal()) setCollapsed(true) }

    // Google Assistant-style listening panel while the microphone is open.
    private val listening = ListeningPanel(service) { EchoRuntime.service?.voice?.cancelListening() }
    private var listeningUi = false
    private var lastQuestion: String? = null
    private val endListening = Runnable {
        listeningUi = false
        root?.visibility = if (shownOnThisScreen) View.VISIBLE else View.GONE
        updateGlow()
    }

    /** Speech events from [com.echoflow.app.voice.VoiceIO]: open, update and close the listening panel. */
    fun onSpeech(e: com.echoflow.app.voice.SpeechUi) {
        if (e is com.echoflow.app.voice.SpeechUi.Ready) {
            main.removeCallbacks(endListening)
            listeningUi = true
            listening.show(lastQuestion.takeIf { lastMode == Mode.ASKING })
            // The panel replaces the bubble and the glow while listening.
            root?.visibility = View.INVISIBLE
            updateGlow()
        }
        listening.on(e)
        if (e is com.echoflow.app.voice.SpeechUi.Ended) main.postDelayed(endListening, 1_000)
    }

    fun show() {
        if (root != null) return

        dot = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.MUTED) }
            layoutParams = LinearLayout.LayoutParams(kit.dp(10), kit.dp(10)).apply {
                marginEnd = kit.dp(8)
                topMargin = kit.dp(5) // centred on the first line of 13sp text
            }
        }
        status = kit.text("", 13f, Color.WHITE).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = kit.dp(250)
            // TalkBack reads status changes and questions as they appear.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val statusRow = kit.row(dot, status, gravity = Gravity.TOP)

        choices = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        choiceScroll = HorizontalScrollView(service).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(kit.dp(16))
            addView(choices)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = kit.dp(10)
            }
        }

        speak = roundButton("🎤", Palette.CORAL, Color.WHITE, sizeDp = 48, visualDp = 48, textSp = 20f, label = "Speak") {
            orchestrator.onSpeakPressed()
        }
        done = pillButton("✓ Done", Palette.MINT, Palette.INK) { orchestrator.onDonePressed() }
        stop = roundButton("■", Palette.RED, Color.WHITE, sizeDp = 48, visualDp = 40, textSp = 15f, label = "Stop") {
            orchestrator.onStopPressed()
        }
        val home = roundButton("⌂", Palette.INK_SOFT, UTILITY_TEXT, sizeDp = 48, visualDp = 34, textSp = 16f, label = "Open EchoFlow") {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        move = roundButton("⇅", Palette.INK_SOFT, UTILITY_TEXT, sizeDp = 48, visualDp = 34, textSp = 16f, label = "Move to the other corner") {
            atBottom = !atBottom
            root?.let { wm.updateViewLayout(it, params()) }
        }
        // Main actions on the left, the two small helpers pushed to the right edge.
        val spacer = View(service).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val controls = kit.row(speak, done, stop, spacer, home, move).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = kit.dp(10)
            }
        }

        panel = object : LinearLayout(service) {
            // Never wider than ~300dp, so the panel can't cover the app's own buttons.
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = kit.dp(MAX_WIDTH_DP)
                val size = MeasureSpec.getSize(widthMeasureSpec)
                val spec = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || size > cap) {
                    MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
                } else widthMeasureSpec
                super.onMeasure(spec, heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(14), kit.dp(12), kit.dp(12), kit.dp(12))
            background = kit.rounded(PANEL_BG, 24f, PANEL_EDGE, 1f)
            elevation = kit.dp(6).toFloat()
            addView(statusRow)
            addView(choiceScroll)
            addView(controls)
        }

        handle = buildHandle()

        // A thin transparent margin gives the panel's shadow room to draw inside the window.
        root = FrameLayout(service).apply {
            val m = kit.dp(SHADOW_ROOM_DP)
            setPadding(m, m, m, m)
            clipToPadding = false
            addView(panel)
            addView(handle)
        }
        handle.visibility = View.GONE
        // Until the first render: just Speak and the helpers.
        done.visibility = View.GONE
        stop.visibility = View.GONE
        wm.addView(root, params())
        if (minimal()) main.postDelayed(collapse, FIRST_COLLAPSE_MS)
    }

    fun setVisible(visible: Boolean) {
        shownOnThisScreen = visible
        root?.visibility = if (!visible) View.GONE else if (listeningUi) View.INVISIBLE else View.VISIBLE
        updateGlow()
    }

    fun hide() {
        main.removeCallbacks(endListening)
        listening.hide()
        main.removeCallbacks(collapse)
        glow.hide()
        stopPulse()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
    }

    fun render(s: UiState) {
        if (root == null) return
        lastMode = s.mode
        lastQuestion = s.question
        // Minimal mode: open whenever EchoFlow is busy; tuck away a few seconds after it's done.
        main.removeCallbacks(collapse)
        // (Longer messages stay up longer, so "Your turn. Everything is ready…" can be read.)
        if (!minimal() || s.mode != Mode.IDLE) setCollapsed(false) else if (!collapsed) main.postDelayed(collapse, readingTime(s.status))
        updateGlow()
        val listening = s.mode == Mode.LISTENING || (s.mode == Mode.TEACHING && s.status.startsWith("Listening"))
        val dotColor = when (s.mode) {
            Mode.TEACHING, Mode.LISTENING -> Palette.CORAL
            Mode.RUNNING -> Palette.MINT
            Mode.ASKING -> Palette.GOLD
            Mode.IDLE -> Palette.MUTED
        }
        (dot.background as GradientDrawable).setColor(dotColor)
        if (listening) startPulse() else stopPulse()

        status.text = when (s.mode) {
            Mode.TEACHING -> "Recording: ${s.status}"
            Mode.LISTENING -> s.status.ifBlank { "Listening…" }
            Mode.ASKING -> s.question?.takeIf { it.isNotBlank() } ?: s.status
            else -> s.status
        }

        done.visibility = if (s.mode == Mode.TEACHING) View.VISIBLE else View.GONE
        // Speak, Done, Stop and Open EchoFlow fill the panel while teaching; "move" waits.
        move.visibility = if (s.mode == Mode.TEACHING) View.GONE else View.VISIBLE
        // Questions are shown in full (they're also spoken); status lines stay short.
        status.maxLines = if (s.mode == Mode.ASKING) 6 else 2
        stop.visibility = if (s.mode == Mode.RUNNING || s.mode == Mode.TEACHING || s.mode == Mode.ASKING) View.VISIBLE else View.GONE
        speak.isEnabled = s.mode == Mode.IDLE || s.mode == Mode.TEACHING
        speak.alpha = if (speak.isEnabled) 1f else 0.4f

        choices.removeAllViews()
        s.choices.take(4).forEach { c -> choices.addView(choiceChip(c)) }
        choiceScroll.visibility = if (s.choices.isEmpty()) View.GONE else View.VISIBLE
        if (s.choices.isNotEmpty()) choiceScroll.scrollTo(0, 0)
    }

    // --- minimal mode ---------------------------------------------------------------------------

    private fun minimal() = EchoRuntime.prefs.minimalBubble

    private fun readingTime(text: String) = (IDLE_COLLAPSE_MS + text.length * 60L).coerceAtMost(MAX_READ_MS)

    /** Re-reads the minimal-mode setting (it changed in EchoFlow's settings). */
    fun refreshMode() {
        main.removeCallbacks(collapse)
        if (!minimal()) setCollapsed(false) else if (lastMode == Mode.IDLE) main.postDelayed(collapse, IDLE_COLLAPSE_MS)
        updateGlow()
    }

    private fun setCollapsed(value: Boolean) {
        if (collapsed == value || root == null) return
        collapsed = value
        panel.visibility = if (value) View.GONE else View.VISIBLE
        handle.visibility = if (value) View.VISIBLE else View.GONE
        // The handle sits flush against the edge; the panel keeps room for its shadow.
        val m = if (value) 0 else kit.dp(SHADOW_ROOM_DP)
        root?.setPadding(m, m, m, m)
        root?.let { runCatching { wm.updateViewLayout(it, params()) } }
    }

    private fun updateGlow() {
        val color = when {
            !minimal() || !shownOnThisScreen || listeningUi -> null
            lastMode == Mode.TEACHING || lastMode == Mode.LISTENING -> Palette.CORAL
            lastMode == Mode.RUNNING -> Palette.MINT
            lastMode == Mode.ASKING -> Palette.GOLD
            else -> null
        }
        glow.set(color)
    }

    /**
     * The tucked-away bubble: a slim pill at the screen's edge (faint, so it doesn't get in the
     * way) inside a full 48dp touch target. Tap: open and listen. Long-press: just open.
     */
    private fun buildHandle() = FrameLayout(service).apply {
        val pill = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = kit.rounded(HANDLE_FILL, 4f, HANDLE_EDGE, 1f)
            layoutParams = FrameLayout.LayoutParams(kit.dp(7), kit.dp(56), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = kit.dp(3)
            }
        }
        addView(pill)
        layoutParams = FrameLayout.LayoutParams(kit.dp(48), kit.dp(72))
        contentDescription = "EchoFlow. Tap to speak, or long-press to open the controls."
        isClickable = true
        isLongClickable = true
        setOnClickListener {
            setCollapsed(false)
            orchestrator.onSpeakPressed()
        }
        setOnLongClickListener {
            setCollapsed(false)
            main.removeCallbacks(collapse)
            main.postDelayed(collapse, IDLE_COLLAPSE_MS)
            true
        }
    }

    // --- pieces -------------------------------------------------------------------------------

    /** Ripple over [shape], white-ish so it shows on the dark panel and on coloured buttons. */
    private fun pressable(shape: Drawable) = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null)

    private fun baseButton(label: String, textColor: Int, textSp: Float, onClick: () -> Unit) = Button(service).apply {
        text = label
        textSize = textSp
        isAllCaps = false
        setTextColor(textColor)
        gravity = Gravity.CENTER
        includeFontPadding = false
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    /**
     * A round icon button: a [visualDp] circle centred in a [sizeDp] square, so small-looking
     * buttons still have a full-size touch target.
     */
    private fun roundButton(
        symbol: String,
        fill: Int,
        textColor: Int,
        sizeDp: Int,
        visualDp: Int,
        textSp: Float,
        label: String,
        onClick: () -> Unit,
    ) = baseButton(symbol, textColor, textSp, onClick).apply {
        contentDescription = label
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        val inset = kit.dp((sizeDp - visualDp) / 2f)
        background = pressable(InsetDrawable(circle, inset))
        layoutParams = LinearLayout.LayoutParams(kit.dp(sizeDp), kit.dp(sizeDp)).apply { marginEnd = kit.dp(4) }
    }

    private fun pillButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit) =
        baseButton(label, textColor, 15f, onClick).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pressable(kit.rounded(fill, 22f))
            setPadding(kit.dp(14), 0, kit.dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, kit.dp(48)).apply {
                marginStart = kit.dp(4)
                marginEnd = kit.dp(6)
            }
        }

    /** A gold answer chip; long answers are shortened on screen but read out in full. */
    private fun choiceChip(choice: String) = baseButton(
        if (choice.length > MAX_CHOICE_CHARS) choice.take(MAX_CHOICE_CHARS - 1).trimEnd() + "…" else choice,
        Palette.INK,
        14f,
    ) { orchestrator.onChoice(choice) }.apply {
        contentDescription = choice
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        background = pressable(kit.rounded(Palette.GOLD_TINT, 22f, Palette.GOLD, 1.5f))
        setPadding(kit.dp(14), 0, kit.dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, kit.dp(48)).apply {
            marginEnd = kit.dp(8)
        }
    }

    /** A gentle breathing dot while EchoFlow is listening. */
    private fun startPulse() {
        if (pulse?.isRunning == true) return
        // "Remove animations" in the phone's accessibility settings: keep the dot still.
        val scale = android.provider.Settings.Global.getFloat(service.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale == 0f) return
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 550
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        if (::dot.isInitialized) dot.alpha = 1f
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Compact panel in a corner, clear of most apps' main buttons; ⇅ flips top/bottom.
        // Offsets subtract the shadow margin so the visible card sits where it always has.
        gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.END
        y = kit.dp((if (atBottom) 150 else 40) - SHADOW_ROOM_DP)
        x = 0
    }

    private companion object {
        const val MAX_WIDTH_DP = 300
        /** Idle this long after a run or an answer: tuck into the edge handle. */
        const val IDLE_COLLAPSE_MS = 6_000L
        const val MAX_READ_MS = 15_000L
        /** After the service starts, show the full panel briefly so people see where it is. */
        const val FIRST_COLLAPSE_MS = 8_000L
        /** Ink at ~55% with a light edge: visible on light and dark apps without shouting. */
        const val HANDLE_FILL = (0x8C shl 24) or (Palette.INK and 0x00FFFFFF)
        const val HANDLE_EDGE = 0x66FFFFFF
        const val MAX_CHOICE_CHARS = 28
        const val SHADOW_ROOM_DP = 6
        /** Palette.INK at ~92% opacity. */
        const val PANEL_BG = (0xEB shl 24) or (Palette.INK and 0x00FFFFFF)
        /** A faint light edge so the panel reads on dark apps too. */
        const val PANEL_EDGE = 0x24FFFFFF
        /** Light text for the small helper buttons (high contrast on INK_SOFT). */
        const val UTILITY_TEXT = 0xFFD5D8EA.toInt()
    }
}
