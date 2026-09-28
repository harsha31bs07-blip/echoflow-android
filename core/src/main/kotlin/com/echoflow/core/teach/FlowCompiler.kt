package com.echoflow.core.teach

import com.echoflow.core.flow.ElementDescriptor
import com.echoflow.core.flow.ElementResolver
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.SlotType
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

        val slots = extractSlots(teachingUtterance, kept, llmSlots)
        val slotValues = slots.associate { it.name to it.taughtValue }

        // 1d. Detours: the teacher went somewhere and came back to the same screen.
        kept = removeDetours(kept, slotValues, dropped)
        // (No "changed nothing" filter: the coarse fingerprint can't see tab switches or list
        // updates, and on device it dropped a needed "Dishes" tab tap. Extra steps are cheaper.)

        val steps = buildSteps(appPackage, appLabel, kept, slotValues)
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

    private fun extractSlots(utterance: String, actions: List<RawAction>, llm: Map<String, String>): List<SlotDef> {
        val parsed = Utterances.parse(Utterances.stripTeachPrefix(utterance))
        val typed = actions.filter { it.kind == RawKind.TYPE }.mapNotNull { it.typed }
        val slots = mutableListOf<SlotDef>()
        val item = llm["item"]
            ?: typed.firstOrNull { t -> parsed.item != null && (sameValue(t, parsed.item) || contains(parsed.item, t) || contains(t, parsed.item)) }
                ?.let { t -> if (contains(parsed.item!!, t)) t else parsed.item }
            ?: parsed.item
        item?.let { slots += SlotDef("item", SlotType.TEXT, TextNormalizer.normalize(it)) }
        (llm["qty"]?.toIntOrNull() ?: parsed.quantity)?.let { slots += SlotDef("qty", SlotType.NUMBER, it.toString()) }
        (llm["address"] ?: parsed.address)?.let { slots += SlotDef("address", SlotType.TEXT, it) }
        return slots
    }

    private fun template(utterance: String, slots: List<SlotDef>): String {
        val parsed = Utterances.parse(Utterances.stripTeachPrefix(utterance))
        var t = parsed.tokens.joinToString(" ")
        slots.firstOrNull { it.name == "address" }?.let { t = replaceIgnoringCase(t, it.taughtValue, "{address}") }
        slots.firstOrNull { it.name == "item" }?.let { t = replaceIgnoringCase(t, it.taughtValue, "{item}") }
        parsed.quantityToken?.let { q -> t = t.split(' ').joinToString(" ") { if (it == q) "{qty}" else it } }
        return t
    }

    companion object {
        const val DEBOUNCE_MS = 350L
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
