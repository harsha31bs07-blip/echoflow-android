package com.echoflow.core.runlog

import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus {
    /** Reached checkout/payment and handed control to the user (the normal success for purchase flows). */
    HANDED_OFF,

    /** All steps done, no payment boundary involved. */
    COMPLETED,

    /** Stopped: couldn't continue safely (target missing, stuck, other app). */
    HALTED,

    /** Asked the user a question and got no usable answer. */
    NO_ANSWER,

    CANCELLED,
}

@Serializable
data class RunRecord(
    val id: String,
    val utterance: String,
    val flowId: String?,
    val flowName: String?,
    val slots: Map<String, String> = emptyMap(),
    val startedAtMs: Long,
    val endedAtMs: Long = 0,
    val status: RunStatus,
    /** 1-based step where the run stopped; 0 if it never started. */
    val stoppedAtStep: Int = 0,
    val totalSteps: Int = 0,
    val stepDescription: String? = null,
    /** What was said to the user at the end. */
    val message: String,
    /** Decisions and notable events, in order ("skipped step 4", "closed popup 'Get Gold'"). */
    val events: List<String> = emptyList(),
) {
    val succeeded: Boolean get() = status == RunStatus.HANDED_OFF || status == RunStatus.COMPLETED

    /** Spoken answer to "what happened last time?" — templated, never generated. */
    fun spokenSummary(): String {
        val what = flowName?.let { "\"$it\"" } ?: "\"$utterance\""
        val slotText = if (slots.isEmpty()) "" else " with " + slots.entries.joinToString(", ") { "${it.key} ${it.value}" }
        val where = if (totalSteps > 0 && stoppedAtStep > 0) " at step $stoppedAtStep of $totalSteps" +
            (stepDescription?.let { ", $it" } ?: "") else ""
        return when (status) {
            RunStatus.HANDED_OFF -> "Last run, $what$slotText, succeeded. It reached the payment step$where and handed over to you. $message"
            RunStatus.COMPLETED -> "Last run, $what$slotText, completed all $totalSteps steps."
            RunStatus.HALTED -> "Last run, $what$slotText, failed$where. $message"
            RunStatus.NO_ANSWER -> "Last run, $what$slotText, stopped$where because I asked a question and got no answer. $message"
            RunStatus.CANCELLED -> "Last run, $what$slotText, was cancelled$where."
        }
    }
}
