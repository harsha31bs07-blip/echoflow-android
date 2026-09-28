package com.echoflow.core.decision

import com.echoflow.core.nlu.Candidate

sealed interface Decision {
    /** Run it now. Missing slots are asked for when their step is reached. */
    data class Proceed(val candidate: Candidate) : Decision

    /** Probably this flow; confirm with a yes/no question first (T13, low confidence). */
    data class Confirm(val candidate: Candidate, val question: String) : Decision

    /** Two or more flows fit about equally; ask which (T13). */
    data class Disambiguate(val options: List<Candidate>, val question: String) : Decision

    /** Nothing fits: say so and offer to learn it (T12). Never guess. */
    data class OfferTeach(val message: String) : Decision
}

/**
 * The pre-run policy (ARCHITECTURE §5), as a pure function over matcher candidates.
 * Thresholds are deliberately conservative: a wrong replay costs more than a question.
 */
object DecisionLayer {
    const val UNKNOWN_BELOW = 0.45
    const val AMBIGUOUS_MARGIN = 0.15
    const val CONFIRM_BELOW = 0.8
    const val MAX_QTY = 10

    fun decide(utterance: String, candidates: List<Candidate>): Decision {
        val top = candidates.firstOrNull()
        if (top == null || top.score < UNKNOWN_BELOW) {
            return Decision.OfferTeach("I don't know how to \"$utterance\" yet. Want to teach me? Say \"teach\" and then show me.")
        }
        val close = candidates.drop(1).filter { it.score >= UNKNOWN_BELOW && top.score - it.score < AMBIGUOUS_MARGIN }
        if (close.isNotEmpty()) {
            val options = (listOf(top) + close).take(3)
            val names = options.mapIndexed { i, c -> "${ordinal(i)}: ${describe(c)}" }
            return Decision.Disambiguate(options, "I know more than one way to do that. ${names.joinToString("; ")}. Which one?")
        }
        val qty = top.slots["qty"]?.toIntOrNull()
        return when {
            qty != null && qty > MAX_QTY -> Decision.Confirm(top, "That's $qty items. Are you sure? Say yes to continue.")
            top.score < CONFIRM_BELOW || top.source == "similar" -> Decision.Confirm(top, "Do you want me to ${describe(top)}? Say yes or no.")
            else -> Decision.Proceed(top)
        }
    }

    fun describe(c: Candidate): String {
        var s = c.flow.template
        c.slots.forEach { (k, v) -> s = s.replace("{$k}", v) }
        s = s.replace(Regex("\\{\\w+\\}"), "…")
        // Values the template has no place for (set at the cart / address list).
        if ("{qty}" !in c.flow.template) c.slots["qty"]?.takeIf { it != "1" }?.let { q -> s = s.replaceFirst(" ", " $q ") }
        if ("{address}" !in c.flow.template) c.slots["address"]?.let { s += " to $it" }
        val app = c.flow.appLabel ?: c.flow.appPackage.substringAfterLast('.')
        return if (s.contains(app, ignoreCase = true)) s else "$s on $app"
    }

    private fun ordinal(i: Int) = listOf("first", "second", "third").getOrElse(i) { "next" }
}
