package com.echoflow.app.orchestrator

import android.os.SystemClock
import com.echoflow.app.EchoRuntime
import com.echoflow.core.gateway.ActionOutcome
import com.echoflow.core.gateway.GateContext
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.replay.ReplayHost

/** Connects the replay engine to the live device: snapshots, the gated gateway, and voice. */
class AppReplayHost(
    private val orchestrator: Orchestrator,
) : ReplayHost {

    override fun current(): ScreenSnapshot? = EchoRuntime.snapshots.current()

    override suspend fun awaitSettled(afterId: Long, timeoutMs: Long): ScreenSnapshot? {
        val start = SystemClock.uptimeMillis()
        var last = EchoRuntime.snapshots.awaitNewerThan(afterId, timeoutMs) ?: return current()
        // Then wait for the screen to go quiet (no newer capture for QUIET_MS), capped.
        while (SystemClock.uptimeMillis() - start < timeoutMs + MAX_EXTRA_MS) {
            last = EchoRuntime.snapshots.awaitNewerThan(last.id, QUIET_MS) ?: return last
        }
        return last
    }

    override suspend fun perform(action: PlannedAction, context: GateContext): ActionOutcome =
        EchoRuntime.gateway?.perform(action, context) ?: ActionOutcome.Failed("accessibility service is off")

    override suspend fun ask(question: String, choices: List<String>): String? = orchestrator.askUser(question, choices)

    override fun say(text: String) = orchestrator.sayAsync(text)

    override fun progress(step: Int, total: Int, description: String) =
        orchestrator.status("Step $step/$total: $description")

    override fun recall(key: String): String? = orchestrator.memory.getString(key, null)

    override fun remember(key: String, value: String) = orchestrator.memory.edit().putString(key, value).apply()

    private companion object {
        const val QUIET_MS = 700L
        const val MAX_EXTRA_MS = 2_500L
    }
}
