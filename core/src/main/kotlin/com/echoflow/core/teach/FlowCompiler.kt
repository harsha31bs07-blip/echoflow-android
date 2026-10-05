package com.echoflow.core.teach

import com.echoflow.core.flow.ElementDescriptor
import com.echoflow.core.flow.ElementResolver
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.SlotType
import com.echoflow.core.flow.SourceSlots
import com.echoflow.core.flow.Step
import com.echoflow.core.nlu.Utterances
import com.echoflow.core.text.TextNormalizer

data class CompileResult(
    val flow: Flow,
    val dropped: List<String>,
)

/**
 * Turns a recorded demonstration into a parameterised [Flow]:
 *  1. noise removal (bonus B1): other apps, duplicate taps, no-op taps, detours;
 *  2. slot binding from the teaching utterance ("order 2 garlic bread to work");
 *  3. step building, with steppers collapsed into [Step.RepeatTap].
 */
class FlowCompiler {

    fun compile(
        id: String,
        teachingUtterance: String,
        actions: List<RawAction>,
        appLabel: String? = null,
        endedAt: String = "user",
        nowMs: Long = 0,
        /** Slot values found by an LLM, if one was available; overrides local parsing. */
        llmSlots: Map<String, String> = emptyMap(),
    ): CompileResult {
        val dropped = mutableListOf<String>()
        val appPackage = actions.groupingBy { it.packageName }.eachCount().maxByOrNull { it.value }?.key
            ?: error("nothing was recorded")

        // 1a. Other apps (launcher icon taps, system dialogs): LaunchApp covers the start.
        var kept = actions.filter { a ->
            (a.packageName == appPackage).also { if (!it) dropped += "tap in ${a.packageName ?: "another app"}" }
        }
        // 1b. Several text changes into one field -> the final text.
        kept = mergeTyping(kept)
        // 1c. Double taps.
        kept = kept.filterIndexed { i, a ->
            val prev = kept.getOrNull(i - 1)
            val dup = prev != null && a.kind == RawKind.TAP && prev.kind == RawKind.TAP &&
                a.target == prev.target && a.atMs - prev.atMs < DEBOUNCE_MS && !isStepper(a)
            if (dup) dropped += "double tap on \"${a.target.display}\""
            !dup
        }

        val slots = extractSlots(teachingUtterance, kept, llmSlots, appPackage)
        val slotValues = slots.associate { it.name to it.taughtValue }

        // 1d. Detours: the teacher went somewhere and came back to the same screen.
        kept = removeDetours(kept, slotValues, dropped)
        // (No "changed nothing" filter: the coarse fingerprint can't see tab switches or list
        // updates, and on device it dropped a needed "Dishes" tab tap. Extra steps are cheaper.)

        val steps = addToCartFromCommand(teachingUtterance, markFirstResult(teachingUtterance, buildSteps(appPackage, appLabel, kept, slotValues)))
        val template = template(teachingUtterance, slots)
        val flow = Flow(
            id = id,
            name = template,
            appPackage = appPackage,
            appLabel = appLabel,
            template = template,
            examples = listOf(Utterances.stripTeachPrefix(teachingUtterance)),
            slots = slots,
            steps = steps,
            endedAt = endedAt,
            recordedActions = actions.size,
            createdAtMs = nowMs,
        )
        return CompileResult(flow, dropped)
    }

    /**
     * "…and add the first result to cart", but the app never reported the Add to Cart tap (Amazon's
     * product page): the command says it, so add the step; replay finds the button by meaning.
     */
    private fun addToCartFromCommand(utterance: String, steps: List<Step>): List<Step> {
        val t = TextNormalizer.tokens(utterance)
        val says = "add" in t && CART_WORDS.any { w -> t.indexOf(w) > t.indexOf("add") }
        if (!says) return steps
        val recorded = steps.any { s ->
            s is Step.Tap && (s.pick == "add_to_cart" ||
                TextNormalizer.normalize(s.target.text ?: s.target.contentDescription).let { l -> CART_WORDS.any { l == "add to $it" } } ||
                TextNormalizer.viewIdTokens(s.target.viewId).let { "add" in it && CART_WORDS.any { w -> w in it } })
        }
        if (recorded) return steps
        return steps + Step.Tap(com.echoflow.core.flow.ElementDescriptor(text = "Add to cart", className = "android.widget.Button"), pick = "add_to_cart")
    }

