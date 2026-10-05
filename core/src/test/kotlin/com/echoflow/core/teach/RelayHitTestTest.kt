package com.echoflow.core.teach

import com.echoflow.core.model.Bounds
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.model.WindowInfo
import com.echoflow.core.model.WindowType
import com.echoflow.core.safety.ActionRisk
import com.echoflow.core.safety.ActionRiskClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayHitTestTest {
    private fun snapshot(vararg elements: UiElement, windows: List<WindowInfo> = emptyList()) =
        ScreenSnapshot(id = 1, timestampMs = 1, packageName = "test.app", windows = windows, elements = elements.toList())

    @Test fun `overlapping foreground footer wins over smaller background label and row`() {
        val s = snapshot(
            UiElement(0, bounds = Bounds(0, 0, 1080, 2340)),
            UiElement(1, parent = 0, clickable = true, bounds = Bounds(72, 2143, 1044, 2239)),
            UiElement(2, parent = 1, contentDescription = "Jalapeno", bounds = Bounds(138, 2164, 824, 2219)),
            UiElement(3, parent = 0, className = "android.widget.Button", clickable = true, text = "Add item", bounds = Bounds(384, 2103, 1044, 2259)),
        )
        assertEquals(RelayHit(3, 3), RelayHitTest.at(s, 714, 2181))
        assertEquals("Add item", Fingerprints.tap(s, RelayHitTest.at(s, 714, 2181)!!.index, 1).label)
    }

    @Test fun `fixed footer label wins over later background image branch`() {
        val s = snapshot(
            UiElement(0, clickable = true, bounds = Bounds(36, 2079, 1044, 2259)),
            UiElement(1, parent = 0, contentDescription = "Continue", bounds = Bounds(755, 2138, 960, 2200)),
            UiElement(2, clickable = true, contentDescription = "Background dish", bounds = Bounds(588, 1843, 1044, 2299)),
        )
        assertEquals(RelayHit(1, 0), RelayHitTest.at(s, 857, 2169))
    }

    @Test fun `text inside selected clickable row remains the recorded descriptor`() {
        val s = snapshot(
            UiElement(0, clickable = true, bounds = Bounds(0, 0, 400, 200)),
            UiElement(1, parent = 0, text = "Home", bounds = Bounds(50, 50, 150, 100)),
        )
        assertEquals(RelayHit(1, 0), RelayHitTest.at(s, 75, 75))
    }

    @Test fun `foreground dialog wins even when its element precedes smaller activity control`() {
        val s = snapshot(
            UiElement(0, windowId = 2, clickable = true, text = "Continue", bounds = Bounds(0, 0, 400, 200)),
            UiElement(1, windowId = 1, clickable = true, text = "Background", bounds = Bounds(50, 50, 150, 100)),
            windows = listOf(
                WindowInfo(1, WindowType.APPLICATION, layer = 1),
                WindowInfo(2, WindowType.APPLICATION, layer = 2),
            ),
        )
        assertEquals(RelayHit(0, 0), RelayHitTest.at(s, 75, 75))
    }

    @Test fun `disabled foreground window cannot fall through to activity`() {
        val s = snapshot(
            UiElement(0, windowId = 2, enabled = false, clickable = true, bounds = Bounds(0, 0, 400, 200)),
            UiElement(1, windowId = 1, clickable = true, bounds = Bounds(50, 50, 150, 100)),
            windows = listOf(
                WindowInfo(1, WindowType.APPLICATION, layer = 1),
                WindowInfo(2, WindowType.APPLICATION, layer = 2),
            ),
        )
        assertNull(RelayHitTest.at(s, 75, 75))
    }

    @Test fun `foreground payment control and child still trigger commit guard`() {
        val s = snapshot(
            UiElement(0, clickable = true, bounds = Bounds(0, 0, 400, 200)),
            UiElement(1, parent = 0, text = "Keep shopping", bounds = Bounds(50, 50, 150, 100)),
            UiElement(2, clickable = true, bounds = Bounds(0, 0, 400, 200)),
            UiElement(3, parent = 2, text = "Place order", bounds = Bounds(50, 50, 150, 100)),
        )
        val hit = RelayHitTest.at(s, 75, 75)!!
        assertEquals(RelayHit(3, 2), hit)
        assertEquals(ActionRisk.COMMIT, ActionRiskClassifier().assess(s, s.elements[hit.actionIndex]).risk)
    }

    @Test fun `right edge is outside and invisible controls do not intercept tap`() {
        val s = snapshot(
            UiElement(0, clickable = true, text = "Left", bounds = Bounds(0, 0, 100, 100)),
            UiElement(1, clickable = true, text = "Right", bounds = Bounds(100, 0, 200, 100)),
            UiElement(2, visible = false, clickable = true, bounds = Bounds(0, 0, 200, 100)),
        )
        assertEquals(RelayHit(1, 1), RelayHitTest.at(s, 100, 50))
        assertNull(RelayHitTest.at(s, 200, 50))
    }
}
