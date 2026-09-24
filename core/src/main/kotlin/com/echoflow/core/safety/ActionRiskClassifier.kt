package com.echoflow.core.safety

import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.text.TextNormalizer

enum class ActionRisk {
    SAFE,

    /** Moves toward payment without committing ("Proceed to pay"); the next screen gets checked. */
    NAVIGATES_TO_PAYMENT,

    /** Irreversible spend or order ("Pay ₹349", "Place order"). Never performed by automation. */
    COMMIT,

    /** Loses user state ("Remove", "Clear cart", "Log out"). Only when explicitly taught. */
    DESTRUCTIVE,
}

data class ActionAssessment(val risk: ActionRisk, val evidence: String? = null)

/**
 * Classifies what tapping [UiElement] would do, from its own label and the labels of its
 * descendants (clickable rows often hold their text in child TextViews).
 */
class ActionRiskClassifier(private val lex: SafetyLexicon = SafetyLexicon) {

    fun assess(snapshot: ScreenSnapshot, target: UiElement): ActionAssessment {
        val candidates = buildList {
            target.label?.let(::add)
            if (!target.editable) {
                snapshot.descendants(target.index, maxDepth = 3)
                    .filter { !it.editable }
                    .mapNotNull { it.label }
                    .take(MAX_DESCENDANT_LABELS)
                    .forEach(::add)
            }
        }.map(TextNormalizer::tokens).filter { it.isNotEmpty() }

        val isSlider = target.simpleClassName.contains("SeekBar") || target.simpleClassName.contains("Slider")
        var navigates: String? = null
        var destructive: String? = null

        for (tokens in candidates) {
            val nav = lex.navigateToPayment.firstOrNull { it.foundIn(tokens) }
            if (nav != null) {
                navigates = navigates ?: nav.source
                continue // "Proceed to pay ₹349" is navigation, not a pay button.
            }
            if (lex.isPayButton(tokens)) return ActionAssessment(ActionRisk.COMMIT, "pay button")
            lex.commitStart.firstOrNull { it.startsOf(tokens) }?.let { return ActionAssessment(ActionRisk.COMMIT, it.source) }
            lex.commitAnywhere.firstOrNull { it.foundIn(tokens) }?.let { return ActionAssessment(ActionRisk.COMMIT, it.source) }
            if (isSlider && ("pay" in tokens || "order" in tokens)) return ActionAssessment(ActionRisk.COMMIT, "slide control")
            if (destructive == null) destructive = lex.destructive.firstOrNull { it.foundIn(tokens) }?.source
        }
        return when {
            destructive != null -> ActionAssessment(ActionRisk.DESTRUCTIVE, destructive)
            navigates != null -> ActionAssessment(ActionRisk.NAVIGATES_TO_PAYMENT, navigates)
            else -> ActionAssessment(ActionRisk.SAFE)
        }
    }

    private companion object {
        const val MAX_DESCENDANT_LABELS = 8
    }
}
