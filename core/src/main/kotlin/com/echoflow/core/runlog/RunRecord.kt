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
        // "order {item}" + {item: garlic bread} -> "order garlic bread"
        var what = flowName ?: utterance
        slots.forEach { (k, v) -> what = what.replace("{$k}", v) }
        what = what.replace(Regex("\\{\\w+\\}"), "").trim()
        val step = stepDescription?.replace(Regex("\\{(\\w+)\\}")) { m -> slots[m.groupValues[1]] ?: m.value }
        val where = if (totalSteps > 0 && stoppedAtStep > 0) " at step $stoppedAtStep of $totalSteps" +
            (step?.let { " ($it)" } ?: "") else ""
        return when (status) {
            RunStatus.HANDED_OFF -> "Your last request, $what, succeeded. I got it ready and handed over to you. $message"
            RunStatus.COMPLETED -> "Your last request, $what, completed all $totalSteps steps."
            RunStatus.HALTED -> "Your last request, $what, failed$where. $message"
            RunStatus.NO_ANSWER -> "Your last request, $what, stopped$where because I asked a question and didn't get an answer. $message"
            RunStatus.CANCELLED -> "Your last request, $what, was cancelled$where."
        }
    }
}
