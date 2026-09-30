package com.echoflow.app.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.echoflow.core.model.Bounds

/**
 * At "Your turn": a soft pulsing outline around the app's Pay / Place order button, so the user
 * sees what's left to do. Display only: it never receives touches, and EchoFlow never taps it.
 */
class PayHighlight(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hideLater = Runnable { hide() }

    fun show(b: Bounds, forMs: Long = 10_000) = main.post {
        hide()
        val pad = dp(6)
        val v = Ring()
        val lp = WindowManager.LayoutParams(
            b.width + pad * 2, b.height + pad * 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left - pad
            y = b.top - pad
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) {
            view = v
            main.postDelayed(hideLater, forMs)
        }
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
    }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private inner class Ring : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
            color = 0xFF2BC4B0.toInt() // EchoFlow mint: "this part is yours"
        }
        private val start = System.currentTimeMillis()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val reduced = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            val t = (System.currentTimeMillis() - start) % 1_400L / 1_400f
            paint.alpha = if (reduced) 230 else (140 + 115 * kotlin.math.sin(t * Math.PI * 2).toFloat().let { (it + 1) / 2 }).toInt()
            val inset = paint.strokeWidth / 2
            canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), dp(14).toFloat(), dp(14).toFloat(), paint)
            if (!reduced) postInvalidateOnAnimation()
        }
    }
}
