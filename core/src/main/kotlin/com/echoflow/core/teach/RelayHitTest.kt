package com.echoflow.core.teach

import com.echoflow.core.flow.Descriptors
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement

/** Both the semantic node to record and the control that receives a relayed tap. */
data class RelayHit(val index: Int, val actionIndex: Int)

object RelayHitTest {
    fun at(snapshot: ScreenSnapshot, x: Int, y: Int): RelayHit? {
        val hits = snapshot.appElements().filter {
            it.visible && it.bounds.area > 0 &&
                x >= it.bounds.left && x < it.bounds.right &&
                y >= it.bounds.top && y < it.bounds.bottom
        }
        if (hits.isEmpty()) return null

        // A dialog's window occludes the activity, even when its smallest node is disabled.
        // SnapshotCapturer visits windows from highest layer to lowest; retain that order
        // when layer metadata is missing or tied.
        val topLayer = hits.maxOf { snapshot.window(it.windowId)?.layer ?: 0 }
        val frontWindow = hits.first { (snapshot.window(it.windowId)?.layer ?: 0) == topLayer }.windowId
        val frontHits = hits.filter { it.windowId == frontWindow && it.enabled }
        if (frontHits.isEmpty()) return null

        // A native button is an explicit action target. Other branches can appear before
        // or after a fixed footer in accessibility order, independently of drawing order.
        // Without a native button, retain the most precise semantic hit rather than
        // treating traversal order as elevation.
        val control = frontHits.filter { it.clickable && it.simpleClassName.endsWith("Button") }
            .maxByOrNull { it.index }
            ?: return frontHits.minWithOrNull(compareBy<UiElement> { it.bounds.area }
                .thenByDescending { it.index })?.let {
                RelayHit(it.index, Descriptors.clickableFor(snapshot, it.index))
            }

        // Keep the text/icon inside this control as the descriptor, as with normal row taps.
        // A smaller label belonging to a different clickable branch must not win.
        val semantic = frontHits.filter {
            Descriptors.clickableFor(snapshot, it.index) == control.index
        }.minByOrNull { it.bounds.area } ?: control
        return RelayHit(semantic.index, control.index)
    }
}
