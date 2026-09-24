package com.echoflow.core.gateway

import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.safety.GateDecision
import com.echoflow.core.safety.GuardState
import com.echoflow.core.safety.SafetyGuard
import com.echoflow.core.safety.ScreenVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Performs raw device actions. Implemented by the accessibility layer; only [ActionGateway] may call it. */
interface ActionExecutor {
    /** Returns false if the platform refused the action (node gone, action unsupported). */
    suspend fun execute(action: PlannedAction, snapshot: ScreenSnapshot): Boolean
}

/** The latest captured screen, plus a way to wait for the next one. */
interface SnapshotSource {
    fun current(): ScreenSnapshot?
    suspend fun awaitNewerThan(snapshotId: Long, timeoutMs: Long): ScreenSnapshot?
}

sealed interface ActionOutcome {
    data class Performed(
        val postSnapshot: ScreenSnapshot?,
        val postVerdict: ScreenVerdict?,
    ) : ActionOutcome {
        /** The action landed us on a sensitive screen and the guard has handed off. */
        val handedOff: Boolean get() = postVerdict?.isSensitive == true
    }

    data class Blocked(val decision: GateDecision.Block) : ActionOutcome
    data class Failed(val message: String) : ActionOutcome
}

/**
 * The single chokepoint for device actions. Every action is gated by [SafetyGuard] against the
 * current screen before it runs, and the screen it produces is fed back to the guard.
 * Actions are serialized: one in flight at a time.
 */
class ActionGateway(
    private val guard: SafetyGuard,
    private val executor: ActionExecutor,
    private val snapshots: SnapshotSource,
    private val postActionTimeoutMs: Long = 3_000,
) {
    private val mutex = Mutex()

    suspend fun perform(action: PlannedAction, context: GateContext = GateContext()): ActionOutcome = mutex.withLock {
        val snapshot = snapshots.current()
            ?: return@withLock ActionOutcome.Failed("no screen captured yet")

        when (val decision = guard.gate(action, snapshot, context)) {
            is GateDecision.Block -> return@withLock ActionOutcome.Blocked(decision)
            is GateDecision.Allow -> Unit
        }
        // The watcher may have tripped on another thread since the gate check.
        if (guard.isTripped) {
            val kind = (guard.currentState as? GuardState.Tripped)?.trip?.kind
            return@withLock ActionOutcome.Blocked(GateDecision.Block(BlockReason.GUARD_TRIPPED, "tripped before execution", kind))
        }

        val ok = try {
            executor.execute(action, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock ActionOutcome.Failed(e.message ?: e.toString())
        }
        if (!ok) return@withLock ActionOutcome.Failed("platform refused $action")

        val post = snapshots.awaitNewerThan(snapshot.id, postActionTimeoutMs)
        ActionOutcome.Performed(post, post?.let(guard::onSnapshot))
    }
}
