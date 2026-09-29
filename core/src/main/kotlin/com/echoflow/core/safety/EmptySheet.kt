package com.echoflow.core.safety

import com.echoflow.core.model.ScreenSnapshot

/**
 * An empty dialog/bottom-sheet shell: a few nodes, nothing readable, and the standard
 * "touch_outside" dimmed area. Zomato sometimes leaves one on screen after its location sheet,
 * which makes the whole screen unreadable. Back closes it and can't pay or type anything, so
 * it's the one action allowed on such a screen.
 */
object EmptySheet {
    private const val MAX_NODES = 6

    fun matches(snapshot: ScreenSnapshot): Boolean {
        val els = snapshot.appElements()
        if (els.isEmpty() || els.size > MAX_NODES) return false
        if (els.any { !it.label.isNullOrBlank() || it.editable }) return false
        return els.any { it.viewId?.substringAfter(":id/")?.let { id -> id == "touch_outside" || id.endsWith("_touch_outside") } == true }
    }
}
