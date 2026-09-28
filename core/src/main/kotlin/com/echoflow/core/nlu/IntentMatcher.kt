package com.echoflow.core.nlu

import com.echoflow.core.flow.Flow
import com.echoflow.core.text.TextNormalizer

sealed interface MetaIntent {
    data class Teach(val utterance: String) : MetaIntent
    data object Done : MetaIntent
    data object Stop : MetaIntent
    data object Continue : MetaIntent
    data object Report : MetaIntent
    data object ListFlows : MetaIntent
}

data class Candidate(
    val flow: Flow,
    val score: Double,
    val slots: Map<String, String>,
    /** "exact", "template", "similar" or "llm". */
    val source: String,
) {
    val missing: List<String> get() = flow.slots.map { it.name }.filter { slots[it].isNullOrBlank() }
}

/** An LLM's reading of an utterance, if one was available (see the app's LlmClient). */
data class LlmIntent(val flowId: String?, val confidence: Double, val slots: Map<String, String>)

/**
 * Matching cascade (after SkillDroid): meta-intents → exact example → template regex →
 * local similarity (capped, so it always confirms) → optional LLM merge.
 */
class IntentMatcher {

    fun meta(utterance: String): MetaIntent? {
        val t = TextNormalizer.normalize(utterance)
        val tokens = t.split(' ')
        return when {
            tokens.firstOrNull() in setOf("teach", "learn") || t.startsWith("remember how") || t.startsWith("new flow") ->
                MetaIntent.Teach(utterance)
            t in setOf("done", "finish", "finished", "save", "save it", "that s it", "thats it", "that is it", "stop teaching", "i am done", "i m done") ->
                MetaIntent.Done
            t in setOf("stop", "cancel", "abort", "halt", "stop it", "stop now", "wait stop") -> MetaIntent.Stop
            t in setOf("continue", "resume", "go on", "carry on", "keep going") -> MetaIntent.Continue
            REPORT.any { t.contains(it) } -> MetaIntent.Report
            LIST.any { t.contains(it) } -> MetaIntent.ListFlows
            else -> null
        }
    }

    fun match(utterance: String, flows: List<Flow>, llm: LlmIntent? = null): List<Candidate> {
        val norm = TextNormalizer.normalize(utterance)
        val local = flows.map { f -> localScore(norm, f) }
        val merged = if (llm == null) local else local.map { c ->
            if (llm.flowId == c.flow.id) {
                c.copy(score = maxOf(c.score, llm.confidence), slots = c.slots + llm.slots.filterValues { it.isNotBlank() }, source = if (llm.confidence > c.score) "llm" else c.source)
            } else if (llm.flowId == null && llm.confidence >= 0.7 && c.source == "similar") {
                c.copy(score = minOf(c.score, 1 - llm.confidence))
            } else c
        }
        return merged.sortedByDescending { it.score }
    }

    private fun localScore(norm: String, flow: Flow): Candidate {
        // 1. Exact example (T2): the taught slot values apply.
        if (flow.examples.any { TextNormalizer.normalize(it) == norm }) {
            return Candidate(flow, 1.0, flow.slots.associate { it.name to it.taughtValue }, "exact")
        }
        // 2. Template regex: same shape, different values (T4–T6).
        templateRegex(flow.template)?.let { (regex, names) ->
            regex.matchEntire(norm)?.let { m ->
                val slots = names.mapIndexed { i, n -> n to normaliseSlot(n, m.groupValues[i + 1]) }.toMap()
                if (slots.values.none { it.isBlank() }) return Candidate(flow, 0.95, slots, "template")
            }
        }
        // 3. Similarity: intent verbs, app mention, and whether the slots are there. Capped at 0.75.
        val parsed = Utterances.parse(norm)
        val templateTokens = TextNormalizer.tokens(flow.template.replace(Regex("\\{\\w+\\}"), " "))
        val flowVerbs = templateTokens.mapNotNull(::verbGroup).toSet()
        val saidVerbs = parsed.tokens.mapNotNull(::verbGroup).toSet()
        var score = 0.0
        if (flowVerbs.isNotEmpty() && flowVerbs.intersect(saidVerbs).isNotEmpty()) score += 0.35
        val appPkg = parsed.appMention?.let { Utterances.appNames[it] }
        when {
            appPkg == null -> score += 0.1
            appPkg == flow.appPackage -> score += 0.3
            else -> score -= 0.4
        }
        val slots = mutableMapOf<String, String>()
        flow.slots.forEach { s ->
            when (s.name) {
                "item" -> parsed.item?.let { slots["item"] = it }
                "qty" -> parsed.quantity?.let { slots["qty"] = it.toString() }
                "address" -> parsed.address?.let { slots["address"] = it }
            }
        }
        if (flow.slots.any { it.name == "item" } && slots["item"] != null) score += 0.2
        // Shared non-slot words with any example (weak evidence).
        val exampleWords = flow.examples.flatMap(TextNormalizer::tokens).filter { it !in Utterances.fillers }.toSet()
        val shared = parsed.tokens.count { it in exampleWords }
        if (shared > 0) score += minOf(0.1, 0.05 * shared)
        return Candidate(flow, score.coerceIn(0.0, SIMILAR_CAP), slots, "similar")
    }

    companion object {
        const val SIMILAR_CAP = 0.75
        private val REPORT = listOf("what happened", "last run", "last time", "did it work", "how did it go", "status report", "previous run")
        private val LIST = listOf("what can you do", "what have you learned", "list flows", "what do you know", "show flows")

        private val verbGroups = mapOf(
            "order" to setOf("order", "get", "buy", "want", "need", "bring", "deliver", "send", "purchase", "grab", "khana", "mangao", "mangwa"),
            "search" to setOf("search", "find", "look", "show", "browse", "check"),
        )

        fun verbGroup(token: String): String? = verbGroups.entries.firstOrNull { token in it.value }?.key

        /** "order {qty} {item} to {address}" -> ^order (\S+) (.+?) to (.+?)$ with slot names. */
        fun templateRegex(template: String): Pair<Regex, List<String>>? {
            val names = mutableListOf<String>()
            val pattern = template.trim().split(Regex("\\s+")).joinToString(" ") { token ->
                val slot = Regex("^\\{(\\w+)\\}$").matchEntire(token)?.groupValues?.get(1)
                if (slot != null) {
                    names += slot
                    if (slot == "qty") "(\\S+)" else "(.+?)"
                } else {
                    Regex.escape(token)
                }
            }
            if (names.isEmpty()) return null
            return runCatching { Regex("^$pattern$") to names.toList() }.getOrNull()
        }

        fun normaliseSlot(name: String, raw: String): String = when (name) {
            "qty" -> (raw.toIntOrNull() ?: Utterances.numberWords[raw.trim()])?.toString() ?: ""
            else -> raw.trim()
        }
    }
}
