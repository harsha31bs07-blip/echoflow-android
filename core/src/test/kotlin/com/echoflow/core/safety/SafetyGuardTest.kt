package com.echoflow.core.safety

import com.echoflow.core.gateway.BlockReason
import com.echoflow.core.gateway.GateContext
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.model.InputTypes
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.testing.screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyGuardTest {
    private val guard = SafetyGuard(ScreenSafetyClassifier(ownPackage = "com.echoflow"), clock = { 42 })
    private val taught = GateContext(explicitlyTaught = true, resolverConfidence = 0.95)

    private var addIdx = -1
    private var searchIdx = -1
    private var proceedIdx = -1
    private var removeIdx = -1
    private val menu: ScreenSnapshot = screen(id = 10) {
        searchIdx = edit(hint = "Search for dishes")
        text("Margherita Pizza")
        addIdx = button("ADD")
        removeIdx = button("Remove")
        proceedIdx = button("Proceed to Pay")
    }

    private var payIdx = -1
    private val payment: ScreenSnapshot = screen(id = 11) {
        text("Payment Options"); text("UPI"); payIdx = button("Pay ₹349")
    }

    private fun block(d: GateDecision): GateDecision.Block = assertIs<GateDecision.Block>(d)

    @Test fun `safe click on safe screen is allowed`() {
        assertIs<GateDecision.Allow>(guard.gate(PlannedAction.Click(10, addIdx), menu, taught))
        assertFalse(guard.isTripped)
    }

    @Test fun `navigation toward payment is allowed`() {
        val d = assertIs<GateDecision.Allow>(guard.gate(PlannedAction.Click(10, proceedIdx), menu, taught))
        assertEquals(ActionRisk.NAVIGATES_TO_PAYMENT, d.risk)
    }

    @Test fun `watcher trips once and notifies listeners`() {
        val trips = mutableListOf<Trip>()
        guard.addTripListener { trips += it }
        guard.onSnapshot(payment)
        guard.onSnapshot(payment.copy(id = 12))
        assertTrue(guard.isTripped)
        assertEquals(1, trips.size)
        assertEquals(SensitiveKind.PAYMENT, trips.single().kind)
        assertEquals(11, trips.single().snapshotId)
        assertTrue(trips.single().handOffMessage.contains("payment screen"))
    }

    @Test fun `any action on a sensitive screen is blocked with hand-off`() {
        val d = block(guard.gate(PlannedAction.Click(11, payIdx), payment, taught))
        assertEquals(BlockReason.SENSITIVE_SCREEN, d.reason)
        assertEquals(SensitiveKind.PAYMENT, d.handOff)
        assertTrue(guard.isTripped)
    }

    @Test fun `once tripped everything is blocked, even back`() {
        guard.onSnapshot(payment)
        assertEquals(BlockReason.GUARD_TRIPPED, block(guard.gate(PlannedAction.Back, menu, taught)).reason)
        assertEquals(BlockReason.GUARD_TRIPPED, block(guard.gate(PlannedAction.Click(10, addIdx), menu, taught)).reason)
    }

    @Test fun `commit tap on an otherwise safe screen is blocked and trips`() {
        var placeIdx = -1
        val cart = screen(id = 20) { text("Your cart"); placeIdx = button("Place Order") }
        val d = block(guard.gate(PlannedAction.Click(20, placeIdx), cart, taught))
        assertEquals(BlockReason.COMMIT_ACTION, d.reason)
        assertEquals(SensitiveKind.PAYMENT, d.handOff)
        assertTrue(guard.isTripped)
    }

    @Test fun `destructive taps need a taught step and high confidence, never recovery`() {
        val click = PlannedAction.Click(10, removeIdx)
        assertEquals(BlockReason.DESTRUCTIVE_ACTION, block(guard.gate(click, menu, GateContext())).reason)
        assertEquals(BlockReason.DESTRUCTIVE_ACTION, block(guard.gate(click, menu, taught.copy(resolverConfidence = 0.8))).reason)
        assertEquals(BlockReason.DESTRUCTIVE_ACTION, block(guard.gate(click, menu, taught.copy(isRecovery = true))).reason)
        assertEquals(ActionRisk.DESTRUCTIVE, assertIs<GateDecision.Allow>(guard.gate(click, menu, taught)).risk)
        assertFalse(guard.isTripped, "destructive blocks ask the user; they are not payment hand-offs")
    }

    @Test fun `stale or unknown targets are blocked`() {
        assertEquals(BlockReason.STALE_SNAPSHOT, block(guard.gate(PlannedAction.Click(9, addIdx), menu, taught)).reason)
        assertEquals(BlockReason.UNKNOWN_TARGET, block(guard.gate(PlannedAction.Click(10, 999), menu, taught)).reason)
    }

    @Test fun `typing into a search field is allowed and the value is never printed`() {
        val action = PlannedAction.SetText(10, searchIdx, "garlic bread")
        assertIs<GateDecision.Allow>(guard.gate(action, menu, taught))
        assertFalse(action.toString().contains("garlic"))
    }

    @Test fun `typing into a secret field is blocked`() {
        var pin = -1
        val s = screen(id = 30) { text("Set up"); pin = edit(hint = "Enter PIN", inputType = InputTypes.TYPE_CLASS_NUMBER) }
        val d = block(guard.gate(PlannedAction.SetText(30, pin, "1234"), s, taught))
        assertEquals(SensitiveKind.PASSWORD, d.handOff)
    }

    @Test fun `launching payment apps is blocked`() {
        assertEquals(BlockReason.SENSITIVE_PACKAGE, block(guard.gate(PlannedAction.LaunchApp("com.phonepe.app"), menu, taught)).reason)
        assertIs<GateDecision.Allow>(guard.gate(PlannedAction.LaunchApp("in.swiggy.android"), menu, taught))
    }

    @Test fun `resume needs confirmation and a safe screen`() {
        guard.onSnapshot(payment)
        assertEquals(ResumeResult.NeedsUserConfirmation, guard.resume(menu, userConfirmed = false))
        assertIs<ResumeResult.StillSensitive>(guard.resume(payment, userConfirmed = true))
        assertTrue(guard.isTripped)
        assertEquals(ResumeResult.Resumed, guard.resume(menu, userConfirmed = true))
        assertFalse(guard.isTripped)
        assertEquals(ResumeResult.NotTripped, guard.resume(menu, userConfirmed = true))
    }

    @Test fun `safe screens leave the guard armed`() {
        val v = guard.onSnapshot(menu)
        assertFalse(v.isSensitive)
        assertNull(v.primaryKind)
        assertEquals(GuardState.Armed, guard.currentState)
    }
}
