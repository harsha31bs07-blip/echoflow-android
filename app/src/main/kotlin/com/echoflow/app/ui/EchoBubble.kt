package com.echoflow.app.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.echoflow.app.orchestrator.Mode
import com.echoflow.app.orchestrator.Orchestrator
import com.echoflow.app.orchestrator.UiState

/**
 * Floating control panel (TYPE_ACCESSIBILITY_OVERLAY, so no overlay permission): Speak, Done
 * (while teaching), Stop, choice buttons for questions, and a status line. It works on top of
 * any app, which is where teaching and replay happen.
 */
class EchoBubble(
    private val service: AccessibilityService,
    private val orchestrator: Orchestrator,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = service.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private lateinit var status: TextView
    private lateinit var speak: Button
    private lateinit var done: Button
    private lateinit var stop: Button
    private lateinit var choices: LinearLayout
    private var atBottom = true

    fun show() {
        if (root != null) return
        val pad = (8 * dp).toInt()
        status = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 3
        }
        speak = button("🎤 Speak", 0xFF1E88E5.toInt()) { orchestrator.onSpeakPressed() }
        done = button("✓ Done", 0xFF43A047.toInt()) { orchestrator.onDonePressed() }
        stop = button("■ Stop", 0xFFE53935.toInt()) { orchestrator.onStopPressed() }
        val home = button("EchoFlow", 0xFF546E7A.toInt()) {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val move = button("⇅", 0xFF546E7A.toInt()) {
            atBottom = !atBottom
            root?.let { wm.updateViewLayout(it, params()) }
        }
        choices = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(speak, done, stop, home, move).forEach(::addView)
        }
        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 33, 33, 33))
                cornerRadius = 16 * dp
            }
            addView(status)
            addView(HorizontalScrollView(service).apply { addView(choices) })
            addView(row)
        }
        wm.addView(root, params())
    }

    fun hide() {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
    }

    fun render(s: UiState) {
        if (root == null) return
        status.text = when (s.mode) {
            Mode.TEACHING -> "● REC  ${s.status}"
            else -> s.status
        }
        done.visibility = if (s.mode == Mode.TEACHING) View.VISIBLE else View.GONE
        stop.visibility = if (s.mode == Mode.RUNNING || s.mode == Mode.TEACHING || s.mode == Mode.ASKING) View.VISIBLE else View.GONE
        speak.isEnabled = s.mode == Mode.IDLE || s.mode == Mode.TEACHING
        speak.alpha = if (speak.isEnabled) 1f else 0.4f
        choices.removeAllViews()
        s.choices.take(4).forEach { c -> choices.addView(button(c.take(28), 0xFF6D4C41.toInt()) { orchestrator.onChoice(c) }) }
        choices.visibility = if (s.choices.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(service).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        background = GradientDrawable().apply { setColor(color); cornerRadius = 12 * dp }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (6 * dp).toInt()
            topMargin = (4 * dp).toInt()
        }
        setOnClickListener { onClick() }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (atBottom) Gravity.BOTTOM else Gravity.TOP
        y = (if (atBottom) 72 else 48) * dp.toInt()
        horizontalMargin = 0.02f
    }
}
