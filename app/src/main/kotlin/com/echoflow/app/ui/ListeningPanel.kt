package com.echoflow.app.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.echoflow.app.EchoRuntime
import com.echoflow.app.voice.SpeechUi
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * The listening screen, in the spirit of Google Assistant: while the microphone is open, a
 * panel rises from the bottom with four coral dots that bob while waiting, turn into bars
 * that move with your voice, and ripple while the words are recognised. The words appear live
 * as you speak. (The rest of EchoFlow keeps the Siri-style edge glow.)
 */
class ListeningPanel(private val service: AccessibilityService, private val onCancel: () -> Unit) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val kit = Kit(service)
    private var root: FrameLayout? = null
    private lateinit var prompt: TextView
    private lateinit var words: TextView
    private lateinit var dots: VoiceDots
    private lateinit var card: LinearLayout
    val showing: Boolean get() = root != null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Closes the panel a moment after listening ends; cancelled if listening starts again. */
    private val closeLater = Runnable { hide() }

    /** Opens the panel; [question] is shown above the words when EchoFlow asked something. */
    fun show(question: String?) {
        // A new listening session: the previous one's delayed close must not close this one.
        main.removeCallbacks(closeLater)
        if (root != null) {
            prompt.text = question ?: "Listening…"
            words.text = hint()
            words.setTextColor(HINT_COLOR)
            dots.level = 0f
            dots.mode = VoiceDots.Mode.WAITING
            return
        }
        prompt = kit.text(question ?: "Listening…", 14f, PROMPT).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        words = kit.text(hint(), 22f, HINT_COLOR, bold = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.START // keep the newest words in view
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, kit.dp(10), 0, kit.dp(6))
        }
        dots = VoiceDots(service).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kit.dp(64))
        }
        val cancel = TextView(service).apply {
            text = "✕"
            textSize = 18f
            setTextColor(PROMPT)
            gravity = Gravity.CENTER
            contentDescription = "Stop listening"
            accessibilityDelegate = Kit.ROLE_BUTTON
            background = kit.rounded(0x1FFFFFFF, 24f)
            // Inside the rounded corner, not over it.
            layoutParams = FrameLayout.LayoutParams(kit.dp(48), kit.dp(48), Gravity.TOP or Gravity.END).apply {
                topMargin = kit.dp(10)
                marginEnd = kit.dp(10)
            }
            setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); onCancel() }
        }
        card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            // Side padding clears the ✕ so centred text never runs under it.
            setPadding(kit.dp(60), kit.dp(22), kit.dp(60), kit.dp(18))
            addView(prompt)
            addView(words)
            addView(dots)
        }
        val sheet = FrameLayout(service).apply {
            background = kit.rounded(SHEET, 28f, 0x24FFFFFF, 1f)
            elevation = kit.dp(8).toFloat()
            addView(card)
            addView(cancel)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                val m = kit.dp(10)
                setMargins(m, m, m, m + kit.dp(8))
            }
        }
        root = FrameLayout(service).apply { addView(sheet) }
        runCatching { wm.addView(root, params()) }.onFailure { root = null; return }
        dots.start(reducedMotion())
        // Rise from the bottom (instant with "Remove animations").
        if (!reducedMotion()) {
            sheet.translationY = kit.dp(160).toFloat()
            sheet.alpha = 0f
            sheet.animate().translationY(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun on(event: SpeechUi) {
        if (root == null) return
        when (event) {
            SpeechUi.Ready -> dots.mode = VoiceDots.Mode.WAITING
            is SpeechUi.Level -> dots.level = event.value
            is SpeechUi.Partial -> {
                words.text = event.text.replaceFirstChar { it.uppercase() }
                words.setTextColor(Color.WHITE)
                dots.mode = VoiceDots.Mode.SPEAKING
            }
            SpeechUi.Thinking -> dots.mode = VoiceDots.Mode.THINKING
            is SpeechUi.Ended -> {
                if (event.text.isNullOrBlank() && EchoRuntime.service?.voice?.lastCancelled != true) {
                    words.text = "I didn't catch that"
                    words.setTextColor(HINT_COLOR)
                }
                dots.mode = VoiceDots.Mode.THINKING
                // Leave the final words up a moment, then go (unless listening starts again).
                main.removeCallbacks(closeLater)
                main.postDelayed(closeLater, if (event.text.isNullOrBlank()) 900L else 600L)
            }
        }
    }

    /** One of the user's own learned commands as the example, or how to teach one. */
    private fun hint(): String {
        val example = runCatching { EchoRuntime.orchestrator.flows.all() }.getOrNull()
            ?.randomOrNull()?.examples?.firstOrNull()
            // "…from brik oven on zomato" -> "…from brik oven": short enough to read at a glance.
            ?.let { e -> if (e.length > 36) e.replace(Regex("\\s+(on|in|using)\\s+\\w+$"), "") else e }
            ?.takeIf { it.length <= 52 }
        return example?.let { "Try “${it.replaceFirstChar { c -> c.uppercase() }}”" } ?: HINT
    }

    fun hide() {
        main.removeCallbacks(closeLater)
        val r = root ?: return
        root = null
        dots.stop()
        runCatching { wm.removeViewImmediate(r) }
    }

    private fun reducedMotion() =
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM }

    private companion object {
        const val HINT = "Say a command, or “teach” and a new one"
        const val SHEET = 0xFF161A33.toInt() // solid ink, so nothing shows through the words
        const val PROMPT = 0xFFC9CCE0.toInt()
        const val HINT_COLOR = 0xFF8D92AD.toInt()
    }
}

/**
 * Four coral dots: they bob gently while waiting, stretch into bars that follow the voice
 * level while you speak, and ripple one after another while the words are recognised.
 */
class VoiceDots(context: Context) : View(context) {
    enum class Mode { WAITING, SPEAKING, THINKING }

    @Volatile var mode = Mode.WAITING
    /** Latest voice level (0..1); smoothed while drawing. */
    @Volatile var level = 0f

    private val density = resources.displayMetrics.density
    private val paints = COLORS.map { c -> Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c } }
    private val rect = RectF()
    private var shown = 0f
    private var t = 0f
    private var animator: ValueAnimator? = null
    private var still = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(reducedMotion: Boolean) {
        still = reducedMotion
        if (still) { invalidate(); return }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t += 0.016f; invalidate() }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val r = 7f * density            // dot radius
        val gap = 14f * density
        val w = r * 2
        val total = COLORS.size * w + (COLORS.size - 1) * gap
        var x = (width - total) / 2f
        val cy = height / 2f
        // Ease the displayed level toward the latest one, so bars move smoothly.
        shown += (level - shown) * 0.25f
        paints.forEachIndexed { i, p ->
            val phase = i * (PI.toFloat() / 2.2f)
            val (h, dy) = when {
                still -> w to 0f
                mode == Mode.SPEAKING -> {
                    val wobble = 0.55f + 0.45f * sin(t * 9f + phase)
                    max(w, w + shown * 30f * density * wobble) to 0f
                }
                mode == Mode.THINKING -> w to -6f * density * max(0f, sin(t * 7f - i * 0.9f))
                else -> w to 3f * density * sin(t * 3.2f + phase)
            }
            rect.set(x, cy - h / 2 + dy, x + w, cy + h / 2 + dy)
            canvas.drawRoundRect(rect, r, r, p)
            x += w + gap
        }
    }

    private companion object {
        // EchoFlow's own coral (the deck's bright shade reads well on the dark panel).
        val COLORS = List(4) { 0xFFFF6B4A.toInt() }
    }
}
