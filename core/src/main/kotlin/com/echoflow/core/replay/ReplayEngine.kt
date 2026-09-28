package com.echoflow.core.replay

import com.echoflow.core.flow.Descriptors
import com.echoflow.core.flow.ElementDescriptor
import com.echoflow.core.flow.ElementResolver
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.Resolution
import com.echoflow.core.flow.Step
import com.echoflow.core.flow.fill
import com.echoflow.core.gateway.ActionOutcome
import com.echoflow.core.gateway.GateContext
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.model.WindowType
import com.echoflow.core.runlog.RunStatus
import com.echoflow.core.safety.GuardState
import com.echoflow.core.safety.SafetyGuard
import com.echoflow.core.safety.SafetyLexicon
import com.echoflow.core.safety.SensitiveKind
import com.echoflow.core.text.TextNormalizer
import kotlinx.coroutines.CancellationException

/** What the replay engine needs from the device. Implemented by the app; faked in tests. */
interface ReplayHost {
    fun current(): ScreenSnapshot?

    /** Waits for a snapshot newer than [afterId] and then for the screen to go quiet. */
    suspend fun awaitSettled(afterId: Long, timeoutMs: Long): ScreenSnapshot?

    suspend fun perform(action: PlannedAction, context: GateContext): ActionOutcome

    /** Speaks a question and returns the user's answer text, or null if none. */
    suspend fun ask(question: String, choices: List<String> = emptyList()): String?

    fun say(text: String)

    fun progress(step: Int, total: Int, description: String) {}

    /** Small persistent memory (e.g. the delivery address chosen last time, per app). */
    fun recall(key: String): String? = null
    fun remember(key: String, value: String) {}

    fun nowMs(): Long = System.currentTimeMillis()
}

data class ReplayResult(
    val status: RunStatus,
    val message: String,
    /** 1-based index of the step being worked on when the run ended. */
    val stoppedAtStep: Int,
    val totalSteps: Int,
    val stepDescription: String?,
    val events: List<String>,
)

/**
 * Replays a taught [Flow] step by step: resolve the element → act through the gateway →
 * wait for the screen to settle. When a target is missing it tries, in order: skipping ahead
 * (the step wasn't needed this time), dismissing a popup, submitting typed text, scrolling,
 * waiting — and otherwise asks a specific question or stops with a specific reason. It never
 * taps anything it isn't confident about.
 */
