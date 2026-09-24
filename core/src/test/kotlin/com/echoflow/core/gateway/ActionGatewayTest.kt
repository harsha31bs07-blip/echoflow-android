package com.echoflow.core.gateway

import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.SafetyGuard
import com.echoflow.core.safety.ScreenSafetyClassifier
import com.echoflow.core.testing.screen
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionGatewayTest {
    private class FakeSource(var current: ScreenSnapshot?, var next: ScreenSnapshot? = null) : SnapshotSource {
        override fun current() = current
        override suspend fun awaitNewerThan(snapshotId: Long, timeoutMs: Long) = next?.takeIf { it.id > snapshotId }
    }

    private class FakeExecutor(val result: Boolean = true) : ActionExecutor {
        val executed = mutableListOf<PlannedAction>()
        override suspend fun execute(action: PlannedAction, snapshot: ScreenSnapshot): Boolean {
            executed += action
            return result
        }
    }

    private var proceed = -1
    private var place = -1
    private val cart = screen(id = 1) {
        text("Garlic Bread x 3")
        proceed = button("Proceed to Pay")
        place = button("Place order")
    }
    private val paymentPage = screen(id = 2) { text("Select payment method"); button("Pay ₹297") }
    private val guard = SafetyGuard(ScreenSafetyClassifier())

    @Test fun `allowed action runs and the resulting payment screen hands off`() = runTest {
        val exec = FakeExecutor()
        val gateway = ActionGateway(guard, exec, FakeSource(cart, paymentPage))
        val outcome = assertIs<ActionOutcome.Performed>(gateway.perform(PlannedAction.Click(1, proceed)))
        assertEquals(1, exec.executed.size)
        assertTrue(outcome.handedOff)
        assertTrue(guard.isTripped)
        // Nothing else gets through after the hand-off.
        assertIs<ActionOutcome.Blocked>(gateway.perform(PlannedAction.Back))
        assertEquals(1, exec.executed.size)
    }

    @Test fun `blocked action never reaches the executor`() = runTest {
        val exec = FakeExecutor()
        val gateway = ActionGateway(guard, exec, FakeSource(cart))
        val outcome = assertIs<ActionOutcome.Blocked>(gateway.perform(PlannedAction.Click(1, place)))
        assertEquals(BlockReason.COMMIT_ACTION, outcome.decision.reason)
        assertTrue(exec.executed.isEmpty())
    }

    @Test fun `platform refusal is reported as failure`() = runTest {
        val gateway = ActionGateway(guard, FakeExecutor(result = false), FakeSource(cart))
        assertIs<ActionOutcome.Failed>(gateway.perform(PlannedAction.Back))
        assertFalse(guard.isTripped)
    }

    @Test fun `no captured screen means no action`() = runTest {
        val exec = FakeExecutor()
        val gateway = ActionGateway(guard, exec, FakeSource(null))
        assertIs<ActionOutcome.Failed>(gateway.perform(PlannedAction.Back))
        assertTrue(exec.executed.isEmpty())
    }
}