    /** "…and add the first result to cart": the tap right after the search is positional. */
    private fun markFirstResult(utterance: String, steps: List<Step>): List<Step> {
        val t = TextNormalizer.tokens(utterance)
        val first = FIRST_PHRASES.any { TextNormalizer.containsPhrase(t, TextNormalizer.tokens(it)) }
        if (!first) return steps
        val typed = steps.indexOfLast { it is Step.TypeText }
        if (typed < 0) return steps
        val k = (typed + 1 until steps.size).firstOrNull { steps[it] is Step.Tap }
        if (k == null) {
            // The app never reported the result tap (Amazon): the command says it, so add it.
            val slot = (steps[typed] as Step.TypeText).slot
            val tap = Step.Tap(com.echoflow.core.flow.ElementDescriptor(text = slot?.let { "{$it}" }), slot = slot, pick = "first")
            return steps.toMutableList().also { it.add(typed + 1, tap) }
        }
        return steps.toMutableList().also { it[k] = (steps[k] as Step.Tap).copy(pick = "first") }
    }

    private fun mergeTyping(actions: List<RawAction>): List<RawAction> {
        val out = mutableListOf<RawAction>()
        for (a in actions) {
            val last = out.lastOrNull()
            if (a.kind == RawKind.TYPE && last?.kind == RawKind.TYPE && sameField(last.target, a.target)) {
                out[out.lastIndex] = a.copy(preFingerprint = last.preFingerprint)
            } else {
                out += a
            }
        }
        return out.filter { it.kind != RawKind.TYPE || !it.typed.isNullOrBlank() }
    }

    private fun sameField(a: ElementDescriptor, b: ElementDescriptor) =
        a.viewId == b.viewId && a.className == b.className && a.contentDescription == b.contentDescription

    private fun removeDetours(actions: List<RawAction>, slots: Map<String, String>, dropped: MutableList<String>): List<RawAction> {
        val result = actions.toMutableList()
        var k = 1
        while (k < result.size) {
            val start = (0 until k).firstOrNull { i -> result[i].preFingerprint == result[k].preFingerprint }
            if (start != null && result[k].kind == RawKind.TAP) {
                val span = result.subList(start, k)
                val meaningful = span.any { it.kind == RawKind.TYPE || isStepper(it) || mentions(it, slots) != null }
                if (!meaningful && span.isNotEmpty()) {
                    span.forEach { dropped += "detour via \"${it.target.display}\"" }
                    span.clear()
                    k = start + 1
                    continue
                }
            }
            k++
        }
        return result
    }

    private fun buildSteps(appPackage: String, appLabel: String?, actions: List<RawAction>, slots: Map<String, String>): List<Step> {
        val steps = mutableListOf<Step>(Step.LaunchApp(appPackage, appLabel))
        val qty = slots["qty"]?.toIntOrNull()
        var i = 0
        while (i < actions.size) {
            val a = actions[i]
            when (a.kind) {
                RawKind.TYPE -> {
                    val typed = a.typed.orEmpty()
                    val slot = slots.entries.firstOrNull { (_, v) -> sameValue(typed, v) }?.key
                    steps += Step.TypeText(a.target, literal = if (slot == null) typed else null, slot = slot, activity = a.activity)
                    i++
                }
                RawKind.TAP -> {
                    var run = 1
                    while (i + run < actions.size && actions[i + run].kind == RawKind.TAP && actions[i + run].target == a.target) run++
                    if (isStepper(a) && qty != null) {
                        steps += Step.RepeatTap(a.target, "qty", offset = qty - run, activity = a.activity)
                    } else {
                        val slot = mentions(a, slots)
                        val target = if (slot != null) templated(a.target, slot, slots.getValue(slot)) else a.target
                        repeat(run) { steps += Step.Tap(target, slot, a.activity) }
                    }
                    i += run
                }
            }
        }
        return steps
    }

    /** Which slot's value appears on the tapped element or its row, if any (not qty). */
    private fun mentions(a: RawAction, slots: Map<String, String>): String? =
        slots.entries.filter { it.key != "qty" }
            .firstOrNull { (_, v) -> ElementResolver.valueMatch(v, a.label ?: a.target.contentDescription, a.target.context) > 0.0 }
            ?.key

    private fun templated(d: ElementDescriptor, slot: String, value: String): ElementDescriptor {
        fun t(s: String?) = s?.let {
            if (sameValue(it, value)) return@let "{$slot}"
            val r = replaceIgnoringCase(it, value, "{$slot}")
            // "Garlic Breadsticks" with "garlic bread" would give "{item}sticks": use the whole label.
            if (Regex("\\{$slot\\}\\p{L}").containsMatchIn(r) || (r == it && ElementResolver.valueMatch(value, it, emptyList()) > 0)) "{$slot}" else r
        }
        return d.copy(text = t(d.text), contentDescription = t(d.contentDescription), context = d.context.map { t(it)!! })
    }

