package com.echoflow.core.safety

import com.echoflow.core.safety.ActionRisk.COMMIT
import com.echoflow.core.safety.ActionRisk.DESTRUCTIVE
import com.echoflow.core.safety.ActionRisk.NAVIGATES_TO_PAYMENT
import com.echoflow.core.safety.ActionRisk.SAFE
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionRiskClassifierTest {
    private val classifier = ActionRiskClassifier()

    private fun riskOfButton(label: String): ActionRisk {
        var idx = -1
        val s = screen { idx = button(label) }
        return classifier.assess(s, s.elements[idx]).risk
    }

    @Test fun `pay buttons are commits`() {
        listOf("Pay ₹349", "PAY NOW", "Pay", "Pay Rs. 1,299", "Pay securely", "भुगतान करें").forEach {
            assertEquals(COMMIT, riskOfButton(it), it)
        }
    }

    @Test fun `order commits`() {
        listOf("Place Order", "Place your order and pay", "Confirm order", "Confirm & Pay", "Buy Now", "Complete purchase", "Swipe to pay")
            .forEach { assertEquals(COMMIT, riskOfButton(it), it) }
    }

    @Test fun `navigation toward payment is allowed but flagged`() {
        listOf("Proceed to Pay", "Proceed to Pay ₹349", "Checkout", "Proceed to Buy", "Continue to payment")
            .forEach { assertEquals(NAVIGATES_TO_PAYMENT, riskOfButton(it), it) }
    }

    @Test fun `destructive actions`() {
        listOf("Remove", "Clear cart", "Yes, start afresh", "Log out", "Delete address", "Cancel order")
            .forEach { assertEquals(DESTRUCTIVE, riskOfButton(it), it) }
    }

    @Test fun `ordinary taps are safe`() {
        listOf("ADD", "Add to Cart", "Search", "Margherita Pizza", "Pay on delivery", "Pay bills", "Home", "Cancel", "Not now")
            .forEach { assertEquals(SAFE, riskOfButton(it), it) }
    }

    @Test fun `clickable row takes the label of its children`() {
        var row = -1
        val s = screen {
            row = container(clickable = true)
            text("₹458", row); text("Place order", row)
        }
        assertEquals(COMMIT, classifier.assess(s, s.elements[row]).risk)
    }

    @Test fun `slider with pay text is a commit`() {
        var slider = -1
        val s = screen { slider = node("android.widget.SeekBar", label = "Slide to confirm your order", clickable = true) }
        assertEquals(COMMIT, classifier.assess(s, s.elements[slider]).risk)
    }

    @Test fun `stepper decrement described as remove is destructive`() {
        var minus = -1
        val s = screen { minus = icon("Remove item") }
        assertEquals(DESTRUCTIVE, classifier.assess(s, s.elements[minus]).risk)
    }
}
