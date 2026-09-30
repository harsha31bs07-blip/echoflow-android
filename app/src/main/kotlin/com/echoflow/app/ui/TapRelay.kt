package com.echoflow.app.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

/**
 * Teaching in apps that don't report their taps (W1): a clear layer over the screen catches each
 * touch, lets [onTap] record the element under a tap, and then plays the same touch to the app as
 * a gesture (the layer lets touches through while it does). Swipes are passed on as swipes and
 * not recorded. [onTap] returning false means "don't pass this one on" (a pay button).
 */
class TapRelay(
    private val service: AccessibilityService,
    private val onTap: (x: Int, y: Int) -> Boolean,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    @Volatile private var keyboardUp = false

    val active: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun start() = main.post {
        if (view != null) return@post
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        val v = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener(Catcher())
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) {
            view = v
            params = lp
            applyTouchable()
        }
    }

    fun stop() = main.post {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    /** The keyboard is showing: let typing go straight to it (typing is recorded anyway). */
    fun setKeyboardUp(up: Boolean) = main.post {
        keyboardUp = up
        applyTouchable()
    }

    private fun applyTouchable(passThrough: Boolean = false) {
        val lp = params ?: return
        val off = passThrough || keyboardUp
        lp.flags = if (off) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        view?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private inner class Catcher : View.OnTouchListener {
        private val path = Path()
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var moved = false
        private val slop = 12 * service.resources.displayMetrics.density

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    path.reset()
                    path.moveTo(e.rawX, e.rawY)
                    downX = e.rawX
                    downY = e.rawY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    path.lineTo(e.rawX, e.rawY)
                    if (hypot(e.rawX - downX, e.rawY - downY) > slop) moved = true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = (SystemClock.uptimeMillis() - downAt).coerceIn(40, 3_000)
                    if (!moved) {
                        // Record first (on the screen as it is now), then play the tap to the app.
                        if (!onTap(downX.toInt(), downY.toInt())) return true
                        val tap = Path().apply { moveTo(downX, downY) }
                        play(GestureDescription.StrokeDescription(tap, 0, if (duration > 500) duration else 60))
                    } else {
                        path.lineTo(e.rawX, e.rawY)
                        play(GestureDescription.StrokeDescription(Path(path), 0, duration))
                    }
                }
            }
            return true
        }
    }

    private fun play(stroke: GestureDescription.StrokeDescription) {
        applyTouchable(passThrough = true)
        // Give the window manager a moment to stop routing touches to the layer.
        main.postDelayed({
            val sent = service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { main.postDelayed({ applyTouchable() }, 60) }
                override fun onCancelled(g: GestureDescription?) { main.postDelayed({ applyTouchable() }, 60) }
            }, null)
            if (!sent) applyTouchable()
        }, 50)
    }
}
