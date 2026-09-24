package com.echoflow.core.safety

import com.echoflow.core.model.InputTypes
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.safety.SignalStrength.MEDIUM
import com.echoflow.core.safety.SignalStrength.STRONG
import com.echoflow.core.text.Phrase
import com.echoflow.core.text.TextNormalizer

/**
 * Local, deterministic classifier: is this screen one where automation must stop and hand
 * control to the user? No network, no LLM. Signals are OR-ed per kind; a kind is flagged once
 * its signal weights reach [ScreenVerdict.TRIP_THRESHOLD] (one STRONG, or three MEDIUMs).
 */
class ScreenSafetyClassifier(
    private val ownPackage: String? = null,
    private val lex: SafetyLexicon = SafetyLexicon,
) {
    fun classify(snapshot: ScreenSnapshot): ScreenVerdict {
        val elements = snapshot.appElements().filter { ownPackage == null || it.packageName != ownPackage }
        val ctx = Ctx(snapshot, elements)
        val signals = mutableListOf<SafetySignal>()
        packageSignals(ctx, signals)
        fieldSignals(ctx, signals)
        textSignals(ctx, signals)
        opaqueSignals(ctx, signals)

        val distinct = signals.distinctBy { Triple(it.kind, it.rule, it.evidence) }
        val kinds = distinct.groupBy { it.kind }
            .filterValues { list -> list.sumOf { it.strength.weight } >= ScreenVerdict.TRIP_THRESHOLD }
            .keys
        return ScreenVerdict(snapshot.id, snapshot.packageName, kinds, distinct)
    }

    private class Ctx(val snapshot: ScreenSnapshot, val elements: List<UiElement>) {
        val visibleLabeled: List<Pair<UiElement, List<String>>> = elements
            .filter { it.visible && !it.editable }
            .mapNotNull { e -> e.label?.let { e to TextNormalizer.tokens(it) } }
            .filter { it.second.isNotEmpty() }
        val editables: List<UiElement> = elements.filter { it.editable || it.simpleClassName.contains("EditText") }

        fun shortLabels(maxTokens: Int) = visibleLabeled.asSequence().filter { it.second.size <= maxTokens }
        fun anyShortLabelHas(phrases: List<Phrase>, maxTokens: Int = SafetyLexicon.SHORT_LABEL_MAX_TOKENS) =
            shortLabels(maxTokens).any { (_, t) -> phrases.any { it.foundIn(t) } }
    }

    private fun packageSignals(ctx: Ctx, out: MutableList<SafetySignal>) {
        val packages = buildSet {
            ctx.snapshot.packageName?.let(::add)
            ctx.snapshot.windows.mapNotNullTo(this) { it.packageName }
            ctx.elements.mapNotNullTo(this) { it.packageName }
        } - setOfNotNull(ownPackage)
        for (pkg in packages) {
            if (matchesPackage(pkg, lex.paymentPackages)) {
                out += SafetySignal(SensitiveKind.PAYMENT, "payment-app", STRONG, pkg)
            }
            if (matchesPackage(pkg, lex.loginPackages)) {
                // Play services also hosts harmless dialogs (location accuracy), so it only counts
                // as MEDIUM; the account picker still trips via its "choose an account" text.
                val strength = if (pkg.startsWith("com.google.android.gms")) MEDIUM else STRONG
                out += SafetySignal(SensitiveKind.LOGIN, "login-app", strength, pkg)
            }
        }
    }

    private fun fieldSignals(ctx: Ctx, out: MutableList<SafetySignal>) {
        val screenMentionsOtp = ctx.anyShortLabelHas(lex.otpTerms, SafetyLexicon.PROSE_MAX_TOKENS)
        val screenHasLoginVerb = ctx.anyShortLabelHas(lex.loginVerbs)

        for (field in ctx.editables) {
            // Never read field.text here: it is user input.
            val labelTokens = TextNormalizer.tokens(field.hintText) +
                TextNormalizer.tokens(field.contentDescription) +
                TextNormalizer.viewIdTokens(field.viewId)

            if (field.password || InputTypes.isPasswordVariation(field.inputType)) {
                out += SafetySignal(SensitiveKind.PASSWORD, "password-field", STRONG, "isPassword", field.index)
            }
            val isPinCode = lex.pinCodeExclusions.any { it.foundIn(labelTokens) }
            lex.passwordFieldLabels.firstOrNull { it.foundIn(labelTokens) }?.let {
                if (!(isPinCode && it.source in setOf("pin", "पिन"))) {
                    out += SafetySignal(SensitiveKind.PASSWORD, "secret-field-label", STRONG, it.source, field.index)
                }
            }
            lex.paymentFieldLabels.firstOrNull { it.foundIn(labelTokens) }?.let {
                out += SafetySignal(SensitiveKind.PAYMENT, "card-field", STRONG, it.source, field.index)
            }
            lex.otpTerms.firstOrNull { it.foundIn(labelTokens) }?.let {
                out += SafetySignal(SensitiveKind.OTP, "otp-field", STRONG, it.source, field.index)
            }
            if (InputTypes.isNumeric(field.inputType) && field.maxTextLength in 4..8 && screenMentionsOtp) {
                out += SafetySignal(SensitiveKind.OTP, "otp-numeric-field", STRONG, "numeric maxLength=${field.maxTextLength}", field.index)
            }
            lex.identityFieldLabels.firstOrNull { it.foundIn(labelTokens) }?.let {
                if (screenHasLoginVerb) {
                    out += SafetySignal(SensitiveKind.LOGIN, "identity-field+login-verb", STRONG, it.source, field.index)
                }
            }
        }

        val singleCharBoxes = ctx.editables.count { it.maxTextLength == 1 }
        if (singleCharBoxes >= 4) {
            out += SafetySignal(SensitiveKind.OTP, "otp-boxes", STRONG, "$singleCharBoxes single-char fields")
        }
    }

    private fun textSignals(ctx: Ctx, out: MutableList<SafetySignal>) {
        val hasEditable = ctx.editables.isNotEmpty()
        for ((e, tokens) in ctx.visibleLabeled) {
            val n = tokens.size
            if (n <= SafetyLexicon.SHORT_LABEL_MAX_TOKENS) {
                lex.paymentStrong.firstOrNull { it.foundIn(tokens) }?.let {
                    out += SafetySignal(SensitiveKind.PAYMENT, "payment-phrase", STRONG, it.source, e.index)
                }
                if (isPayButtonLabel(tokens)) {
                    out += SafetySignal(SensitiveKind.PAYMENT, "pay-button", STRONG, tokens.take(2).joinToString(" "), e.index)
                }
                lex.otpTerms.firstOrNull { it.foundIn(tokens) }?.let {
                    out += SafetySignal(SensitiveKind.OTP, "otp-term", if (hasEditable) STRONG else MEDIUM, it.source, e.index)
                }
                lex.loginHeadings.firstOrNull { it.tokens == tokens }?.let {
                    out += SafetySignal(SensitiveKind.LOGIN, "login-heading", MEDIUM, it.source, e.index)
                }
            }
            if (n <= SafetyLexicon.PROSE_MAX_TOKENS) {
                // OTP prompts are specific enough to trust in full sentences ("Enter the 6-digit OTP sent to ...").
                lex.otpScreenPhrases.firstOrNull { it.foundIn(tokens) }?.let {
                    out += SafetySignal(SensitiveKind.OTP, "otp-phrase", STRONG, it.source, e.index)
                }
            }
            if (n <= SafetyLexicon.SHORT_LABEL_MAX_TOKENS + 2) {
                lex.loginStrong.firstOrNull { it.foundIn(tokens) }?.let {
                    out += SafetySignal(SensitiveKind.LOGIN, "login-phrase", STRONG, it.source, e.index)
                }
            }
            if (n <= SafetyLexicon.OPTION_LABEL_MAX_TOKENS) {
                lex.paymentMedium.filter { it.foundIn(tokens) }.forEach {
                    out += SafetySignal(SensitiveKind.PAYMENT, "payment-option", MEDIUM, it.source, e.index)
                }
            }
        }
    }

    private fun isPayButtonLabel(tokens: List<String>): Boolean = lex.isPayButton(tokens)

    private fun opaqueSignals(ctx: Ctx, out: MutableList<SafetySignal>) {
        val screenArea = ctx.snapshot.screenArea.coerceAtLeast(1)
        for (web in ctx.elements.filter { it.visible && it.simpleClassName == "WebView" }) {
            if (web.bounds.area * 2 < screenArea) continue
            val readable = ctx.snapshot.descendants(web.index).count { it.visible && it.label != null }
            if (readable <= 3) {
                out += SafetySignal(SensitiveKind.OPAQUE_UNKNOWN, "empty-webview", STRONG, "$readable readable nodes", web.index)
            }
        }
        val loading = ctx.elements.any { it.visible && it.simpleClassName.contains("ProgressBar") }
        if (ctx.visibleLabeled.isEmpty() && ctx.editables.isEmpty() && !loading) {
            out += SafetySignal(SensitiveKind.OPAQUE_UNKNOWN, "no-readable-content", STRONG, "${ctx.elements.size} nodes")
        }
    }

    private fun matchesPackage(pkg: String, list: List<String>): Boolean =
        list.any { entry -> if (entry.endsWith('.')) pkg.startsWith(entry) else pkg == entry || pkg.startsWith("$entry.") }
}
