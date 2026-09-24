package com.echoflow.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

@Serializable
enum class WindowType { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, OTHER }

@Serializable
data class WindowInfo(
    val id: Int,
    val type: WindowType,
    val packageName: String? = null,
    val title: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val layer: Int = 0,
    val isActive: Boolean = false,
    val isFocused: Boolean = false,
)

/**
 * One accessibility node, flattened. [index] is the position in [ScreenSnapshot.elements];
 * [parent] is the parent's index or -1 for a window root.
 */
@Serializable
data class UiElement(
    val index: Int,
    val parent: Int = -1,
    val windowId: Int = 0,
    val depth: Int = 0,
    val className: String = "",
    val packageName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    /** Raw android.text.InputType bits; see [InputTypes]. */
    val inputType: Int = 0,
    /** -1 when the field has no max length. */
    val maxTextLength: Int = -1,
) {
    /** The text a sighted user reads for this node: text, else content description. */
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

    val simpleClassName: String get() = className.substringAfterLast('.')
}

@Serializable
data class ScreenSnapshot(
    val id: Long,
    val timestampMs: Long,
    /** Package of the top-most application window. */
    val packageName: String?,
    val activityName: String? = null,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val windows: List<WindowInfo> = emptyList(),
    val elements: List<UiElement> = emptyList(),
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    val screenArea: Long get() = screenWidth.toLong() * screenHeight.toLong()

    private val childIndex: Map<Int, List<Int>> by lazy {
        elements.filter { it.parent >= 0 }.groupBy({ it.parent }, { it.index })
    }

    private val windowById: Map<Int, WindowInfo> by lazy { windows.associateBy { it.id } }

    fun children(index: Int): List<UiElement> = childIndex[index].orEmpty().map { elements[it] }

    fun descendants(index: Int, maxDepth: Int = Int.MAX_VALUE): Sequence<UiElement> = sequence {
        val stack = ArrayDeque<Pair<Int, Int>>()
        childIndex[index].orEmpty().forEach { stack.addLast(it to 1) }
        while (stack.isNotEmpty()) {
            val (i, d) = stack.removeLast()
            yield(elements[i])
            if (d < maxDepth) childIndex[i].orEmpty().forEach { stack.addLast(it to d + 1) }
        }
    }

    fun window(id: Int): WindowInfo? = windowById[id]

    /** Elements that belong to application windows (dialogs and sheets included), or all if windows are unknown. */
    fun appElements(): List<UiElement> {
        if (windows.isEmpty()) return elements
        val appWindowIds = windows.filter { it.type == WindowType.APPLICATION }.map { it.id }.toSet()
        return elements.filter { it.windowId in appWindowIds }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Subset of android.text.InputType constants, duplicated so :core has no Android dependency. */
object InputTypes {
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_CLASS_PHONE = 0x00000003
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    fun isNumeric(inputType: Int): Boolean {
        val cls = inputType and TYPE_MASK_CLASS
        return cls == TYPE_CLASS_NUMBER || cls == TYPE_CLASS_PHONE
    }

    fun isPasswordVariation(inputType: Int): Boolean {
        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when (cls) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
