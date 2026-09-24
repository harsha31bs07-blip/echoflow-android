package com.echoflow.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.echoflow.core.model.Bounds
import com.echoflow.core.model.CaptureDiagnostics
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.model.WindowInfo
import com.echoflow.core.model.WindowType
import java.util.concurrent.atomic.AtomicLong

/**
 * Flattens the accessibility trees of all application windows (activity, dialogs, bottom sheets)
 * into a [ScreenSnapshot]. Our own overlay and system windows are skipped. Password field text is
 * never read. Runs on the capture thread.
 */
class SnapshotCapturer(private val service: AccessibilityService) {
    private val ownPackage = service.packageName
    private val nextId = AtomicLong(1)

    /** Per-capture counters for [CaptureDiagnostics]. */
    private class Stats {
        var withheld = 0
        val withheldAt = ArrayList<String>()
        var truncated = false
    }

    /**
     * Returns null when no application window content is available yet (transient during transitions).
     * [trigger] is recorded in the diagnostics: "event", "recheck" or "dump".
     */
    fun capture(activityName: String?, trigger: String = "event"): LiveSnapshot? {
        val elements = ArrayList<UiElement>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val windowInfos = ArrayList<WindowInfo>()
        val stats = Stats()
        var topPackage: String? = null

        val windows = runCatching { service.windows }.getOrDefault(emptyList()).sortedByDescending { it.layer }
        for (window in windows) {
            val type = mapType(window.type)
            val root = runCatching { window.root }.getOrNull()
            val pkg = root?.packageName?.toString()
            val rect = Rect().also(window::getBoundsInScreen)
            windowInfos += WindowInfo(
                id = window.id, type = type, packageName = pkg, title = window.title?.toString(),
                bounds = rect.toBounds(), layer = window.layer, isActive = window.isActive, isFocused = window.isFocused,
            )
            if (type != WindowType.APPLICATION || root == null || pkg == ownPackage) continue
            if (topPackage == null) topPackage = pkg
            walk(root, window.id, elements, nodes, stats)
        }

        if (windows.isEmpty()) {
            service.rootInActiveWindow?.takeIf { it.packageName?.toString() != ownPackage }?.let { root ->
                topPackage = root.packageName?.toString()
                walk(root, root.windowId, elements, nodes, stats)
            }
        }
        if (elements.isEmpty()) return null

        val metrics = service.resources.displayMetrics
        val snapshot = ScreenSnapshot(
            id = nextId.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            packageName = topPackage,
            activityName = activityName,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            windows = windowInfos,
            elements = elements,
            diagnostics = CaptureDiagnostics(
                totalNodes = elements.size,
                labeledNodes = elements.count { it.visible && it.label != null },
                withheldChildren = stats.withheld,
                withheldAt = stats.withheldAt,
                truncated = stats.truncated,
                trigger = trigger,
            ),
        )
        return LiveSnapshot(snapshot, nodes)
    }

    private fun walk(
        root: AccessibilityNodeInfo,
        windowId: Int,
        elements: MutableList<UiElement>,
        nodes: MutableList<AccessibilityNodeInfo>,
        stats: Stats,
    ) {
        data class Frame(val node: AccessibilityNodeInfo, val parent: Int, val depth: Int)

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, -1, 0))
        while (stack.isNotEmpty()) {
            if (elements.size >= MAX_NODES) {
                stats.truncated = true
                break
            }
            val (node, parent, depth) = stack.removeLast()
            val index = elements.size
            elements += toElement(node, index, parent, windowId, depth)
            nodes += node
            if (depth >= MAX_DEPTH) {
                if (node.childCount > 0) stats.truncated = true
                continue
            }
            var missing = 0
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i)
                if (child == null) missing++ else stack.addLast(Frame(child, index, depth + 1))
            }
            if (missing > 0) {
                stats.withheld += missing
                if (stats.withheldAt.size < 10) {
                    stats.withheldAt += node.viewIdResourceName ?: node.className?.toString() ?: "?"
                }
            }
        }
    }

    private fun toElement(n: AccessibilityNodeInfo, index: Int, parent: Int, windowId: Int, depth: Int): UiElement {
        val rect = Rect().also(n::getBoundsInScreen)
        val isPassword = n.isPassword
        return UiElement(
            index = index,
            parent = parent,
            windowId = windowId,
            depth = depth,
            className = n.className?.toString().orEmpty(),
            packageName = n.packageName?.toString(),
            // Password text is never read, not even into memory.
            text = if (isPassword) null else n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            hintText = n.hintText?.toString(),
            viewId = n.viewIdResourceName,
            bounds = rect.toBounds(),
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            editable = n.isEditable,
            password = isPassword,
            focusable = n.isFocusable,
            focused = n.isFocused,
            checkable = n.isCheckable,
            checked = n.isChecked,
            scrollable = n.isScrollable,
            enabled = n.isEnabled,
            visible = n.isVisibleToUser,
            inputType = n.inputType,
            maxTextLength = n.maxTextLength,
        )
    }

    private fun mapType(type: Int): WindowType = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
        AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
        else -> WindowType.OTHER
    }

    private fun Rect.toBounds() = Bounds(left, top, right, bottom)

    private companion object {
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 60
    }
}
