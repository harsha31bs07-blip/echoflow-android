package com.echoflow.app.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * A soft glow around the screen's edge while EchoFlow is busy (listening, recording, working,
 * asking), in the spirit of Siri on iPhone: nothing on screen when idle, an unmistakable
 * edge when active. Purely visual: the window never takes touches (FLAG_NOT_TOUCHABLE), is
 * invisible to TalkBack, and is ignored by EchoFlow's own screen reading (overlay windows
 * aren't app windows).
 */
class EdgeGlow(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: GlowView? = null
    private var color: Int? = null

    /** Shows the glow in [color], or hides it when null. */
    fun set(color: Int?) {
        if (color == this.color) return
        this.color = color
        val still = reducedMotion()
        if (color == null) {
            // Fade out, then stop drawing (at once with "Remove animations").
            view?.let { v ->
                v.animate().cancel()
                if (still) { v.stop(); v.visibility = View.GONE }
                else v.animate().alpha(0f).setDuration(220).withEndAction {
                    if (this.color == null) { v.stop(); v.visibility = View.GONE }
                }.start()
            }
            return
        }
        val v = view ?: GlowView(service).also {
            view = it
            runCatching { wm.addView(it, params()) }.onFailure { view = null; return }
        }
        val wasHidden = v.visibility != View.VISIBLE || v.alpha < 1f
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.start(color, still)
        // Fade in when it appears; a colour change while shown just switches.
        if (wasHidden && !still) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(260).start()
        } else {
            v.alpha = 1f
        }
    }

    fun hide() {
        view?.let { v -> v.stop(); runCatching { wm.removeViewImmediate(v) } }
        view = null
        color = null
    }

    private fun reducedMotion() =
        Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Three nested rounded strokes (wide and faint → thin and bright) under a turning gradient. */
    private class GlowView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paints = listOf(14f to 0x40, 7f to 0x80, 2.5f to 0xFF).map { (w, a) ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * density; alpha = a }
        }
        private val rect = RectF()
        private val rotation = Matrix()
        private var angle = 0f
        private var animator: ValueAnimator? = null
        private var shader: SweepGradient? = null
        private var tint = 0

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun start(color: Int, still: Boolean) {
            tint = color
            buildShader()
            animator?.cancel()
            animator = if (still) null else ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 4_000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
            invalidate()
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildShader()

        /** The turning gradient for the current colour and size (not rebuilt while drawing). */
        private fun buildShader() {
            if (width == 0 || height == 0) return
            shader = SweepGradient(width / 2f, height / 2f, intArrayOf(tint, lighter(tint), tint, 0x00FFFFFF and tint, tint), null).also { g ->
                paints.forEach { it.shader = g }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = shader ?: return
            rotation.setRotate(angle, w / 2, h / 2)
            s.setLocalMatrix(rotation)
            // Phones' screens have rounded corners; follow them roughly.
            val radius = 36f * density
            paints.forEach { p ->
                val inset = p.strokeWidth / 2
                rect.set(inset, inset, w - inset, h - inset)
                canvas.drawRoundRect(rect, radius, radius, p)
            }
        }

        private fun lighter(c: Int): Int {
            fun mix(v: Int) = v + (255 - v) * 45 / 100
            return (0xFF shl 24) or (mix((c shr 16) and 0xFF) shl 16) or (mix((c shr 8) and 0xFF) shl 8) or mix(c and 0xFF)
        }
    }
}