    private fun extractSlots(utterance: String, actions: List<RawAction>, llm: Map<String, String>, appPackage: String): List<SlotDef> {
        val parsed = Utterances.parse(Utterances.stripTeachPrefix(utterance))
        val typed = actions.filter { it.kind == RawKind.TYPE }.mapNotNull { it.typed }
        val slots = mutableListOf<SlotDef>()
        // "from Domino's": its own slot, so the restaurant search isn't mistaken for the item.
        val sourceName = SourceSlots.nameFor(appPackage)
        val source = (llm[sourceName] ?: parsed.source)?.let { spoken -> typedPart(spoken, typed) ?: spoken }
        val itemTyped = typed.filter { t -> source == null || !sameValue(t, source) }
        val spokenItemMatch = if (llm["item"] == null && parsed.item != null) {
            closeTypedPart(parsed.item, itemTyped, allowFusedArticle = true)
        } else null
        val item = llm["item"]
            ?: itemTyped
                .firstOrNull { t -> parsed.item != null && (sameValue(t, parsed.item) || contains(parsed.item, t) || contains(t, parsed.item)) }
                ?.let { t -> if (contains(parsed.item!!, t)) t else parsed.item }
            ?: spokenItemMatch?.typed
            ?: parsed.item
        item?.let { v ->
            val norm = TextNormalizer.normalize(v)
            val spoken = parsed.item
            val (value, qualifiers) =
                if (spoken != null && norm != TextNormalizer.normalize(spoken) && contains(spoken, norm)) {
                    // Typed "margherita" for spoken "margherita pizza": "pizza" is a qualifier.
                    norm to adjacentWords(parsed.tokens, TextNormalizer.tokens(norm), TextNormalizer.tokens(spoken))
                } else if (spokenItemMatch != null && sameValue(norm, spokenItemMatch.typed)) {
                    // The native field supplies the spelling; retain adjacent spoken qualifiers.
                    norm to adjacentWords(parsed.tokens, spokenItemMatch.words, TextNormalizer.tokens(spoken))
                } else {
                    refineItem(norm, actions, source)
                }
            slots += SlotDef("item", SlotType.TEXT, value, qualifiers)
        }
        source?.let { slots += SlotDef(sourceName, SlotType.TEXT, TextNormalizer.normalize(it)) }
        (llm["qty"]?.toIntOrNull() ?: parsed.quantity)?.let { slots += SlotDef("qty", SlotType.NUMBER, it.toString()) }
        (llm["address"] ?: parsed.address)?.let { slots += SlotDef("address", SlotType.TEXT, it) }
        return slots
    }

    /** What was typed for a spoken value, when it's the same words or some of them ("domino" for "dominos pizza"). */
    private fun typedPart(spoken: String, typed: List<String>): String? {
        val words = TextNormalizer.tokens(spoken)
        return typed.firstOrNull { t -> sameValue(t, spoken) || TextNormalizer.tokens(t).let { tw -> tw.isNotEmpty() && words.containsAll(tw) } }
            ?: closeTypedPart(spoken, typed)?.typed
    }

    private data class TypedPart(val typed: String, val words: List<String>)

    /**
     * Ground a small ASR spelling difference in the text the teacher actually entered.
     * Use the replay matcher's per-word edit bounds, and decline ambiguous recorded values.
     * The leading value must match, so a generic trailing qualifier cannot supply the item.
     */
    private fun closeTypedPart(spoken: String, typed: List<String>, allowFusedArticle: Boolean = false): TypedPart? {
        val words = TextNormalizer.tokens(spoken)
        val matches = typed.distinctBy(TextNormalizer::normalize).mapNotNull { value ->
            val native = TextNormalizer.tokens(value)
            if (native.isEmpty() || native.size > words.size) return@mapNotNull null
            val said = words.take(native.size)
            val close = said.zip(native).mapIndexed { i, (a, b) ->
                nearWord(a, b) || (allowFusedArticle && i == 0 && a.length >= 8 && b.length >= 8 &&
                    listOf("a", "an", "the").any { article ->
                        a.startsWith(article) && nearWord(a.removePrefix(article), b)
                    })
            }.all { it }
            if (close) TypedPart(value, said) else null
        }
        return matches.singleOrNull()
    }

