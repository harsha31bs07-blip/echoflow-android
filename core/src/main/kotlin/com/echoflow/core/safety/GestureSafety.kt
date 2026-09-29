package com.echoflow.core.safety

import com.echoflow.core.model.ScreenSnapshot

/**
 * Guard for the gesture fallback (a tap at an element's centre when the app refuses
 * ACTION_CLICK). A coordinate tap lands on whatever is drawn on top at that point, and tree order
 * doesn't tell us what that is: on Zomato the menu's "1 item added · Continue" bar stays in the
 * tree under the cart sheet, at the same spot as "Place Order". So the tap is refused whenever
 * any other clickable element there could pay, order, head to payment or delete something.
 */
object GestureSafety {
    private val risky = setOf(ActionRisk.COMMIT, ActionRisk.NAVIGATES_TO_PAYMENT, ActionRisk.DESTRUCTIVE)

    fun safeToTap(snapshot: ScreenSnapshot, index: Int, classifier: ActionRiskClassifier = ActionRiskClassifier()): Boolean {
        val target = snapshot.elements.getOrNull(index) ?: return false
        if (!target.visible || target.bounds.width <= 0 || target.bounds.height <= 0) return false
        val x = (target.bounds.left + target.bounds.right) / 2
        val y = (target.bounds.top + target.bounds.bottom) / 2
        val ancestors = generateSequence(target.parent.takeIf { it >= 0 }) { p -> snapshot.elements.getOrNull(p)?.parent?.takeIf { it >= 0 } }.toSet()
        val descendants = snapshot.descendants(index).map { it.index }.toSet()
        return snapshot.elements.none { e ->
            e.index != index && e.index !in ancestors && e.index !in descendants &&
                e.visible && e.clickable &&
                x >= e.bounds.left && x < e.bounds.right && y >= e.bounds.top && y < e.bounds.bottom &&
                classifier.assess(snapshot, e).risk in risky
        }
    }
}