class ReplayEngine(
    private val host: ReplayHost,
    private val guard: SafetyGuard,
    private val resolver: ElementResolver = ElementResolver(),
    private val stepBudgetMs: Long = STEP_BUDGET_MS,
) {
    private val events = mutableListOf<String>()

    /** Field typed into most recently, for "press enter" when results don't appear. */
    private var lastTyped: ElementDescriptor? = null

    /** The address sheet is answered at most once per run. */
    private var addressHandled = false

    suspend fun run(flow: Flow, slotValues: Map<String, String>): ReplayResult {
        events.clear()
        lastTyped = null
        addressHandled = false
        val slots = slotValues.toMutableMap()
        val steps = flow.steps
        var i = 0
        try {
            while (i < steps.size) {
                val step = steps[i]
                host.progress(i + 1, steps.size, step.description)
                when (val r = runStep(flow, steps, i, slots)) {
                    is StepResult.Done -> {
                        if (step is Step.LaunchApp) ensureAddress(flow, slots)?.let { return result(it.status, it.message, i, steps) }
                        i++
                    }
                    is StepResult.SkipTo -> {
                        events += "step ${i + 1} not needed this time; continued at step ${r.index + 1}"
                        i = r.index
                    }
                    is StepResult.Stop -> return result(r.status, r.message, i, steps)
                    StepResult.Retry -> Unit // runStep resolves retries itself; never returned here
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
        // All steps done: are we at the payment boundary?
        var final = readable(host.awaitSettled(host.current()?.id ?: 0, 1_500) ?: host.current())
        // The last tap can open a dialog (replace cart), an options sheet or an address sheet.
        repeat(3) {
            val s = final ?: return@repeat
            val stop: StepResult.Stop? = when {
                decisionDialog(s) != null -> handleDecisionDialog(s, decisionDialog(s)!!) as StepResult.Stop?
                customisationSheet(s) != null -> handleCustomisation(s, slots)
                !addressHandled && addressOptions(s).isNotEmpty() -> { addressHandled = true; handleAddressSheet(s, flow, slots) }
                else -> return@repeat
            }
            if (stop != null) return result(stop.status, stop.message, steps.size - 1, steps)
            final = readable(host.awaitSettled(s.id, 2_000) ?: host.current())
        }
        (guard.currentState as? GuardState.Tripped)?.let {
            return result(RunStatus.HANDED_OFF, it.trip.handOffMessage, steps.size - 1, steps)
        }
        if (final != null) {
            if (guard.classify(final).isCheckout) {
                adjustQuantity(final, flow, slots)?.let { return result(it.status, it.message, steps.size - 1, steps) }
                final = readable(host.current()) ?: final
            }
            guard.handOffAtCheckout(final)?.let { return result(RunStatus.HANDED_OFF, it.handOffMessage, steps.size - 1, steps) }
            // Taught to end at checkout, but the app's cart button didn't report its tap while
            // teaching (custom views): open the cart ourselves. Navigating toward payment is allowed.
            if (flow.endedAt == "CHECKOUT") {
                openCart(final)?.let { opened ->
                    var cart = readable(opened) ?: opened
                    if (guard.classify(cart).isCheckout) {
                        adjustQuantity(cart, flow, slots)?.let { return result(it.status, it.message, steps.size - 1, steps) }
                        cart = readable(host.current()) ?: cart
                    }
                    guard.handOffAtCheckout(cart)?.let { return result(RunStatus.HANDED_OFF, it.handOffMessage, steps.size - 1, steps) }
                }
                (guard.currentState as? GuardState.Tripped)?.let { return result(RunStatus.HANDED_OFF, it.trip.handOffMessage, steps.size - 1, steps) }
            }
        }
        return result(RunStatus.COMPLETED, "Done. I finished all ${steps.size} steps.", steps.size - 1, steps)
    }

    private sealed interface StepResult {
        data object Done : StepResult
        data class SkipTo(val index: Int) : StepResult
        data object Retry : StepResult
        data class Stop(val status: RunStatus, val message: String) : StepResult
    }

    private suspend fun runStep(flow: Flow, steps: List<Step>, i: Int, slots: MutableMap<String, String>): StepResult {
        val step = steps[i]
        if (step is Step.LaunchApp) return launch(step, steps, slots)

        // A slot this step needs but the command didn't give (bonus B3: ask mid-flow).
        val slotName = when (step) {
            is Step.Tap -> step.slot
            is Step.TypeText -> step.slot
            is Step.RepeatTap -> step.slot
            else -> null
        }
        if (slotName != null && slots[slotName].isNullOrBlank()) {
            val snap = host.current()
            val choices = if (step is Step.Tap && snap != null) optionsLike(snap, step.target) else emptyList()
            val q = "Which $slotName should I use?" + if (choices.isNotEmpty()) " I can see: ${choices.joinToString(", ")}." else ""
            val answer = host.ask(q, choices)?.trim()
            if (answer.isNullOrBlank()) return StepResult.Stop(RunStatus.NO_ANSWER, "I needed the $slotName and didn't get an answer.")
            slots[slotName] = if (slotName == "qty") (answer.toIntOrNull() ?: wordNumber(answer) ?: 1).toString() else answer
            events += "asked for $slotName: ${slots[slotName]}"
        }

        val started = host.nowMs()
        var scrolls = 0
        var triedIme = false
        var triedDismiss = 0
        var askedAboutValue = false
        var staleRetries = 0
        while (host.nowMs() - started < stepBudgetMs) {
            val snap = host.current() ?: return StepResult.Stop(RunStatus.HALTED, "I can't see the screen.")
            tripped()?.let { return it }
            val preview = guard.classify(snap)
            if (preview.kinds == setOf(SensitiveKind.OPAQUE_UNKNOWN) && host.nowMs() - started < OPAQUE_GRACE_MS) {
                // Blank frames during screen transitions: give the app a moment to draw.
                host.awaitSettled(snap.id, 1_000)
                continue
            }
            if (preview.isSensitive && guard.onSnapshot(snap).isSensitive) return tripped() ?: StepResult.Stop(RunStatus.HANDED_OFF, "Sensitive screen.")
            if (snap.packageName != null && snap.packageName != flow.appPackage && !isOwnOrSystem(snap.packageName)) {
                // Some other app came to the front: try back once, then stop.
                if (triedDismiss < 1) {
                    triedDismiss++
                    events += "left ${snap.packageName}; pressed back"
                    act(PlannedAction.Back, GateContext(isRecovery = true), snap)
                    continue
                }
                return StepResult.Stop(RunStatus.HALTED, "I ended up in another app (${snap.packageName}) at step ${i + 1}.")
            }

            // Dialogs that need the user's decision (T7): never auto-confirmed.
            decisionDialog(snap)?.let { return handleDecisionDialog(snap, it) ?: return@let }
            // An options sheet this item has but the taught one didn't (L3): ask, then continue.
            if (customisationSheet(snap) != null) {
                handleCustomisation(snap, slots)?.let { return it }
                continue
            }
            // "Select delivery address" sheet: pick the {address} slot, or last time's, or ask (T6).
            if (addressOptions(snap).isNotEmpty() && !addressHandled) {
                addressHandled = true
                handleAddressSheet(snap, flow, slots)?.let { return it }
                continue
            }

            val resolution = resolve(step, snap, slots)
            if (resolution != null) {
                val r = perform(step, snap, resolution, slots)
                // The screen changed between finding the element and tapping it: find it again.
                if (r is StepResult.Retry && staleRetries++ < 3) continue
                return if (r is StepResult.Retry) StepResult.Stop(RunStatus.HALTED, "The screen kept changing, so I stopped at step ${i + 1}.") else r
            }

            // Lookahead: the screen already matches a later step (optional screen skipped).
            lookahead(steps, i, snap, slots)?.let { return StepResult.SkipTo(it) }

            // A popup covering the screen (promo, rating, location): dismiss via whitelist.
            if (triedDismiss < 2) {
                val dismiss = dismissButton(snap)
                if (dismiss != null) {
                    triedDismiss++
                    events += "closed popup via \"${snap.elements[dismiss].label}\""
                    act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, dismiss)), GateContext(isRecovery = true), snap)
                    continue
                }
            }

            // Typed text but the app wants "enter" before showing results.
            val typedField = lastTyped
            if (!triedIme && typedField != null) {
                triedIme = true
                lastTyped = null
                val field = resolver.resolve(snap, typedField, slots, editableOnly = true)
                if (field != null) {
                    events += "pressed enter on the search field"
                    act(PlannedAction.ImeEnter(snap.id, field.index), GateContext(explicitlyTaught = true, resolverConfidence = field.score), snap)
                    continue
                }
            }

            // A slot value that isn't on screen: scroll a bit, then ask with what we see.
            if (scrolls < MAX_SCROLLS) {
                val list = scrollableList(snap)
                if (list != null) {
                    scrolls++
                    act(PlannedAction.Scroll(snap.id, list.index, forward = true), GateContext(isRecovery = true), snap)
                    continue
                }
            }
            if (step is Step.Tap && step.slot != null && !askedAboutValue) {
                askedAboutValue = true
                val options = optionsLike(snap, step.target)
                if (options.isNotEmpty()) {
                    val want = slots[step.slot].orEmpty()
                    val answer = host.ask("I can't find \"$want\". I can see: ${options.joinToString(", ")}. Which one should I pick?", options)
                    val chosen = answer?.let { a -> options.firstOrNull { ElementResolver.textMatch(it, a) > 0 || ElementResolver.textMatch(a, it) > 0 } }
                    if (chosen == null) return StepResult.Stop(RunStatus.NO_ANSWER, "I couldn't find \"$want\" and didn't get a choice I could use.")
                    slots[step.slot] = chosen
                    events += "user picked \"$chosen\" instead of \"$want\""
                    continue
                }
            }

            // Nothing to do but wait for the screen (loading).
            host.awaitSettled(snap.id, 1_500)
        }

        val snap = host.current()
        return StepResult.Stop(RunStatus.HALTED, stuckMessage(step, i, steps.size, snap, slots))
    }

    private suspend fun launch(step: Step.LaunchApp, steps: List<Step>, slots: Map<String, String>): StepResult {
        val before = host.current()
        when (val o = host.perform(PlannedAction.LaunchApp(step.packageName), GateContext(explicitlyTaught = true, resolverConfidence = 1.0))) {
            is ActionOutcome.Blocked -> return StepResult.Stop(RunStatus.HALTED, "I'm not allowed to open ${step.appLabel ?: step.packageName}.")
            is ActionOutcome.Failed -> return StepResult.Stop(RunStatus.HALTED, "I couldn't open ${step.appLabel ?: step.packageName}. Is it installed?")
            is ActionOutcome.Performed -> Unit
        }
        host.awaitSettled(before?.id ?: 0, 4_000)
        // Normalise the start: back out until the first real step is visible (max 3 backs).
        val first = steps.drop(1).firstOrNull() ?: return StepResult.Done
        repeat(4) { attempt ->
            val snap = readable(host.current()) ?: return StepResult.Done
            tripped()?.let { return it }
            if (resolve(first, snap, slots) != null || decisionDialog(snap) != null || dismissButton(snap) != null ||
                addressOptions(snap).isNotEmpty() || customisationSheet(snap) != null
            ) return StepResult.Done
            if (snap.packageName != step.packageName) {
                host.awaitSettled(snap.id, 1_500)
                return@repeat
            }
            if (attempt < 3) {
                events += "pressed back to reach the start screen"
                act(PlannedAction.Back, GateContext(isRecovery = true), snap)
            }
        }
        return StepResult.Done
    }

    private fun resolve(step: Step, snap: ScreenSnapshot, slots: Map<String, String>): Resolution? = when (step) {
        is Step.Tap -> if (step.slot != null) {
            ElementResolver(SLOT_TAP_SCORE).resolve(snap, step.target, slots, requiredValue = slots[step.slot])
        } else {
            resolver.resolve(snap, step.target, slots)
        }
        is Step.TypeText -> ElementResolver(FIELD_SCORE).resolve(snap, step.target, slots, editableOnly = true)
        is Step.RepeatTap -> resolver.resolve(snap, step.target, slots)
        is Step.LaunchApp -> null
    }

    private suspend fun perform(step: Step, snap: ScreenSnapshot, r: Resolution, slots: Map<String, String>): StepResult {
        val ctx = GateContext(explicitlyTaught = true, resolverConfidence = r.score)
        val outcome = when (step) {
            is Step.Tap -> act(PlannedAction.Click(snap.id, r.actionIndex), ctx, snap)
            is Step.TypeText -> {
                val text = step.slot?.let { slots[it] } ?: step.literal.orEmpty()
                act(PlannedAction.SetText(snap.id, r.index, text), ctx, snap).also {
                    if (it is ActionOutcome.Performed) lastTyped = step.target
                }
            }
            is Step.RepeatTap -> {
                val times = (slots[step.slot]?.toIntOrNull() ?: 1) - step.offset
                var last: ActionOutcome = ActionOutcome.Performed(snap, null)
                var current = snap
                var resolution: Resolution? = r
                repeat(times.coerceAtLeast(0)) { n ->
                    val res = resolution ?: return StepResult.Stop(RunStatus.HALTED, "I lost the quantity button after ${n} taps.")
                    last = act(PlannedAction.Click(current.id, res.actionIndex), ctx.copy(resolverConfidence = res.score), current)
                    if (last !is ActionOutcome.Performed) return@repeat
                    current = host.current() ?: current
                    resolution = resolver.resolve(current, step.target, slots)
                }
                if (times <= 0) events += "quantity already right; no taps needed"
                last
            }
            is Step.LaunchApp -> ActionOutcome.Performed(snap, null)
        }
        return when (outcome) {
            is ActionOutcome.Performed -> tripped() ?: StepResult.Done
            is ActionOutcome.Blocked -> tripped()
                ?: if (outcome.decision.reason == com.echoflow.core.gateway.BlockReason.STALE_SNAPSHOT) StepResult.Retry
                else StepResult.Stop(RunStatus.HALTED, "I stopped: ${outcome.decision.detail}.")
            is ActionOutcome.Failed -> StepResult.Stop(RunStatus.HALTED, "The tap on \"${stepTarget(step)?.display}\" didn't work (${outcome.message}).")
        }
    }

    private suspend fun act(action: PlannedAction, ctx: GateContext, snap: ScreenSnapshot): ActionOutcome {
        val o = host.perform(action, ctx)
        if (o is ActionOutcome.Performed) host.awaitSettled(snap.id, 3_000)
        return o
    }

    private fun describe(o: ActionOutcome): String = when (o) {
        is ActionOutcome.Blocked -> "blocked: ${o.decision.reason} ${o.decision.detail}"
        is ActionOutcome.Failed -> "failed: ${o.message}"
        is ActionOutcome.Performed -> "done"
    }

    private fun tripped(): StepResult.Stop? =
        (guard.currentState as? GuardState.Tripped)?.let { StepResult.Stop(RunStatus.HANDED_OFF, it.trip.handOffMessage) }

    /**
     * Saved-address rows of a "Select delivery address" sheet/screen: label (e.g. "Home") → row
     * element. Rows are clickable elements whose first short label is a saved-address name.
     */
    private fun addressOptions(snap: ScreenSnapshot): Map<String, UiElement> {
        val all = snap.appElements().filter { it.visible }
        val heading = all.any { e -> e.label?.let { TextNormalizer.normalize(it) }?.let { l -> ADDRESS_HEADINGS.any { l.contains(it) } } == true }
        if (!heading) return emptyMap()
        // Rows below a "recently searched" heading are past searches, not saved addresses.
        val recentTop = all.filter { TextNormalizer.normalize(it.label).startsWith("recent") }.minOfOrNull { it.bounds.top } ?: Int.MAX_VALUE
        val out = linkedMapOf<String, UiElement>()
        for (row in all.filter { it.clickable && it.bounds.top < recentTop }) {
            val labels = snap.descendants(row.index, maxDepth = 3).filter { it.visible }.sortedBy { it.index }
                .mapNotNull { it.label }
                .filter { l -> !l.contains("icon", ignoreCase = true) && TextNormalizer.normalize(l) !in setOf("selected", "default") }
                .toList()
            if (labels.size < 2) continue // a name plus the full address line
            val name = labels.first().trim()
            val n = TextNormalizer.tokens(name)
            if (n.isEmpty() || n.size > 3 || NOT_ADDRESS.any { TextNormalizer.normalize(name).contains(it) }) continue
            if (labels.drop(1).none { it.length > 20 }) continue // must have a real address line
            out.putIfAbsent(name, row)
        }
        return out
    }

    /**
     * T6 when the taught flow had no address step: the command named an address ("… to home")
     * that isn't the one selected. Open the app's delivery-address bar ("Selected address is
     * Hostel, …") and pick the saved address from the list.
     */
    private suspend fun ensureAddress(flow: Flow, slots: MutableMap<String, String>): StepResult.Stop? {
        val want = slots["address"] ?: return null
        if (flow.steps.any { it is Step.Tap && it.slot == "address" }) return null // taught explicitly
        val snap = readable(host.current()) ?: return null
        val bar = snap.appElements().firstOrNull { e ->
            e.visible && TextNormalizer.normalize(e.label).let { l -> ADDRESS_BARS.any { l.startsWith(it) } }
        } ?: return null // no address bar on this app's start screen; nothing to do
        if (ElementResolver.valueMatch(want, bar.label, emptyList()) > 0) {
            events += "delivery address already $want"
            return null
        }
        // The bar's centre can be empty space; tap the short address name shown inside it.
        val b = bar.bounds
        val name = snap.appElements().filter { e ->
            e.visible && e.index != bar.index && (e.label?.length ?: 99) <= 24 &&
                e.bounds.left >= b.left && e.bounds.right <= b.right && e.bounds.top >= b.top - 60 && e.bounds.bottom <= b.bottom + 120
        }.minByOrNull { it.bounds.top * 10 + it.bounds.left } ?: bar
        val o = act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, name.index)), GateContext(explicitlyTaught = true, resolverConfidence = 0.9), snap)
        if (o !is ActionOutcome.Performed) return tripped() ?: StepResult.Stop(RunStatus.HALTED, "I couldn't open the delivery address list (${describe(o)}).")
        var list = readable(host.awaitSettled(snap.id, 3_000) ?: host.current()) ?: return null
        val started = host.nowMs()
        while (addressOptions(list).isEmpty() && host.nowMs() - started < 4_000) {
            list = readable(host.awaitSettled(list.id, 1_000) ?: host.current()) ?: return null
        }
        if (addressOptions(list).isEmpty()) return StepResult.Stop(RunStatus.HALTED, "I opened the address list but couldn't read the saved addresses.")
        addressHandled = true
        handleAddressSheet(list, flow, slots)?.let { return it }
        host.awaitSettled(list.id, 3_000)
        return null
    }

    private suspend fun handleAddressSheet(snap: ScreenSnapshot, flow: Flow, slots: MutableMap<String, String>): StepResult.Stop? {
        val options = addressOptions(snap)
        val key = "address:${flow.appPackage}"
        fun find(want: String?) = want?.let { w -> options.entries.firstOrNull { ElementResolver.valueMatch(w, it.key, emptyList()) > 0 } }
        var chosen = find(slots["address"])
        if (chosen == null && slots["address"] == null) {
            chosen = find(host.recall(key))?.also { events += "used last time's address \"${it.key}\"" }
        }
        if (chosen == null) {
            val names = options.keys.toList()
            val q = (slots["address"]?.let { "I can't find a saved address called \"$it\". " } ?: "") +
                "Which delivery address should I use: ${names.joinToString(" or ")}?"
            val answer = host.ask(q, names) ?: return StepResult.Stop(RunStatus.NO_ANSWER, "I needed a delivery address and didn't get one.")
            chosen = find(answer) ?: return StepResult.Stop(RunStatus.HALTED, "\"$answer\" isn't one of your saved addresses (${names.joinToString(", ")}).")
        }
        val (name, row) = chosen
        slots["address"] = name
        host.remember(key, name)
        events += "delivery address: $name"
        val now = host.current() ?: snap
        val freshRow = addressOptions(now)[name] ?: row.takeIf { now.id == snap.id }
            ?: return StepResult.Stop(RunStatus.HALTED, "The address list closed before I could pick $name.")
        val o = act(PlannedAction.Click(now.id, freshRow.index), GateContext(explicitlyTaught = true, resolverConfidence = 1.0), now)
        return if (o is ActionOutcome.Performed) null else tripped() ?: StepResult.Stop(RunStatus.HALTED, "I couldn't select the address $name.")
    }

    /**
     * T5 at the cart: set the item's quantity with its "+"/"−" stepper when the command asked for
     * one the taught flow didn't set. Reads the displayed count after every tap; never goes
     * below 1 ("−" at 1 removes the item).
     */
    private suspend fun adjustQuantity(start: ScreenSnapshot, flow: Flow, slots: Map<String, String>): StepResult.Stop? {
        val want = slots["qty"]?.toIntOrNull() ?: return null
        if (flow.steps.any { it is Step.RepeatTap }) return null // the flow sets quantity itself
        val item = slots["item"]
        var snap = start
        repeat(want + 3) {
            val row = cartRow(snap, item) ?: return if (want > 1) StepResult.Stop(RunStatus.HALTED, "I added ${item ?: "the item"} but couldn't find its quantity buttons in the cart.") else null
            val (count, plus, minus) = row
            if (count == want) {
                if (want > 1) events += "set quantity to $want"
                return null
            }
            val button = if (count < want) plus else minus.takeIf { count > 1 } ?: return null
            val o = act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, button.index)), GateContext(explicitlyTaught = true, resolverConfidence = 1.0), snap)
            if (o !is ActionOutcome.Performed) return tripped() ?: StepResult.Stop(RunStatus.HALTED, "I couldn't change the quantity (${describe(o)}).")
            snap = host.current() ?: return null
        }
        return StepResult.Stop(RunStatus.HALTED, "I couldn't get the quantity to $want.")
    }

    /** The cart line for [item]: its displayed count and its "+" / "−" buttons (same row on screen). */
    private fun cartRow(snap: ScreenSnapshot, item: String?): Triple<Int, UiElement, UiElement>? {
        val els = snap.appElements().filter { it.visible }
        fun cy(e: UiElement) = (e.bounds.top + e.bounds.bottom) / 2
        fun isPlus(e: UiElement) = e.label?.trim() == "+" || TextNormalizer.normalize(e.label).let { it.contains("add one more") || it.contains("increase") }
        fun isMinus(e: UiElement) = e.label?.trim() in setOf("−", "-") || TextNormalizer.normalize(e.label).let { it.contains("remove one") || it.contains("decrease") }
        val pluses = els.filter(::isPlus)
        val anchor = item?.let { i -> els.firstOrNull { ElementResolver.valueMatch(i, it.label, emptyList()) >= 0.8 } }
        val plus = if (anchor != null) pluses.minByOrNull { kotlin.math.abs(cy(it) - cy(anchor)) } else pluses.singleOrNull()
        plus ?: return null
        val minus = els.filter(::isMinus).minByOrNull { kotlin.math.abs(cy(it) - cy(plus)) + kotlin.math.abs(it.bounds.left - plus.bounds.left) / 4 } ?: return null
        val count = els.filter { e -> e.label?.trim()?.all(Char::isDigit) == true && kotlin.math.abs(cy(e) - cy(plus)) < 60 && e.bounds.left in minus.bounds.left..plus.bounds.right }
            .firstNotNullOfOrNull { it.label?.trim()?.toIntOrNull() } ?: return null
        return Triple(count, plus, minus)
    }

    /** The "add" button of an item-options sheet ("Choose customization for …" + "Add Item | ₹511"). */
    private fun customisationSheet(snap: ScreenSnapshot): UiElement? {
        val labels = popupLabels(snap).map(TextNormalizer::normalize)
        if (labels.none { l -> CUSTOMISE_WORDS.any { l.contains(it) } }) return null
        return snap.appElements().firstOrNull { e ->
            e.visible && e.label != null && TextNormalizer.normalize(e.label).let { l -> ADD_ITEM_WORDS.any { l.startsWith(it) } }
        }
    }

    private suspend fun handleCustomisation(snap: ScreenSnapshot, slots: Map<String, String>): StepResult.Stop? {
        val button = customisationSheet(snap) ?: return null
        val item = slots["item"] ?: "This item"
        val price = SafetyLexicon.amountOf(TextNormalizer.tokens(button.label))
        val answer = host.ask(
            "$item has extra options. Should I add it with the default choices" + (price?.let { " for $it" } ?: "") + "?",
            listOf("yes", "no"),
        )
        if (answer == null || !isYes(answer)) {
            return StepResult.Stop(if (answer == null) RunStatus.NO_ANSWER else RunStatus.HALTED, "Okay, I left the options open for you to choose.")
        }
        events += "added $item with default options"
        val now = host.current() ?: snap
        val fresh = customisationSheet(now) ?: return StepResult.Stop(RunStatus.HALTED, "The options sheet closed before I could add $item.")
        val o = act(PlannedAction.Click(now.id, Descriptors.clickableFor(now, fresh.index)), GateContext(explicitlyTaught = true, resolverConfidence = 1.0), now)
        return if (o is ActionOutcome.Performed) null else tripped() ?: StepResult.Stop(RunStatus.HALTED, "I couldn't add $item.")
    }

    /** Waits (up to [OPAQUE_GRACE_MS]) for a blank loading frame to be replaced by real content. */
    private suspend fun readable(snap: ScreenSnapshot?): ScreenSnapshot? {
        var s = snap ?: return null
        val start = host.nowMs()
        while (guard.classify(s).kinds == setOf(SensitiveKind.OPAQUE_UNKNOWN) && host.nowMs() - start < OPAQUE_GRACE_MS) {
            s = host.awaitSettled(s.id, 1_000) ?: host.current() ?: return s
        }
        return s
    }

    private suspend fun openCart(snap: ScreenSnapshot): ScreenSnapshot? {
        val button = snap.appElements()
            .filter { it.visible && it.label != null }
            .firstOrNull { e -> TextNormalizer.normalize(e.label).let { l -> CART_WORDS.any { w -> l == w || l.startsWith("$w ") } } }
            ?: return null
        events += "opened the cart via \"${button.label}\""
        val o = act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, button.index)), GateContext(explicitlyTaught = true, resolverConfidence = 0.9), snap)
        return if (o is ActionOutcome.Performed) host.awaitSettled(snap.id, 3_000) ?: host.current() else null
    }

    private fun lookahead(steps: List<Step>, i: Int, snap: ScreenSnapshot, slots: Map<String, String>): Int? {
        for (j in i + 1 until minOf(steps.size, i + 4)) {
            val later = steps[j]
            // Only plain taps may be skipped; typing or value-dependent steps must always run.
            val skipped = steps.subList(i, j)
            if (skipped.any { it !is Step.Tap || it.slot != null }) return null
            val r = resolve(later, snap, slots) ?: continue
            if (r.score >= LOOKAHEAD_SCORE) return j
        }
        return null
    }

    // ---- popups and dialogs ----

    private data class Dialog(val kind: String, val text: String)

    /** Dialogs asking to throw away state (replace/clear cart). These always go to the user. */
    private fun decisionDialog(snap: ScreenSnapshot): Dialog? {
        val labels = popupLabels(snap)
        val joined = labels.joinToString(" ").let(TextNormalizer::normalize)
        val hit = listOf("replace cart", "items already in cart", "start afresh", "clear cart", "discard", "replace item",
            "your cart contains", "items from another", "different restaurant").firstOrNull { joined.contains(it) }
        return hit?.let { Dialog("replace-cart", labels.filter { it.length > 15 }.maxByOrNull { it.length } ?: labels.first()) }
    }

    private suspend fun handleDecisionDialog(before: ScreenSnapshot, d: Dialog): StepResult? {
        val answer = host.ask("Your cart already has other items. The app says: ${d.text.trim()} Should I replace them?", listOf("yes", "no"))
        val yes = answer != null && isYes(answer)
        events += "asked about existing cart: ${if (yes) "replace" else "keep"}"
        val words = if (yes) listOf("replace", "yes", "start afresh", "clear", "ok") else listOf("no", "cancel", "keep")
        // Asking took seconds; act on the screen as it is now.
        val snap = host.current() ?: before
        if (decisionDialog(snap) == null) return StepResult.Stop(RunStatus.HALTED, "The cart dialog closed before I could answer it.")
        // Buttons only (the dialog title "Replace cart item?" also starts with "replace").
        val buttons = snap.appElements().filter { it.visible && it.label != null && (it.clickable || it.className.contains("Button")) }
        fun norm(e: UiElement) = TextNormalizer.tokens(e.label).joinToString(" ")
        val button = buttons.firstOrNull { e -> words.any { norm(e) == it } }
            ?: buttons.firstOrNull { e -> words.any { w -> norm(e).startsWith("$w ") } }
        if (!yes) {
            button?.let { act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, it.index)), GateContext(isRecovery = true), snap) }
            return StepResult.Stop(if (answer == null) RunStatus.NO_ANSWER else RunStatus.HALTED, "I kept your existing cart and stopped, as you asked.")
        }
        if (button == null) return StepResult.Stop(RunStatus.HALTED, "I couldn't find the replace button on the cart dialog.")
        // The user explicitly agreed, so this counts as a taught, fully confident step.
        val o = act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, button.index)), GateContext(explicitlyTaught = true, resolverConfidence = 1.0), snap)
        return if (o is ActionOutcome.Performed) null else StepResult.Stop(RunStatus.HALTED, "I couldn't replace the cart items (${describe(o)}).")
    }

    /** Labels inside a popup: a smaller application window on top, or a dialog/bottom-sheet node. */
    private fun popupLabels(snap: ScreenSnapshot): List<String> {
        val appWindows = snap.windows.filter { it.type == WindowType.APPLICATION }
        val top = appWindows.maxByOrNull { it.layer }
        val popupWindow = top?.takeIf { appWindows.size > 1 && it.bounds.area < snap.screenArea * 0.9 }
        val inPopup: (UiElement) -> Boolean = if (popupWindow != null) {
            { it.windowId == popupWindow.id }
        } else {
            val roots = snap.elements.filter { e -> POPUP_CLASSES.any { e.className.contains(it, ignoreCase = true) } || e.viewId?.contains("dialog", true) == true || e.viewId?.contains("bottom_sheet", true) == true }
            val ids = roots.flatMap { r -> listOf(r.index) + snap.descendants(r.index).map { it.index }.toList() }.toSet()
            ({ it.index in ids })
        }
        return snap.elements.filter { it.visible && inPopup(it) }.mapNotNull { it.label }
    }

    private fun dismissButton(snap: ScreenSnapshot): Int? {
        val labels = popupLabels(snap)
        if (labels.isEmpty()) return null
        val candidates = snap.appElements().filter { e -> e.visible && e.label != null && e.label in labels }
        return candidates.firstOrNull { e ->
            val t = TextNormalizer.tokens(e.label).joinToString(" ")
            DISMISS.any { w -> t == w } || (e.text.isNullOrBlank() && DISMISS_DESC.any { w -> t == w || t.startsWith("$w ") })
        }?.index
    }

    // ---- helpers ----

    private fun scrollableList(snap: ScreenSnapshot): UiElement? =
        snap.appElements().filter { it.scrollable && it.visible }.maxByOrNull { it.bounds.area }

    /** Labels of elements that look like the taught target (same class/parent): list options. */
    private fun optionsLike(snap: ScreenSnapshot, target: ElementDescriptor): List<String> {
        val parentSig = target.parentSignature
        // If the slot lives in the row (an "ADD" next to a dish name), offer the rows' names.
        val slotInRow = target.context.any { it.contains("{") } && target.text?.contains("{") != true && target.contentDescription?.contains("{") != true
        return snap.appElements()
            .filter { it.visible && it.label != null && it.className == target.className && (parentSig == null || snap.elements.getOrNull(it.parent)?.let(Descriptors::signature) == parentSig) }
            .mapNotNull { e ->
                if (slotInRow) {
                    Descriptors.rowContext(snap, e.index).firstOrNull { l -> l.any(Char::isLetter) && !l.trim().startsWith("₹") && l != e.label }
                } else {
                    e.label
                }
            }
            .map { it.take(40) }
            .distinct()
            .take(5)
    }

    private fun stuckMessage(step: Step, i: Int, total: Int, snap: ScreenSnapshot?, slots: Map<String, String>): String {
        val what = stepTarget(step)?.fill(slots)?.display ?: "the next button"
        val language = snap?.let { nonLatinShare(it) } ?: 0.0
        return when {
            language > 0.5 -> "The app seems to be in a different language, so I can't find \"$what\" (step ${i + 1} of $total). Please switch the app back to English."
            else -> "I couldn't find \"$what\" on this screen (step ${i + 1} of $total), so I stopped without tapping anything."
        }
    }

    private fun nonLatinShare(snap: ScreenSnapshot): Double {
        val labels = snap.appElements().mapNotNull { it.label }.filter { it.any(Char::isLetter) }
        if (labels.isEmpty()) return 0.0
        return labels.count { l -> l.count { it.isLetter() && it.code > 0x24F } > l.count { it.isLetter() } / 2 }.toDouble() / labels.size
    }

    private fun stepTarget(step: Step): ElementDescriptor? = when (step) {
        is Step.Tap -> step.target
        is Step.TypeText -> step.target
        is Step.RepeatTap -> step.target
        is Step.LaunchApp -> null
    }

    private fun isOwnOrSystem(pkg: String) = pkg == "com.echoflow" || pkg.startsWith("com.android.systemui")

    private fun result(status: RunStatus, message: String, i: Int, steps: List<Step>) = ReplayResult(
        status = status,
        message = message,
        stoppedAtStep = i + 1,
        totalSteps = steps.size,
        stepDescription = steps.getOrNull(i)?.description,
        events = events.toList(),
    )

    companion object {
        const val STEP_BUDGET_MS = 12_000L
        const val SLOT_TAP_SCORE = 0.5
        const val FIELD_SCORE = 0.45
        const val LOOKAHEAD_SCORE = 0.85
        const val MAX_SCROLLS = 3
        const val OPAQUE_GRACE_MS = 6_000L
        private val ADDRESS_HEADINGS = listOf("select delivery address", "select a delivery address", "choose a delivery address",
            "choose delivery address", "select address", "saved addresses", "deliver to", "choose address", "select delivery location")
        private val ADDRESS_BARS = listOf("selected address is", "delivering to", "deliver to", "delivery address")
        private val NOT_ADDRESS =listOf("enter location", "add address", "add new", "use current location", "grant", "search")
        private val CUSTOMISE_WORDS =listOf("customization", "customisation", "customize", "customise", "choose your", "add ons", "addons")
        private val ADD_ITEM_WORDS = listOf("add item", "add to cart", "add to bag")
        private val CART_WORDS =listOf("view cart", "checkout", "go to cart", "view bag", "go to bag", "proceed to cart")
        private val POPUP_CLASSES = listOf("Dialog", "BottomSheet", "PopupWindow")
        private val DISMISS = listOf("close", "not now", "no thanks", "skip", "later", "maybe later", "dismiss", "got it", "ok", "okay", "x", "×", "✕", "cancel")
        private val DISMISS_DESC = listOf("close", "dismiss", "cross", "close button", "navigate up")

        fun isYes(answer: String): Boolean {
            val t = TextNormalizer.tokens(answer)
            return t.any { it in setOf("yes", "yeah", "yep", "sure", "ok", "okay", "replace", "haan", "ha", "correct", "right", "go", "confirm", "do") }
        }

        fun wordNumber(answer: String): Int? = TextNormalizer.tokens(answer).firstNotNullOfOrNull {
            it.toIntOrNull() ?: com.echoflow.core.nlu.Utterances.numberWords[it]
        }
    }
}
