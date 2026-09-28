package com.echoflow.core.safety

/** Ordered by hand-off priority: the first present kind is the one announced. */
enum class SensitiveKind(val spoken: String) {
    PASSWORD("a password screen"),
    OTP("an OTP screen"),
    PAYMENT("a payment screen"),
    LOGIN("a login screen"),
    OPAQUE_UNKNOWN("a screen I can't read"),

    /**
     * Never produced by the classifier. A replay that finishes its taught steps on a checkout
     * screen hands off with this kind via [SafetyGuard.handOffAtCheckout].
     */
    CHECKOUT("the checkout screen"),
}

enum class SignalStrength(val weight: Int) { STRONG(3), MEDIUM(1) }

/**
 * Why a kind was flagged. [evidence] is always a lexicon phrase, a rule name, or a package
 * name — never user-entered text — so verdicts are safe to log and display.
 */
data class SafetySignal(
    val kind: SensitiveKind,
    val rule: String,
    val strength: SignalStrength,
    val evidence: String,
    val elementIndex: Int? = null,
)

/**
 * The screen shows a button that spends money or places an order ("Pay ₹632 using Debit card",
 * "Place order") but no credential fields or payment-method list. Safe taps (address, quantity)
 * are allowed there; the commit button itself never is (see [ActionRiskClassifier]).
 */
data class CheckoutSignal(
    /** Lexicon phrase or rule that matched, e.g. "pay ₹" or "place order". */
    val evidence: String,
    /** Amount shown on the commit button, e.g. "₹632", when there is one. */
    val amount: String? = null,
    val elementIndex: Int? = null,
)

data class ScreenVerdict(
    val snapshotId: Long,
    val packageName: String?,
    val kinds: Set<SensitiveKind>,
    val signals: List<SafetySignal>,
    /** Set when a commit button is visible; only meaningful when the screen is not sensitive. */
    val checkout: CheckoutSignal? = null,
) {
    val isSensitive: Boolean get() = kinds.isNotEmpty()

    /** Not a hand-off screen, but one tap away from spending money. */
    val isCheckout: Boolean get() = !isSensitive && checkout != null

    val primaryKind: SensitiveKind? get() = SensitiveKind.entries.firstOrNull { it in kinds }

    /** "PAYMENT", "CHECKOUT" or "SAFE": one word for overlays, file names and announcements. */
    val label: String get() = primaryKind?.name ?: if (isCheckout) "CHECKOUT" else "SAFE"

    fun summary(maxSignals: Int = 3): String = when {
        isSensitive -> {
            val reasons = signals.filter { it.kind in kinds }
                .sortedByDescending { it.strength.weight }
                .take(maxSignals)
                .joinToString("; ") { "${it.rule}: ${it.evidence}" }
            "${kinds.joinToString("+")} — $reasons"
        }
        isCheckout -> "CHECKOUT — commit-button: ${checkout!!.evidence}" + (checkout.amount?.let { " ($it)" } ?: "")
        else -> "SAFE"
    }

    companion object {
        const val TRIP_THRESHOLD = 3
    }
}
