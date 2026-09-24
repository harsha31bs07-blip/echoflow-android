package com.echoflow.app.monitor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.GuardState
import com.echoflow.core.safety.ScreenVerdict
import com.echoflow.core.safety.SensitiveKind

/**
 * Debug overlay for build step 1: shows how SafetyGuard classifies each screen while you browse
 * target apps by hand. Uses TYPE_ACCESSIBILITY_OVERLAY, so no overlay permission is needed.
 * It never takes focus; our own package is excluded from capture so it can't affect verdicts.
 */
class SafetyMonitorOverlay(
    private val service: AccessibilityService,
    private val onDump: () -> Unit,
    private val onRearm: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var diagnosticsLine: TextView
    private lateinit var guardLine: TextView
    private var atTop = true

    fun show() {
        if (root != null) return
        val dp = service.resources.displayMetrics.density
        val pad = (8 * dp).toInt()
        title = TextView(service).apply { setTextColor(Color.WHITE); textSize = 15f }
        detail = TextView(service).apply { setTextColor(Color.WHITE); textSize = 11f; maxLines = 3 }
        guardLine = TextView(service).apply { setTextColor(Color.WHITE); textSize = 11f }
        diagnosticsLine = TextView(service).apply { setTextColor(Color.WHITE); textSize = 11f }
        val buttons = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("Dump") { onDump() })
            addView(smallButton("Re-arm") { onRearm() })
            addView(smallButton("Move") { atTop = !atTop; root?.let { wm.updateViewLayout(it, params()) } })
            addView(smallButton("Hide") { onHide() })
        }
        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(SAFE_BG)
            addView(title)
            addView(detail)
            addView(diagnosticsLine)
            addView(guardLine)
            addView(buttons)
        }
        wm.addView(root, params())
    }

    fun hide() {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
    }

    fun render(snapshot: ScreenSnapshot?, verdict: ScreenVerdict?, guardState: GuardState) {
        val r = root ?: return
        val packageName = snapshot?.packageName
        val kind = verdict?.primaryKind
        title.text = when {
            verdict == null -> "Waiting for a screen…"
            kind == null -> "SAFE · ${packageName ?: "?"}"
            else -> "${verdict.kinds.joinToString(" + ")} · ${packageName ?: "?"}"
        }
        detail.text = verdict?.takeIf { it.isSensitive }?.summary() ?: ""
        detail.visibility = if (detail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // Always visible: "0 readable" with withheld children means the app hides its UI from us.
        diagnosticsLine.text = snapshot?.diagnostics?.summary() ?: ""
        guardLine.text = when (guardState) {
            GuardState.Armed -> "Guard: armed"
            is GuardState.Tripped -> "Guard: HANDED OFF (${guardState.trip.kind}) — leave this screen, then Re-arm"
        }
        r.setBackgroundColor(
            when (kind) {
                null -> SAFE_BG
                SensitiveKind.OPAQUE_UNKNOWN -> OPAQUE_BG
                else -> SENSITIVE_BG
            },
        )
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(service).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (atTop) Gravity.TOP else Gravity.BOTTOM
        alpha = 0.92f
    }

    private companion object {
        val SAFE_BG = Color.argb(225, 27, 94, 32)
        val SENSITIVE_BG = Color.argb(235, 183, 28, 28)
        val OPAQUE_BG = Color.argb(235, 230, 81, 0)
    }
}