    private fun nearWord(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 4) return false
        val limit = if (b.length >= 8) 2 else 1
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length] <= limit
    }

    /**
     * Spoken item words right next to the typed ones ("pizza" after "margherita"), at most two.
     * Words elsewhere in the command ("… on amazon and add the first result") don't count.
     */
    private fun adjacentWords(tokens: List<String>, value: List<String>, spokenItem: List<String>): List<String> {
        if (value.isEmpty()) return emptyList()
        val start = (0..tokens.size - value.size).firstOrNull { i -> value.indices.all { tokens[i + it] == value[it] } } ?: return emptyList()
        val end = start + value.size
        val out = mutableListOf<String>()
        var k = end
        while (k < tokens.size && out.size < 2 && tokens[k] in spokenItem && tokens[k] !in Utterances.fillers) out += tokens[k++]
        k = start - 1
        while (k >= 0 && out.size < 2 && tokens[k] in spokenItem && tokens[k] !in Utterances.fillers) out.add(0, tokens[k--])
        return out
    }

    /**
     * "margherita pizza" when the menu only says "Margherita": keep the words the screen showed
     * (they must include the first, most specific word) and remember the rest as qualifiers.
     */
    private fun refineItem(value: String, actions: List<RawAction>, source: String?): Pair<String, List<String>> {
        val words = TextNormalizer.tokens(value)
        if (words.size < 2) return value to emptyList()
        fun covered(a: RawAction): List<String> = words.filter { w ->
            when (a.kind) {
                RawKind.TYPE -> ElementResolver.valueMatch(w, a.typed, emptyList()) > 0
                else -> ElementResolver.valueMatch(w, a.label ?: a.target.contentDescription, a.target.context) > 0
            }
        }
        val relevant = actions.filter { a ->
            source == null || when (a.kind) {
                RawKind.TYPE -> !sameValue(a.typed.orEmpty(), source)
                else -> ElementResolver.valueMatch(source, a.label ?: a.target.contentDescription, a.target.context) == 0.0
            }
        }
        val coverage = relevant.map(::covered)
        if (coverage.any { it.size == words.size }) return value to emptyList()
        val best = coverage.filter { words.first() in it }.maxByOrNull { it.size } ?: return value to emptyList()
        return best.joinToString(" ") to words.filter { it !in best }
    }

    private fun template(utterance: String, slots: List<SlotDef>): String {
        val parsed = Utterances.parse(Utterances.stripTeachPrefix(utterance))
        var t = parsed.tokens.joinToString(" ")
        slots.firstOrNull { it.name == "address" }?.let { t = replaceIgnoringCase(t, it.taughtValue, "{address}") }
        // The whole spoken source phrase, even if only part of it was typed.
        slots.firstOrNull { it.name in SourceSlots.names }?.let { s ->
            t = replaceIgnoringCase(t, parsed.source?.takeIf { p ->
                contains(p, s.taughtValue) || closeTypedPart(p, listOf(s.taughtValue)) != null
            } ?: s.taughtValue, "{${s.name}}")
        }
        slots.firstOrNull { it.name == "item" }?.let { s ->
            val spoken = parsed.item?.let { closeTypedPart(it, listOf(s.taughtValue), allowFusedArticle = true) }
            t = replaceIgnoringCase(t, spoken?.words?.joinToString(" ") ?: s.taughtValue, "{item}")
        }
        parsed.quantityToken?.let { q -> t = t.split(' ').joinToString(" ") { if (it == q) "{qty}" else it } }
        return t
    }

    companion object {
        const val DEBOUNCE_MS = 350L
        private val CART_WORDS = listOf("cart", "bag", "basket", "trolley")
        private val FIRST_PHRASES = listOf("first result", "first one", "first item", "first product", "top result", "first option", "first search result")
        private val stepperWords = listOf("add one more", "increase", "increment", "add more", "plus")

        fun isStepper(a: RawAction): Boolean {
            val label = TextNormalizer.normalize(a.label ?: a.target.contentDescription ?: a.target.text)
            val raw = (a.label ?: a.target.contentDescription ?: a.target.text ?: "").trim()
            return raw == "+" || stepperWords.any { label.contains(it) }
        }

        fun sameValue(a: String, b: String) = TextNormalizer.normalize(a) == TextNormalizer.normalize(b)
        private fun contains(haystack: String, needle: String) =
            TextNormalizer.normalize(haystack).contains(TextNormalizer.normalize(needle))

        fun replaceIgnoringCase(s: String, value: String, with: String): String {
            if (value.isBlank()) return s
            val idx = s.lowercase().indexOf(value.lowercase())
            return if (idx < 0) s else s.substring(0, idx) + with + s.substring(idx + value.length)
        }
    }
}
