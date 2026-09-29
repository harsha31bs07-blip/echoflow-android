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
 * Look: a compact deep-ink card (at most ~300dp wide, soft gradient, faint edge) with a mode
 * label ("Ready", "Recording", "Working", "Question") tinted like the edge glow, the status line,
 * answer chips when EchoFlow asks something, and a row of round controls with drawn line icons;
 * the main one, Speak, is a coral gradient disc with a sound-wave icon and a soft ring.
 */
class EchoBubble(
    private val service: AccessibilityService,
    private val orchestrator: Orchestrator,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val kit = Kit(service)
    private var root: FrameLayout? = null
    private lateinit var modeLabel: TextView
    private lateinit var status: TextView
    private lateinit var speak: View
    private lateinit var done: Button
    private lateinit var stop: View
    private lateinit var move: View
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

    // Google Assistant-style listening panel while the microphone is open. Its ✕ stops
    // listening and folds EchoFlow back into the handle (while teaching, it only stops listening:
    // the lesson needs ✓ Done).
    private val listening = ListeningPanel(service) { cancelFromPanel() }

    /** The listening panel's ✕. */
    private fun cancelFromPanel() {
        foldAfterListening = lastMode != Mode.TEACHING
        EchoRuntime.service?.voice?.cancelListening()
        // Respond at once: close the panel and bring the handle back now, not a second later,
        // so it can be tapped again straight away.
        listening.hide()
        main.removeCallbacks(endListening)
        endListening.run()
    }
    private var listeningUi = false
    private var foldAfterListening = false
    private var lastQuestion: String? = null
    private val endListening = Runnable {
        listeningUi = false
        applyVisibility()
        updateGlow()
        if (foldAfterListening) {
            foldAfterListening = false
            main.removeCallbacks(collapse)
            if (minimal()) setCollapsed(true)
        }
    }

    /** The bubble's ✕: stop any task, question or lesson, then fold into the edge handle. */
    private fun closeAll() {
        // The stopping task may report "running" once more on its way out: stay folded meanwhile.
        closedAt = android.os.SystemClock.uptimeMillis()
        if (lastMode != Mode.IDLE) orchestrator.onStopPressed()
        main.removeCallbacks(collapse)
        if (minimal()) setCollapsed(true)
        updateGlow()
        // Once the quiet period is over, show the glow again if something is still going on.
        main.postDelayed({ updateGlow() }, CLOSE_QUIET_MS + 100)
    }
    private var closedAt = 0L
    private fun justClosed() = android.os.SystemClock.uptimeMillis() - closedAt < CLOSE_QUIET_MS

    /** Speech events from [com.echoflow.app.voice.VoiceIO]: open, update and close the listening panel. */
    fun onSpeech(e: com.echoflow.app.voice.SpeechUi) {
        if (e is com.echoflow.app.voice.SpeechUi.Ready) {
            main.removeCallbacks(endListening)
            // A new session: a ✕ from the previous one doesn't carry over.
            foldAfterListening = false
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

        // Mode label ("● Working"), tinted like the edge glow for that mode.
        modeLabel = kit.text("● Ready", 12f, MODE_READY, bold = true).apply {
            setPadding(kit.dp(10), kit.dp(4), kit.dp(10), kit.dp(4))
            background = kit.rounded(tint(MODE_READY), 12f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the status line says it
        }
        // ✕: stop whatever is going on and fold back into the edge handle, any time.
        val close = iconButton(Glyph.Kind.CLOSE, plainDisc(CONTROL_BG), UTILITY_TEXT, sizeDp = 48, visualDp = 30, iconDp = 16, label = "Close: stop and hide EchoFlow") {
            closeAll()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                marginEnd = -kit.dp(8)
                topMargin = -kit.dp(8)
                bottomMargin = -kit.dp(8)
            }
        }
        val headSpacer = View(service).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val statusRow = kit.row(modeLabel, headSpacer, close).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        status = kit.text("", 14f, Color.WHITE).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(kit.dp(2), kit.dp(8), 0, 0)
            // TalkBack reads status changes and questions as they appear.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

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

        // Speak: the main action. A coral gradient disc with a sound-wave icon and a soft ring.
        speak = iconButton(Glyph.Kind.WAVES, speakDisc(), Color.WHITE, sizeDp = 56, visualDp = 56, iconDp = 26, label = "Speak") {
            orchestrator.onSpeakPressed()
        }
        done = pillButton("Done", Palette.MINT, Color.WHITE) { orchestrator.onDonePressed() }.apply {
            val check = Glyph(Glyph.Kind.CHECK, Color.WHITE, kit.dp(18)).apply { setBounds(0, 0, kit.dp(18), kit.dp(18)) }
            setCompoundDrawablesRelative(check, null, null, null)
            compoundDrawablePadding = kit.dp(6)
        }
        stop = iconButton(Glyph.Kind.STOP, plainDisc(Palette.RED), Color.WHITE, sizeDp = 48, visualDp = 42, iconDp = 22, label = "Stop") {
            orchestrator.onStopPressed()
        }
        val home = iconButton(Glyph.Kind.HOME, plainDisc(CONTROL_BG), UTILITY_TEXT, sizeDp = 48, visualDp = 36, iconDp = 20, label = "Open EchoFlow") {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        move = iconButton(Glyph.Kind.MOVE, plainDisc(CONTROL_BG), UTILITY_TEXT, sizeDp = 48, visualDp = 36, iconDp = 20, label = "Move to the other corner") {
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
            setPadding(kit.dp(16), kit.dp(14), kit.dp(14), kit.dp(14))
            // Deep ink with a gentle top-to-bottom gradient and a faint light edge.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(PANEL_TOP, PANEL_BOTTOM)).apply {
                cornerRadius = kit.dp(26).toFloat()
                setStroke(kit.dp(1), PANEL_EDGE)
            }
            elevation = kit.dp(10).toFloat()
            addView(statusRow)
            addView(status)
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
        applyVisibility()
    }

    /** The notification shade or lock screen covers the screen. */
    private var shadeOpen = false

    fun setShadeOpen(open: Boolean) {
        if (shadeOpen == open) return
        shadeOpen = open
        applyVisibility()
    }

    private fun applyVisibility() {
        root?.visibility = when {
            !shownOnThisScreen || shadeOpen -> View.GONE
            listeningUi -> View.INVISIBLE
            else -> View.VISIBLE
        }
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
        // Safety net: never stay hidden for a listening panel that isn't actually on screen.
        if (listeningUi && !listening.showing) {
            listeningUi = false
            applyVisibility()
        }
        // Minimal mode: open whenever EchoFlow is busy; tuck away a few seconds after it's done.
        main.removeCallbacks(collapse)
        // (Longer messages stay up longer, so "Your turn. Everything is ready…" can be read.)
        when {
            !minimal() -> setCollapsed(false)
            justClosed() -> Unit // ✕ was just tapped: stay folded while the task winds down
            s.mode != Mode.IDLE -> setCollapsed(false)
            !collapsed -> main.postDelayed(collapse, readingTime(s.status))
        }
        updateGlow()
        val listening = s.mode == Mode.LISTENING || (s.mode == Mode.TEACHING && s.status.startsWith("Listening"))
        val (label, color) = when (s.mode) {
            Mode.TEACHING -> "Recording" to MODE_CORAL
            Mode.LISTENING -> "Listening" to MODE_CORAL
            Mode.RUNNING -> "Working" to MODE_MINT
            Mode.ASKING -> "Question" to MODE_GOLD
            Mode.IDLE -> "Ready" to MODE_READY
        }
        modeLabel.text = "●  $label"
        modeLabel.setTextColor(color)
        modeLabel.background = kit.rounded(tint(color), 12f)
        if (listening || s.mode == Mode.TEACHING) startPulse() else stopPulse()

        status.text = when (s.mode) {
            Mode.TEACHING -> s.status // the "Recording" label above says the mode
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
        // Gentle transitions (none with "Remove animations"): the panel grows out of its corner,
        // the handle fades in.
        if (reducedMotion()) return
        if (value) {
            handle.alpha = 0f
            handle.animate().alpha(1f).setDuration(220).start()
        } else {
            panel.pivotX = panel.width.takeIf { it > 0 }?.toFloat() ?: kit.dp(280).toFloat()
            panel.pivotY = if (atBottom) (panel.height.takeIf { it > 0 }?.toFloat() ?: kit.dp(160).toFloat()) else 0f
            panel.alpha = 0f
            panel.scaleX = 0.92f
            panel.scaleY = 0.92f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /** Touches pass through the panel (EchoFlow's own gestures must reach the app underneath). */
    private var passThrough = false

    fun setPassThrough(on: Boolean) {
        if (passThrough == on) return
        passThrough = on
        root?.let { runCatching { wm.updateViewLayout(it, params()) } }
    }

    private fun reducedMotion() =
        android.provider.Settings.Global.getFloat(service.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** A light tick under the finger for the main buttons. */
    private fun tick(v: View) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun updateGlow() {
        val color = when {
            !minimal() || !shownOnThisScreen || shadeOpen || listeningUi || justClosed() -> null
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
            tick(it)
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
        setOnClickListener { tick(it); onClick() }
    }

    /**
     * A round icon button: a [visualDp] disc centred in a [sizeDp] square (so small-looking
     * buttons still have a full-size touch target), with a drawn [Glyph] in the middle.
     */
    private fun iconButton(
        kind: Glyph.Kind,
        disc: Drawable,
        iconColor: Int,
        sizeDp: Int,
        visualDp: Int,
        iconDp: Int,
        label: String,
        onClick: () -> Unit,
    ) = android.widget.ImageButton(service).apply {
        contentDescription = label
        setImageDrawable(Glyph(kind, iconColor, kit.dp(iconDp)))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        val inset = kit.dp((sizeDp - visualDp) / 2f)
        background = pressable(InsetDrawable(disc, inset))
        layoutParams = LinearLayout.LayoutParams(kit.dp(sizeDp), kit.dp(sizeDp)).apply { marginEnd = kit.dp(4) }
        setOnClickListener { tick(it); onClick() }
    }

    private fun plainDisc(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Speak: a coral disc lit from the top-left, inside a soft coral ring. */
    private fun speakDisc(): Drawable {
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FF6B4A) }
        val disc = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF7A52.toInt(), Palette.CORAL)).apply {
            shape = GradientDrawable.OVAL
        }
        val ringWidth = kit.dp(4)
        return android.graphics.drawable.LayerDrawable(arrayOf(ring, disc)).apply {
            setLayerInset(1, ringWidth, ringWidth, ringWidth, ringWidth)
        }
    }

    /** A colour at low opacity, for the mode label's background. */
    private fun tint(color: Int) = (0x2E shl 24) or (color and 0x00FFFFFF)

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
        pulse = ObjectAnimator.ofFloat(modeLabel, View.ALPHA, 1f, 0.55f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        if (::modeLabel.isInitialized) modeLabel.alpha = 1f
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
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
        /** After ✕, ignore "still busy" states from the stopping task for this long. */
        const val CLOSE_QUIET_MS = 2_500L
        const val MAX_READ_MS = 15_000L
        /** After the service starts, show the full panel briefly so people see where it is. */
        const val FIRST_COLLAPSE_MS = 8_000L
        /** Ink at ~55% with a light edge: visible on light and dark apps without shouting. */
        const val HANDLE_FILL = (0x8C shl 24) or (Palette.INK and 0x00FFFFFF)
        /** A coral edge: the tucked-away handle still says "EchoFlow". */
        const val HANDLE_EDGE = 0xCCFF6B4A.toInt()
        const val MAX_CHOICE_CHARS = 28
        const val SHADOW_ROOM_DP = 6
        /** Deep ink, a touch lighter at the top (~96% opaque). */
        const val PANEL_TOP = 0xF5262B4D.toInt()
        const val PANEL_BOTTOM = 0xF5141830.toInt()
        /** A faint light edge so the panel reads on dark apps too. */
        const val PANEL_EDGE = 0x2EFFFFFF
        /** Small round controls (home, move, close). */
        const val CONTROL_BG = 0xFF30365C.toInt()
        /** Mode label colours: bright enough on the dark panel (all ≥ 4.5:1). */
        const val MODE_READY = 0xFFC9CCE0.toInt()
        const val MODE_CORAL = 0xFFFF8A6B.toInt()
        const val MODE_MINT = 0xFF3DD6C4.toInt()
        const val MODE_GOLD = 0xFFF4B63F.toInt()
        /** Light text for the small helper buttons (high contrast on INK_SOFT). */
        const val UTILITY_TEXT = 0xFFD5D8EA.toInt()
    }
}
