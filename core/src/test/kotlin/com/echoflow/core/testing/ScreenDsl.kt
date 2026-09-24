package com.echoflow.core.testing

import com.echoflow.core.model.Bounds
import com.echoflow.core.model.InputTypes
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.model.WindowInfo
import com.echoflow.core.model.WindowType

/** Tiny builder for synthetic screens. Each call returns the new element's index. */
class ScreenBuilder(private val pkg: String) {
    private val elements = mutableListOf<UiElement>()
    private val windows = mutableListOf(WindowInfo(1, WindowType.APPLICATION, pkg, bounds = Bounds(0, 0, 1080, 2400)))
    private var y = 0

    private fun add(e: (Int) -> UiElement): Int {
        val index = elements.size
        elements += e(index)
        return index
    }

    private fun nextBounds(height: Int = 120): Bounds = Bounds(0, y, 1080, y + height).also { y += height }

    fun container(parent: Int = -1, clickable: Boolean = false, className: String = "android.widget.LinearLayout") =
        add { UiElement(it, parent, 1, className = className, packageName = pkg, bounds = nextBounds(0), clickable = clickable) }

    fun text(value: String, parent: Int = -1, clickable: Boolean = false) =
        add { UiElement(it, parent, 1, className = "android.widget.TextView", packageName = pkg, text = value, bounds = nextBounds(), clickable = clickable) }

    fun button(value: String, parent: Int = -1) =
        add { UiElement(it, parent, 1, className = "android.widget.Button", packageName = pkg, text = value, bounds = nextBounds(), clickable = true) }

    fun icon(description: String, parent: Int = -1, clickable: Boolean = true) =
        add { UiElement(it, parent, 1, className = "android.widget.ImageView", packageName = pkg, contentDescription = description, bounds = nextBounds(), clickable = clickable) }

    fun edit(
        hint: String? = null,
        parent: Int = -1,
        typed: String? = null,
        inputType: Int = InputTypes.TYPE_CLASS_TEXT,
        maxLength: Int = -1,
        password: Boolean = false,
        viewId: String? = null,
        description: String? = null,
    ) = add {
        UiElement(
            it, parent, 1, className = "android.widget.EditText", packageName = pkg, text = typed, hintText = hint,
            contentDescription = description, viewId = viewId, bounds = nextBounds(), clickable = true, editable = true,
            focusable = true, password = password, inputType = inputType, maxTextLength = maxLength,
        )
    }

    fun node(className: String, parent: Int = -1, bounds: Bounds = nextBounds(), label: String? = null, clickable: Boolean = false) =
        add { UiElement(it, parent, 1, className = className, packageName = pkg, text = label, bounds = bounds, clickable = clickable) }

    fun overlayText(value: String, ownPkg: String) {
        windows += WindowInfo(2, WindowType.ACCESSIBILITY_OVERLAY, ownPkg, bounds = Bounds(0, 0, 1080, 200))
        elements += UiElement(elements.size, -1, 2, className = "android.widget.TextView", packageName = ownPkg, text = value)
    }

    fun build(id: Long = 1): ScreenSnapshot =
        ScreenSnapshot(id = id, timestampMs = 0, packageName = pkg, windows = windows.toList(), elements = elements.toList())
}

fun screen(pkg: String = "in.swiggy.android", id: Long = 1, block: ScreenBuilder.() -> Unit): ScreenSnapshot =
    ScreenBuilder(pkg).apply(block).build(id)
