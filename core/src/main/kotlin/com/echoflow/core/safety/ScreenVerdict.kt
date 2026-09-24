package com.echoflow.core.safety

/** Ordered by hand-off priority: the first present kind is the one announced. */
enum class SensitiveKind(val spoken: String) {
    PASSWORD("a password screen"),
    OTP("an OTP screen"),
    PAYMENT("a payment screen"),
    LOGIN("a login screen"),
    OPAQUE_UNKNOWN("a screen I can't read"),
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

data class ScreenVerdict(
    val snapshotId: Long,
    val packageName: String?,
    val kinds: Set<SensitiveKind>,
    val signals: List<SafetySignal>,
) {
    val isSensitive: Boolean get() = kinds.isNotEmpty()

    val primaryKind: SensitiveKind? get() = SensitiveKind.entries.firstOrNull { it in kinds }

    fun summary(maxSignals: Int = 3): String =
        if (!isSensitive) {
            "SAFE"
        } else {
            val reasons = signals.filter { it.kind in kinds }
                .sortedByDescending { it.strength.weight }
                .take(maxSignals)
                .joinToString("; ") { "${it.rule}: ${it.evidence}" }
            "${kinds.joinToString("+")} — $reasons"
        }

    companion object {
        const val TRIP_THRESHOLD = 3
    }
}
