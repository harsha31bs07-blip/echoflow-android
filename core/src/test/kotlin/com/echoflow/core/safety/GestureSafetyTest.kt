package com.echoflow.core.safety

import com.echoflow.core.model.Bounds
import com.echoflow.core.model.UiElement
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureSafetyTest {
    private val pkg = "com.application.zomato"

    /** Zomato: the cart sheet's "Place Order" and the menu's "Continue" bar share a spot. */
    @Test fun `no coordinate tap where a place-order button also sits`() {
        var bar = -1
        val s = screen(pkg) {
            val checkout = add { UiElement(it, -1, 1, className = "android.widget.FrameLayout", packageName = pkg, bounds = Bounds(420, 2100, 1060, 2230), clickable = true) }
            add { UiElement(it, checkout, 1, className = "android.widget.TextView", packageName = pkg, text = "Place Order", bounds = Bounds(700, 2130, 980, 2200)) }
            bar = add { UiElement(it, -1, 1, className = "android.view.ViewGroup", packageName = pkg, bounds = Bounds(40, 2110, 1040, 2230), clickable = true) }
            add { UiElement(it, bar, 1, className = "android.view.View", packageName = pkg, text = "1 item added", bounds = Bounds(300, 2140, 600, 2200)) }
            add { UiElement(it, bar, 1, className = "android.view.View", packageName = pkg, text = "Continue", bounds = Bounds(780, 2140, 940, 2200)) }
        }
        assertFalse(GestureSafety.safeToTap(s, bar))
    }

    @Test fun `a floating cart bar over plain list rows can be tapped`() {
        var bar = -1
        val s = screen("in.swiggy.android") {
            val row = add { UiElement(it, -1, 1, className = "android.view.ViewGroup", packageName = "in.swiggy.android", bounds = Bounds(0, 2000, 1080, 2300), clickable = true) }
            add { UiElement(it, row, 1, className = "android.widget.TextView", packageName = "in.swiggy.android", text = "Garlic Breadsticks", bounds = Bounds(40, 2020, 600, 2080)) }
            bar = add { UiElement(it, -1, 1, className = "android.view.ViewGroup", packageName = "in.swiggy.android", bounds = Bounds(20, 2150, 1060, 2280), clickable = true) }
            add { UiElement(it, bar, 1, className = "android.widget.TextView", packageName = "in.swiggy.android", text = "View Cart", bounds = Bounds(700, 2180, 1000, 2250)) }
        }
        assertTrue(GestureSafety.safeToTap(s, bar))
    }
}
