package com.echoflow.core.gateway

/**
 * Everything automation can do to the device. Element-targeted actions carry the id of the
 * snapshot they were resolved against; the gateway refuses them if the screen has moved on.
 */
sealed interface PlannedAction {
    sealed interface Targeted : PlannedAction {
        val snapshotId: Long
        val elementIndex: Int
    }

    data class Click(override val snapshotId: Long, override val elementIndex: Int) : Targeted

    class SetText(override val snapshotId: Long, override val elementIndex: Int, val text: String) : Targeted {
        // Never print the typed value.
        override fun toString() = "SetText(snapshotId=$snapshotId, elementIndex=$elementIndex, text=<${text.length} chars>)"
    }

    data class ImeEnter(override val snapshotId: Long, override val elementIndex: Int) : Targeted

    data class Scroll(override val snapshotId: Long, override val elementIndex: Int, val forward: Boolean) : Targeted

    data object Back : PlannedAction

    data class LaunchApp(val packageName: String) : PlannedAction
}

/** Facts about *why* an action is being attempted, supplied by the replay/recovery layer. */
data class GateContext(
    /** The user demonstrated this exact step while teaching. */
    val explicitlyTaught: Boolean = false,
    /** ElementResolver's confidence that the target is the taught element, 0..1. */
    val resolverConfidence: Double = 0.0,
    /** Autonomous recovery (dismissing popups, backing out). Recovery never gets risky taps. */
    val isRecovery: Boolean = false,
)

enum class BlockReason {
    GUARD_TRIPPED,
    SENSITIVE_SCREEN,
    SENSITIVE_PACKAGE,
    COMMIT_ACTION,
    DESTRUCTIVE_ACTION,
    SENSITIVE_FIELD,
    STALE_SNAPSHOT,
    UNKNOWN_TARGET,
}
