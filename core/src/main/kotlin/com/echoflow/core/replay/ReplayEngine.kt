package com.echoflow.core.replay

import com.echoflow.core.flow.Descriptors
import com.echoflow.core.flow.ElementDescriptor
import com.echoflow.core.flow.ElementResolver
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.Resolution
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.SourceSlots
import com.echoflow.core.flow.Step
import com.echoflow.core.flow.fill
import com.echoflow.core.gateway.ActionOutcome
import com.echoflow.core.gateway.GateContext
import com.echoflow.core.gateway.PlannedAction
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.nlu.IntentMatcher
import com.echoflow.core.model.UiElement
import com.echoflow.core.model.WindowType
import com.echoflow.core.runlog.RunStatus
import com.echoflow.core.safety.EmptySheet
import com.echoflow.core.safety.GuardState
import com.echoflow.core.safety.SafetyGuard
import com.echoflow.core.safety.SafetyLexicon
import com.echoflow.core.safety.SensitiveKind
import com.echoflow.core.safety.Trip
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

    /** The value typed most recently ("brik oven"), for opening the matching result. */
    private var lastTypedValue: String? = null

    /** Things the user should hear at the end ("I kept the size that was already selected."). */
    private val notes = mutableListOf<String>()

    private val risk = com.echoflow.core.safety.ActionRiskClassifier()

    /** "Already in your cart" is decided at most once per run. */
    private var alreadyInCartNoted = false

    /** The address sheet is answered at most once per run. */
    private var addressHandled = false

    /** The options sheet is asked about at most once per run. */
    private var customisationHandled = false

    suspend fun run(flow: Flow, slotValues: Map<String, String>): ReplayResult {
        events.clear()
        notes.clear()
        lastTyped = null
        lastTypedValue = null
        addressHandled = false
        alreadyInCartNoted = false
        customisationHandled = false
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
        // A flow that ends by typing a search: submit it (the teacher pressed enter/search, which
        // isn't reported as a tap).
        val typedLast = lastTyped
        if (typedLast != null && steps.lastOrNull() is Step.TypeText) {
            host.current()?.let { s ->
                resolver.resolve(s, typedLast, slots, editableOnly = true)?.let { f ->
                    events += "pressed enter on the search field"
                    act(PlannedAction.ImeEnter(s.id, f.index), GateContext(explicitlyTaught = true, resolverConfidence = f.score), s)
                }
            }
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
            return result(statusFor(it.trip), it.trip.handOffMessage, steps.size - 1, steps)
        }
        if (final != null) {
            // Taught to end at checkout or payment: the cart can arrive a moment after the last tap.
            if (flow.endedAt in setOf("CHECKOUT", "PAYMENT") && !guard.classify(final).isCheckout) final = awaitCheckout(final)
            if (guard.classify(final).isCheckout) {
                adjustQuantity(final, flow, slots)?.let { return result(it.status, it.message, steps.size - 1, steps) }
                final = awaitCheckout(readable(host.current()) ?: final)
            }
            guard.handOffAtCheckout(final)?.let { return result(RunStatus.HANDED_OFF, it.handOffMessage, steps.size - 1, steps) }
            // Taught to end at checkout, but the app's cart button didn't report its tap while
            // teaching (custom views): open the cart ourselves. Navigating toward payment is allowed.
            if (flow.endedAt == "CHECKOUT") {
                openCart(final)?.let { opened ->
                    var cart = readable(opened) ?: opened
                    cart = awaitCheckout(cart)
                    if (guard.classify(cart).isCheckout) {
                        adjustQuantity(cart, flow, slots)?.let { return result(it.status, it.message, steps.size - 1, steps) }
                        cart = awaitCheckout(readable(host.current()) ?: cart)
                    }
                    guard.handOffAtCheckout(cart)?.let { return result(RunStatus.HANDED_OFF, it.handOffMessage, steps.size - 1, steps) }
                }
                (guard.currentState as? GuardState.Tripped)?.let { return result(statusFor(it.trip), it.trip.handOffMessage, steps.size - 1, steps) }
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
            val def = flow.slots.firstOrNull { it.name == slotName }
            val q = slotQuestion(slotName, def) + if (choices.isNotEmpty()) " I can see: ${choices.joinToString(", ")}." else ""
            val answer = host.ask(q, choices)?.trim()
            if (answer.isNullOrBlank()) return StepResult.Stop(RunStatus.NO_ANSWER, "I needed the ${slotWord(slotName, def)} and didn't get an answer.")
            slots[slotName] = if (slotName == "qty") (answer.toIntOrNull() ?: wordNumber(answer) ?: 1).toString() else slotAnswer(answer, def)
            events += "asked for $slotName: ${slots[slotName]}"
        }

        // Reset after dialogs and questions: time spent waiting for the user doesn't count.
        var started = host.nowMs()
        var scrolls = 0
        var triedIme = false
        var triedDismiss = 0
        var askedAboutValue = false
        var staleRetries = 0
        var blankSince: Long? = null
        var triedOpenSearch = false
        var closedEmptySheet = false
        var openedResults = 0
        var lastOpen: String? = null
        var triedRetry = false
        while (host.nowMs() - started < stepBudgetMs) {
            val snap = host.current() ?: return StepResult.Stop(RunStatus.HALTED, "I can't see the screen.")
            tripped()?.let { return it }
            val preview = guard.classify(snap)
            if (preview.kinds == setOf(SensitiveKind.OPAQUE_UNKNOWN) && !closedEmptySheet && EmptySheet.matches(snap)) {
                // An empty sheet left on screen (Zomato): Back closes it.
                closedEmptySheet = true
                events += "closed an empty sheet with back"
                act(PlannedAction.Back, GateContext(isRecovery = true), snap)
                host.awaitSettled(snap.id, 1_500)
                continue
            }
            if (preview.kinds == setOf(SensitiveKind.OPAQUE_UNKNOWN)) {
                // Blank frames during loading: give the app time to draw, counted from when the
                // screen went blank; only a screen that stays unreadable ends the run.
                val since = blankSince ?: host.nowMs().also { blankSince = it }
                if (host.nowMs() - since < OPAQUE_GRACE_MS) {
                    host.awaitSettled(snap.id, 1_000)
                    continue
                }
            } else {
                blankSince = null
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
            val dialog = decisionDialog(snap)
            if (dialog != null) {
                handleDecisionDialog(snap, dialog)?.let { return it }
                started = host.nowMs()
                continue
            }
            // An options sheet this item has but the taught one didn't (L3): ask, then continue.
            // When the taught step is on the sheet itself ("Add item", a size), just do that.
            val sheet = customisationSheet(snap)
            if (sheet != null && resolve(step, snap, slots)?.let { r -> snap.elements[r.index].windowId == sheet.windowId } != true) {
                handleCustomisation(snap, slots)?.let { return it }
                started = host.nowMs()
                continue
            }
            // "Select delivery address" sheet: pick the {address} slot, or last time's, or ask (T6).
            if (addressOptions(snap).isNotEmpty() && !addressHandled) {
                addressHandled = true
                handleAddressSheet(snap, flow, slots)?.let { return it }
                started = host.nowMs()
                continue
            }

            if (step is Step.Tap && step.pick == "add_to_cart") {
                val add = addToCartButton(snap)
                if (add != null) {
                    events += "tapped \"${add.label ?: "Add to cart"}\" (${add.viewId?.substringAfter(":id/") ?: add.className})"
                    val r = perform(step, snap, Resolution(add.index, Descriptors.clickableFor(snap, add.index), 0.9, 0.0), slots)
                    if (r is StepResult.Retry && staleRetries++ < 3) continue
                    return if (r is StepResult.Retry) StepResult.Stop(RunStatus.HALTED, "The screen kept changing, so I stopped at step ${i + 1}.") else r
                }
            }
            // Results, not search suggestions: product cards are taken at once; any other kind of row
            // only once no keyboard is showing (the search was sent).
            if (step is Step.Tap && step.pick == "first") {
                val keyboardUp = snap.windows.any { it.type == WindowType.INPUT_METHOD && it.bounds.height > 200 }
                val first = topResult(snap, slots["item"], allowNonProducts = !keyboardUp)
                if (first != null) {
                    events += "opened the first result: \"${first.label}\""
                    val r = perform(step, snap, Resolution(first.index, Descriptors.clickableFor(snap, first.index), 0.8, 0.0), slots)
                    if (r is StepResult.Retry && staleRetries++ < 3) continue
                    return if (r is StepResult.Retry) StepResult.Stop(RunStatus.HALTED, "The screen kept changing, so I stopped at step ${i + 1}.") else r
                }
            }

            val resolution = resolve(step, snap, slots)
            if (resolution != null) {
                val r = perform(step, snap, resolution, slots)
                // The screen changed between finding the element and tapping it: find it again.
                if (r is StepResult.Retry && staleRetries++ < 3) continue
                return if (r is StepResult.Retry) StepResult.Stop(RunStatus.HALTED, "The screen kept changing, so I stopped at step ${i + 1}.") else r
            }


            // T10: the step right after searching for a value can't be found, and the app says it
            // found nothing (or nothing matching shows after a few seconds): ask for another value
            // right away instead of hunting (specific, and well inside 30 s).
            val typedBefore = steps.getOrNull(i - 1) as? Step.TypeText
            val searched = typedBefore?.slot?.let { slots[it] }
            if (searched != null && !askedAboutValue && step is Step.Tap && step.pick == null && openedResults == 0) {
                val waited = host.nowMs() - started
                if (noResults(snap) || (step.slot == null && triedIme && waited > QUICK_ASK_MS && firstResult(snap, searched) == null)) {
                    askedAboutValue = true
                    val slotName = typedBefore.slot!!
                    val where = slots.entries.firstOrNull { it.key in SourceSlots.names && it.key != slotName }?.value?.let { " at $it" } ?: ""
                    events += "searched for \"$searched\"$where and found nothing"
                    val answer = host.ask("I searched for \"$searched\"$where but couldn't find it. What should I get instead?")?.trim()
                    val stopWords = setOf("no", "nothing", "stop", "cancel", "leave it", "never mind", "nevermind")
                    if (answer.isNullOrBlank() || TextNormalizer.normalize(answer) in stopWords) {
                        return StepResult.Stop(
                            if (answer.isNullOrBlank()) RunStatus.NO_ANSWER else RunStatus.HALTED,
                            "I searched for \"$searched\"$where but couldn't find it, so I stopped at step ${i + 1} without adding anything.",
                        )
                    }
                    slots[slotName] = slotAnswer(answer, flow.slots.firstOrNull { it.name == slotName })
                    events += "user asked for \"${slots[slotName]}\" instead of \"$searched\""
                    return StepResult.SkipTo(i - 1)
                }
            }

            // The app's own error page ("Something went wrong. Try again"): retry once.
            if (!triedRetry) {
                val retry = retryButton(snap)
                if (retry != null) {
                    triedRetry = true
                    events += "the app showed an error; tapped \"${snap.elements[retry].label}\""
                    act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, retry)), GateContext(isRecovery = true), snap)
                    host.awaitSettled(snap.id, 3_000)
                    started = host.nowMs()
                    continue
                }
            }

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

            // Typing step, but the field is hidden behind a "Search" button (its tap wasn't reported
            // while teaching): open search first. A taught tap on a search button that looks
            // different now (Zomato swaps it for a search bar once the menu scrolls) is the same.
            // (Only once no result for the text just typed is left to open: that comes first.)
            val searchTap = isSearchTap(step) &&
                (openedResults >= MAX_RESULT_OPENS || lastTypedValue?.let { firstResult(snap, it) } == null)
            if ((step is Step.TypeText || searchTap) && !triedOpenSearch) {
                val opener = searchOpener(snap)
                if (opener != null) {
                    triedOpenSearch = true
                    events += "opened search via \"${opener.label ?: opener.viewId}\""
                    val o = act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, opener.index)), GateContext(explicitlyTaught = true, resolverConfidence = 0.8), snap)
                    if (searchTap && o is ActionOutcome.Performed) return tripped() ?: StepResult.Done
                    continue
                }
            }
            // T7: the dish is already in the cart, so its row shows "− 1 +" where "ADD" was. Don't
            // add another; the cart step sets the quantity that was asked for.
            if (isAddTap(step) && !alreadyInCartNoted) {
                val item = slots["item"]
                if (item != null && cartRow(snap, item) != null) {
                    alreadyInCartNoted = true
                    lastTypedValue = null
                    events += "$item was already in the cart; didn't add another"
                    notes += "$item was already in your cart, so I didn't add another one."
                    // Tapping ADD would have closed the search keyboard, which hides the cart bar.
                    if (snap.windows.any { it.type == WindowType.INPUT_METHOD }) {
                        events += "closed the keyboard"
                        act(PlannedAction.Back, GateContext(isRecovery = true), snap)
                        host.awaitSettled(snap.id, 1_500)
                    }
                    return StepResult.Done
                }
            }

            // After a search, the taught next element isn't on the results list: open the first
            // result that matches what was just typed ({restaurant} or {item}): result taps that
            // weren't reported while teaching. Twice at most (Zomato: suggestion, then the card).
            val item = lastTypedValue ?: slots["item"]
            if (openedResults < MAX_RESULT_OPENS && item != null && step is Step.Tap && step.slot == null && step.pick == null && steps.take(i).any { it is Step.TypeText }) {
                val result = firstResult(snap, item)
                if (result != null) {
                    openedResults++
                    // Same screen, same result as the last open: the click was accepted and ignored
                    // (Zomato's result cards), so tap it for real this time.
                    val key = "${snap.activityName}|${result.label}|${result.bounds}"
                    val gesture = key == lastOpen
                    lastOpen = key
                    events += "opened the first result matching \"$item\"" + if (gesture) " (tapped)" else ""
                    act(PlannedAction.Click(snap.id, Descriptors.clickableFor(snap, result.index), gesture = gesture), GateContext(explicitlyTaught = true, resolverConfidence = 0.8), snap)
                    // Let the next screen arrive before looking again (a second tap on the same
                    // row while it loads opens nothing new).
                    host.awaitSettled(snap.id, 3_000)
                    continue
                }
            }

            // Lookahead, last resort before scrolling: a later step is on screen, so this one was
            // optional this time. Only after a short wait, because persistent elements (bottom
            // navigation tabs, a cart icon) are visible on every screen.
            if (host.nowMs() - started > LOOKAHEAD_AFTER_MS && (scrolls >= MAX_SCROLLS || scrollableList(snap) == null)) {
                lookahead(steps, i, snap, slots)?.let { return StepResult.SkipTo(it) }
            }

            // A slot value that isn't on screen: scroll a bit, then ask with what we see.
            // (Not for a search button: scrolling hides it in some apps.)
            // Add to Cart sits a few screens down a product page: allow more scrolling there.
            val addToCartStep = step is Step.Tap && step.pick == "add_to_cart"
            // (Never for typing: text fields sit at the top, and the list there is suggestions.)
            if (scrolls < (if (addToCartStep) MAX_PRODUCT_SCROLLS else MAX_SCROLLS) && !isSearchTap(step) && step !is Step.TypeText) {
                val list = scrollableList(snap)
                if (list != null) {
                    scrolls++
                    val o = act(PlannedAction.Scroll(snap.id, list.index, forward = true), GateContext(isRecovery = true), snap)
                    events += "scrolled ${list.simpleClassName}" + if (o is ActionOutcome.Performed) "" else " (${describe(o)})"
                    if (addToCartStep && o is ActionOutcome.Performed) {
                        host.awaitSettled(snap.id, 1_200)
                        started = host.nowMs()
                    }
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
        // The launch starts a fresh task (the app's home screen), so no backing out is needed;
        // wait for the app's first real screen (past splash / blank frames), then carry on.
        repeat(6) {
            val snap = readable(host.current()) ?: return StepResult.Done
            tripped()?.let { return it }
            if (snap.packageName == step.packageName && guard.classify(snap).kinds.isEmpty()) return StepResult.Done
            host.awaitSettled(snap.id, 1_500)
        }
        return StepResult.Done
    }

    private fun resolve(step: Step, snap: ScreenSnapshot, slots: Map<String, String>): Resolution? = when (step) {
        is Step.Tap -> (if (step.slot != null) {
            ElementResolver(SLOT_TAP_SCORE).resolve(snap, step.target, slots, requiredValue = slots[step.slot])
        } else {
            resolver.resolve(snap, step.target, slots)
        })?.takeIf { r -> step.pick == null && (!isAddTap(step) || saysAdd(snap, r)) } // picks use their own rules
        is Step.TypeText -> ElementResolver(FIELD_SCORE).resolve(snap, step.target, slots, editableOnly = true)
            // The learned field isn't here (another app, B2; or a redesigned screen), but exactly one
            // text box is on screen: that's where the text goes.
            ?: snap.appElements().filter { it.visible && it.editable && !it.password }.singleOrNull()
                ?.let { Resolution(it.index, it.index, FIELD_SCORE, 0.0) }
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
                    if (it is ActionOutcome.Performed) {
                        lastTyped = step.target
                        lastTypedValue = text
                    }
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
        // The "open the matching result" recovery is only for the step right after typing.
        if (outcome is ActionOutcome.Performed && step is Step.Tap) lastTypedValue = null
        return when (outcome) {
            is ActionOutcome.Performed -> tripped() ?: StepResult.Done
            is ActionOutcome.Blocked -> tripped()
                ?: if (outcome.decision.reason == com.echoflow.core.gateway.BlockReason.STALE_SNAPSHOT ||
                    (outcome.decision.reason == com.echoflow.core.gateway.BlockReason.SENSITIVE_SCREEN && outcome.decision.handOff == null)
                ) StepResult.Retry
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

    /**
     * A hand-off at the payment boundary (checkout, payment, OTP, password) is the normal end of a
     * purchase flow. A login or unreadable screen means the run couldn't do its job (T10), so
     * "did the last run succeed?" must answer no (T14).
     */
    private fun statusFor(trip: Trip): RunStatus =
        if (trip.kind == SensitiveKind.LOGIN || trip.kind == SensitiveKind.OPAQUE_UNKNOWN) RunStatus.HALTED else RunStatus.HANDED_OFF

    private fun tripped(): StepResult.Stop? =
        (guard.currentState as? GuardState.Tripped)?.let { StepResult.Stop(statusFor(it.trip), it.trip.handOffMessage) }

    /**
     * Saved-address rows of a "Select delivery address" sheet/screen: label (e.g. "Home") → row
     * element. Rows are clickable elements whose first short label is a saved-address name.
     */
    private fun addressOptions(snap: ScreenSnapshot): Map<String, UiElement> {
        val all = snap.appElements().filter { it.visible }
        // A real sheet heading: short, no digits ("Deliver to 560054" on every Amazon page isn't one).
        val heading = all.any { e ->
            e.label?.let { TextNormalizer.normalize(it) }?.let { l ->
                ADDRESS_HEADINGS.any { l.contains(it) } && l.split(' ').size <= 6 && l.none(Char::isDigit)
            } == true
        }
        if (!heading) return emptyMap()
        // Rows below a "recently searched" heading are past searches, not saved addresses.
        val recentTop = all.filter { TextNormalizer.normalize(it.label).startsWith("recent") }.minOfOrNull { it.bounds.top } ?: Int.MAX_VALUE
        val out = linkedMapOf<String, UiElement>()
        for (row in all.filter { it.clickable && it.bounds.top < recentTop }) {
            val labels = snap.descendants(row.index, maxDepth = 3).filter { it.visible }.sortedBy { it.index }
                .mapNotNull { it.label }
                // Skip icon descriptions and icon-font glyphs (no letters after normalising).
                .filter { l -> !l.contains("icon", ignoreCase = true) && TextNormalizer.tokens(l).isNotEmpty() && TextNormalizer.normalize(l) !in setOf("selected", "default") }
                .toList()
            // Distances ("0 m", "291 km") and phone numbers aren't the address's name.
            val named = labels.filterNot { l -> DISTANCE.matches(l.trim()) || l.contains("phone", ignoreCase = true) }
            if (named.size < 2) continue // a name plus the full address line
            val name = named.first().trim()
            val n = TextNormalizer.tokens(name)
            if (n.isEmpty() || n.size > 3 || NOT_ADDRESS.any { TextNormalizer.normalize(name).contains(it) }) continue
            // Offers and prices ("Buy for" / "₹1,734 with Axis Bank Credit Card") aren't addresses.
            if (named.take(2).any { SafetyLexicon.amountOf(TextNormalizer.tokens(it)) != null }) continue
            if (named.drop(1).none { it.length > 20 }) continue // must have a real address line
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
        }
            // Zomato: an unlabelled "location_container" at the top holding "Home" + the address.
            ?: snap.appElements().firstOrNull { e ->
                e.visible && e.clickable && e.bounds.top < snap.screenHeight / 5 &&
                    TextNormalizer.viewIdTokens(e.viewId).any { it == "location" || it == "address" }
            }
            ?: return null // no address bar on this app's start screen; nothing to do
        // Already there: the bar's own text names it ("Delivering to Work"), or its short name
        // child is exactly it (Zomato's "Home" / "Work" title).
        val alreadySet = ElementResolver.valueMatch(want, bar.label, emptyList()) > 0 ||
            snap.descendants(bar.index, maxDepth = 4).any { d -> TextNormalizer.normalize(d.label) == TextNormalizer.normalize(want) }
        if (alreadySet) {
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
                ?: options.entries.singleOrNull()?.also { events += "used the only saved address \"${it.key}\"" }
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
    /** The cart draws (and, after a quantity change, redraws) its pay button last: wait for it. */
    private suspend fun awaitCheckout(start: ScreenSnapshot): ScreenSnapshot {
        var cart = start
        val waitStart = host.nowMs()
        while (!guard.classify(cart).isCheckout && guard.classify(cart).kinds.isEmpty() && host.nowMs() - waitStart < 5_000) {
            cart = host.awaitSettled(cart.id, 1_000) ?: host.current() ?: cart
        }
        return cart
    }

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
        // Unlabelled icon buttons (Zomato's cart stepper) are recognised by their view ids.
        // (Their "text" is an icon-font glyph: no letters or digits.)
        fun idSays(e: UiElement, words: Set<String>) = e.label.orEmpty().none { it.isLetterOrDigit() } && e.clickable &&
            TextNormalizer.viewIdTokens(e.viewId).any { it in words }
        fun isPlus(e: UiElement) = e.label?.trim() == "+" || TextNormalizer.normalize(e.label).let { it.contains("add one more") || it.contains("increase") } ||
            idSays(e, setOf("add", "plus", "increment", "increase", "inc"))
        fun isMinus(e: UiElement) = e.label?.trim() in setOf("−", "-") || TextNormalizer.normalize(e.label).let { it.contains("remove one") || it.contains("decrease") } ||
            idSays(e, setOf("remove", "minus", "decrement", "decrease", "subtract", "dec"))
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
        if (customisationHandled) {
            // Still open after "Add Item": the dish has a required choice with no default.
            return StepResult.Stop(RunStatus.HALTED, "$item needs you to pick some options, like size or type. I've left them open for you to choose.")
        }
        customisationHandled = true
        // The teacher got past this sheet (its taps aren't always reported): keep the choices
        // that are already selected, like the size, and say so at the end. A required choice with
        // nothing selected keeps the sheet open and stops the run below.
        val price = SafetyLexicon.amountOf(TextNormalizer.tokens(button.label))
        events += "added $item with the preselected options"
        val now = host.current() ?: snap
        val fresh = customisationSheet(now) ?: return StepResult.Stop(RunStatus.HALTED, "The options sheet closed before I could add $item.")
        val o = act(PlannedAction.Click(now.id, Descriptors.clickableFor(now, fresh.index)), GateContext(explicitlyTaught = true, resolverConfidence = 1.0), now)
        if (o !is ActionOutcome.Performed) return tripped() ?: StepResult.Stop(RunStatus.HALTED, "I couldn't add $item.")
        // Give the sheet time to close; if it stays, a required choice is missing.
        var after = host.current() ?: return null
        val waitStart = host.nowMs()
        while (customisationSheet(after) != null && host.nowMs() - waitStart < 2_500) {
            after = host.awaitSettled(after.id, 800) ?: host.current() ?: return null
        }
        return if (customisationSheet(after) != null) {
            StepResult.Stop(RunStatus.HALTED, "$item needs you to pick some options, like size or type. I've left them open for you to choose.")
        } else {
            notes += "I added $item with the options that were already selected" + (price?.let { ", $it" } ?: "") + "."
            null
        }
    }

    /**
     * A taught "ADD" tap may only land on something that says add (the button, or a clickable
     * whose text says so): never on the dish name when the ADD button has become "− 1 +".
     */
    private fun saysAdd(snap: ScreenSnapshot, r: Resolution): Boolean {
        val labels = (listOf(snap.elements[r.index], snap.elements[r.actionIndex]) + snap.descendants(r.actionIndex, maxDepth = 2))
            .mapNotNull { it.label?.let(TextNormalizer::tokens) }
        return labels.any { "add" in it }
    }

    /** A taught "ADD" / "Add to cart" tap. */
    private fun isAddTap(step: Step): Boolean {
        if (step !is Step.Tap || step.pick != null) return false
        val label = TextNormalizer.normalize(step.target.text ?: step.target.contentDescription)
        return label == "add" || label == "add to cart" || label == "add item" || label == "add to bag"
    }

    /** A taught tap whose target is a search button or box (by its label, row text or view id). */
    private fun isSearchTap(step: Step): Boolean {
        if (step !is Step.Tap || step.slot != null || step.pick != null) return false
        val t = step.target
        val words = (listOfNotNull(t.text, t.contentDescription) + t.context).flatMap(TextNormalizer::tokens) +
            TextNormalizer.viewIdTokens(t.viewId)
        return "search" in words
    }

    /** A non-editable "Search" box/button (upper part of the screen) that opens the search field. */
    private fun searchOpener(snap: ScreenSnapshot): UiElement? = snap.appElements()
        .filter { it.visible && !it.editable && it.bounds.top < snap.screenHeight / 2 }
        .filter { e ->
            val l = TextNormalizer.normalize(e.label)
            l == "search" || l.startsWith("search for") || l.startsWith("search or") || l.contains("open search") ||
                (e.clickable && TextNormalizer.viewIdTokens(e.viewId).let { "search" in it && ("box" in it || "bar" in it || "edit" in it) })
        }
        .minByOrNull { if (it.clickable) 0 else 1 }

    /** The first (top-most) result whose own label contains [item], below the search field. */
    /**
     * An "Add to cart / bag / basket" button, by its label or its view id (Amazon's is labelled
     * "Submit", id add-to-cart-button). Never one whose label or id says buy now / pay / order.
     */
    private fun addToCartButton(snap: ScreenSnapshot): UiElement? = snap.appElements()
        .filter { it.visible && (it.clickable || it.className.contains("Button")) }
        .filter { e ->
            val label = TextNormalizer.normalize(e.label)
            val id = TextNormalizer.viewIdTokens(e.viewId)
            ADD_TO_CART_LABELS.any { label == it || label.startsWith("$it ") } ||
                ("add" in id && listOf("cart", "bag", "basket").any { it in id })
        }
        .filter { e -> risk.assess(snap, e).risk == com.echoflow.core.safety.ActionRisk.SAFE }
        .minByOrNull { it.bounds.top }

    /**
     * The first search result, by position. Product cards (a title with a price or a star rating
     * just below it) come first, which skips AI summaries and video ads above the list; among the
     * first few, one whose title names the item wins. Without product cards: the first clickable
     * row with a title-like label (3+ words).
     */
    private fun topResult(snap: ScreenSnapshot, item: String?, allowNonProducts: Boolean = true): UiElement? {
        val els = snap.appElements().filter { it.visible }
        // Amazon heads its list with "Results"; banners and AI summaries sit above it.
        val listTop = els.firstOrNull { TextNormalizer.normalize(it.label) in RESULTS_HEADINGS }?.bounds?.bottom ?: 0
        val rows = els
            .filter { !it.editable && it.bounds.top > snap.screenHeight / 8 && it.bounds.top >= listTop }
            .filter { e -> (e.label?.trim()?.split(Regex("\\s+"))?.size ?: 0) in 3..40 && (e.label?.length ?: 0) >= 15 }
            .filter { Descriptors.clickableFor(snap, it.index).let { c -> snap.elements[c].clickable } }
            .filter { e ->
                TextNormalizer.normalize(e.label).let { l ->
                    !l.startsWith("view sponsored") && !l.startsWith("sponsored ad from") && !l.startsWith("results for") && !l.startsWith("showing results") &&
                        !l.startsWith("ref ") && !l.contains("http")
                }
            }
            .sortedBy { it.bounds.top * 10 + it.bounds.left }
            .distinctBy { Descriptors.clickableFor(snap, it.index) }
        fun productLike(e: UiElement) = els.any { o ->
            o.bounds.top >= e.bounds.top && o.bounds.top <= e.bounds.bottom + PRODUCT_DETAIL_SPAN &&
                o.bounds.left < e.bounds.right && o.bounds.right > e.bounds.left &&
                TextNormalizer.tokens(o.label).let { t -> t.size in 1..6 && (TextNormalizer.containsPhrase(t, OUT_OF_5) || SafetyLexicon.amountOf(t) != null) }
        }
        val products = rows.filter(::productLike)
        val pool = products.ifEmpty { if (allowNonProducts) rows else emptyList() }
        if (item != null) pool.take(4).firstOrNull { ElementResolver.valueMatch(item, it.label, emptyList()) > 0 }?.let { return it }
        return pool.firstOrNull()
    }

    private fun firstResult(snap: ScreenSnapshot, item: String): UiElement? = snap.appElements()
        .filter { it.visible && !it.editable && it.bounds.top > snap.screenHeight / 8 && (it.label?.length ?: 0) >= item.length }
        .filter { ElementResolver.valueMatch(item, it.label, emptyList()) >= 0.8 }
        .filter { Descriptors.clickableFor(snap, it.index).let { c -> snap.elements[c].clickable } }
        .minByOrNull { it.bounds.top * 10 + it.bounds.left }

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

    /** The app says a search found nothing. */
    private fun noResults(snap: ScreenSnapshot): Boolean = snap.appElements().any { e ->
        e.visible && TextNormalizer.normalize(e.label).let { l -> l.isNotEmpty() && l.split(' ').size <= 14 && NO_RESULTS.any { l.contains(it) } }
    }

    /** "Try again" / "Retry" (exact labels only, never "Retry payment") on an error page. */
    private fun retryButton(snap: ScreenSnapshot): Int? {
        val labels = snap.appElements().filter { it.visible }.mapNotNull { it.label?.let(TextNormalizer::normalize) }
        if (labels.none { l -> ERROR_TEXTS.any { l.contains(it) } }) return null
        return snap.appElements().firstOrNull { e ->
            e.visible && e.label != null && (e.clickable || e.className.contains("Button")) &&
                TextNormalizer.normalize(e.label) in RETRY_LABELS
        }?.index
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
            // Web pages (Amazon's product page) often don't flag themselves scrollable.
            ?: snap.appElements().filter { e -> e.visible && SCROLL_CLASSES.any { e.className.endsWith(it) } && e.bounds.area * 3 > snap.screenArea }
                .maxByOrNull { it.bounds.area }

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
        message = (notes + message).joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) },
        stoppedAtStep = i + 1,
        totalSteps = steps.size,
        stepDescription = steps.getOrNull(i)?.description,
        events = events.toList(),
    )

    companion object {
        const val STEP_BUDGET_MS = 12_000L
        const val MAX_RESULT_OPENS = 3
        private val DISTANCE = Regex("^\\d+(\\.\\d+)?\\s*(m|km|mi)$", RegexOption.IGNORE_CASE)
        /** How long after pressing enter to wait for a searched value before asking (T10: < 30 s). */
        const val QUICK_ASK_MS = 4_000L
        private val NO_RESULTS = listOf(
            "no results", "no result found", "no matching", "nothing found", "no items found", "no dishes",
            "couldn t find", "couldnt find", "could not find", "didn t find", "did not match", "no match", "0 results",
            "no products", "we couldn t", "sorry we",
        )
        const val MAX_PRODUCT_SCROLLS = 8
        private val ADD_TO_CART_LABELS = listOf("add to cart", "add to bag", "add to basket", "add to trolley")
        /** How far below a result title its price/rating may sit (px) to count as a product card. */
        const val PRODUCT_DETAIL_SPAN = 450
        private val OUT_OF_5 = listOf("out", "of", "5")
        private val RESULTS_HEADINGS = setOf("results", "search results", "all results")
        private val SCROLL_CLASSES = listOf("WebView", "RecyclerView", "ScrollView", "ListView")
        private val ERROR_TEXTS = listOf(
            "something went wrong", "went wrong", "couldn t load", "couldnt load", "could not load", "unable to load",
            "no internet", "not connected", "oops", "failed to load", "please try again",
        )
        private val RETRY_LABELS = setOf("try again", "retry", "reload", "refresh", "tap to retry")
        const val SLOT_TAP_SCORE = 0.5
        const val FIELD_SCORE = 0.45
        const val LOOKAHEAD_SCORE = 0.85
        const val MAX_SCROLLS = 3
        const val OPAQUE_GRACE_MS = 6_000L
        const val LOOKAHEAD_AFTER_MS = 2_500L
        private val ADDRESS_HEADINGS = listOf("select a saved address", "select delivery address","select a delivery address", "choose a delivery address",
            "choose delivery address", "select address", "saved addresses", "deliver to", "choose address", "select delivery location")
        private val ADDRESS_BARS = listOf("selected address is", "delivering to", "deliver to", "delivery address")
        private val NOT_ADDRESS = listOf(
            "enter location", "add address", "add new", "use current location", "grant", "search",
            "buy", "offer", "pay", "save", "bank", "card", "emi", "coupon", "deal",
        )
        private val CUSTOMISE_WORDS = listOf(
            "customization", "customisation", "customize", "customise", "choose your", "add ons", "addons",
            "choose from variant", "select any", "select up to", "choose any", "required",
        )
        private val ADD_ITEM_WORDS = listOf("add item", "add to cart", "add to bag")
        private val CART_WORDS =listOf("view cart", "checkout", "go to cart", "view bag", "go to bag", "proceed to cart")
        private val POPUP_CLASSES = listOf("Dialog", "BottomSheet", "PopupWindow")
        private val DISMISS = listOf("close", "not now", "no thanks", "skip", "later", "maybe later", "dismiss", "got it", "ok", "okay", "x", "×", "✕", "cancel")
        private val DISMISS_DESC = listOf("close", "dismiss", "cross", "close button", "navigate up")

        fun isYes(answer: String): Boolean {
            val t = TextNormalizer.tokens(answer)
            if (t.any { it in setOf("no", "not", "dont", "nope", "nahi", "cancel", "stop") }) return false
            return t.any { it in setOf("yes", "yeah", "yep", "sure", "ok", "okay", "replace", "haan", "ha", "correct", "right", "go", "confirm", "do") }
        }

        fun wordNumber(answer: String): Int? = TextNormalizer.tokens(answer).firstNotNullOfOrNull {
            it.toIntOrNull() ?: com.echoflow.core.nlu.Utterances.numberWords[it]
        }

        /** "pizza" for an item taught as "margherita pizza", "restaurant", "quantity"… */
        fun slotWord(name: String, def: SlotDef?): String = when (name) {
            "item" -> def?.qualifiers?.lastOrNull() ?: "item"
            "qty" -> "quantity"
            else -> name
        }

        /** B3: "Which restaurant should I order from? Last time it was dominos." */
        fun slotQuestion(name: String, def: SlotDef?): String {
            val last = def?.taughtValue?.takeIf { it.isNotBlank() && name != "qty" }?.let { " Last time it was $it." } ?: ""
            return when (name) {
                "restaurant" -> "Which restaurant should I order from?$last"
                "store" -> "Which store or seller should I use?$last"
                "address" -> "Which address should I deliver to?$last"
                "qty" -> "How many should I order?"
                else -> "Which ${slotWord(name, def)} do you want?$last"
            }
        }

        /** "from Domino's" -> "dominos"; "same as last time" -> the taught value. */
        fun slotAnswer(answer: String, def: SlotDef?): String {
            var words = TextNormalizer.tokens(answer)
            if (def != null && (words.isEmpty() || words.joinToString(" ") in SAME_ANSWERS)) return def.taughtValue
            while (words.size > 1 && words.first() in setOf("from", "the", "a", "an", "at", "to", "order", "get", "some", "i", "want")) words = words.drop(1)
            val q = def?.qualifiers.orEmpty().map(IntentMatcher::singular).toSet()
            val kept = words.filter { IntentMatcher.singular(it) !in q }
            return (kept.ifEmpty { words }).joinToString(" ")
        }

        private val SAME_ANSWERS = setOf(
            "same", "same one", "the same", "same as last time", "same as before", "last one", "the last one",
            "yes", "yeah", "usual", "the usual", "same place", "same restaurant", "yes same",
        )
    }
}
