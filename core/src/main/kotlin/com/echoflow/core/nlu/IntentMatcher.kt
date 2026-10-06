package com.echoflow.core.nlu

import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotType
import com.echoflow.core.flow.SourceSlots
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
    /** "exact", "template", "similar", "llm" or "cross-app". */
    val source: String,
    /** Run this flow in another app of the same kind (bonus B2: an Amazon flow on Myntra). */
    val targetApp: String? = null,
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
            t in setOf("done", "finish", "finished", "save", "save it", "thats it", "that is it", "stop teaching", "i am done", "im done") ->
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
                c.copy(score = maxOf(c.score, llm.confidence), slots = cleanSlots(c.flow, c.slots + llm.slots.filterValues { it.isNotBlank() }), source = if (llm.confidence > c.score) "llm" else c.source)
            } else if (llm.flowId == null && c.source == "similar") {
                // The model read the command and matched no flow (or wasn't sure): a mere
                // similarity guess ("show my wishlist" vs "search … and add to cart") shouldn't
                // turn into "Do you want me to …?". Below the confirm line, it offers to learn it.
                c.copy(score = minOf(c.score, if (llm.confidence >= 0.7) 1 - llm.confidence else 0.4))
            } else c
        }
        // A flow for the app the command names beats trying another app's flow there.
        val named = Utterances.parse(norm).appMention?.let { Utterances.appNames[it] }
        val nativeHit = merged.any { it.targetApp == null && it.flow.appPackage == named && it.score >= 0.45 }
        return merged.filter { !(nativeHit && it.targetApp != null) }.sortedByDescending { it.score }
    }

    private fun localScore(norm: String, flow: Flow): Candidate {
        val own = rawScore(norm, flow).let { c -> c.copy(slots = cleanSlots(flow, c.slots)) }
        return crossApp(norm, flow)?.takeIf { it.score > own.score } ?: own
    }

    /**
     * B2: "…on Myntra" with a flow taught on Amazon (same kind of app): read the command as if it
     * named the taught app; if the shape fits, offer to run the same steps in the named app. Always
     * confirmed first (score below the confirm line).
     */
    private fun crossApp(norm: String, flow: Flow): Candidate? {
        val mention = Utterances.parse(norm).appMention ?: return null
        val target = Utterances.appNames[mention] ?: return null
        if (target == flow.appPackage) return null
        val category = Utterances.appCategory[target] ?: return null
        if (Utterances.appCategory[flow.appPackage] != category) return null
        val own = Utterances.appWord(flow.appPackage) ?: return null
        val asTaught = norm.split(' ').joinToString(" ") { if (it == mention) own else it }
        val c = rawScore(asTaught, flow)
        if (c.source != "template" && c.source != "exact") return null
        return Candidate(flow, minOf(c.score, CROSS_APP_SCORE), cleanSlots(flow, c.slots), "cross-app", targetApp = target)
    }

    private fun rawScore(norm: String, flow: Flow): Candidate {
        // 1. Exact example (T2): the taught slot values apply.
        if (flow.examples.any { TextNormalizer.normalize(it) == norm }) {
            return Candidate(flow, 1.0, flow.slots.associate { it.name to it.taughtValue }, "exact")
        }
        val p = Utterances.parse(norm)
        // A command naming a different app never uses this flow's template ("… on zomato" vs a
        // Swiggy flow), nor does one naming a restaurant when the flow has no place for it.
        val mentioned = p.appMention?.let { Utterances.appNames[it] }
        val flowSource = flow.slots.firstOrNull { it.name in SourceSlots.names }?.name
        // "from dominos" for a flow taught "… on dominos": the same fixed place, not a new restaurant.
        val sourceIsTaughtPlace = p.source?.let { src -> TextNormalizer.tokens(src).all { it in placeWords(flow) } } == true
        val shapeOk = (mentioned == null || mentioned == flow.appPackage) && (p.source == null || flowSource != null || sourceIsTaughtPlace)
        // 2. Template regex: same shape, different values (T4–T6).
        if (shapeOk) templateRegex(flow.template)?.let { (regex, names) ->
            regex.matchEntire(norm)?.let { m ->
                val slots = names.mapIndexed { i, n -> n to normaliseSlot(n, m.groupValues[i + 1]) }.toMap().toMutableMap()
                // A greedy item slot swallows "to home" / "on swiggy": split those back out.
                val item = slots["item"]
                if (item != null && p.item != null && item != p.item && item.contains(p.item)) slots["item"] = p.item
                if (p.address != null && "address" !in names) slots["address"] = p.address
                if (p.quantity != null && "qty" !in names) slots["qty"] = p.quantity.toString()
                if (slots.values.none { it.isBlank() }) return Candidate(flow, 0.95, slots, "template")
            }
        }
        // 2b. Relaxed template: the same command in looser words ("get me a margherita from
        // dominos" for "order a {item} pizza from {restaurant} on zomato"). Articles and politeness
        // dropped, verbs mapped to their group, plurals and qualifiers optional, and a "from X"
        // part may be left out (it's asked for mid-flow, bonus B3).
        if (shapeOk) relaxedMatch(p, flow)?.let { slots ->
            val itemMissing = flow.slots.any { it.name == "item" } && cleanSlots(flow, slots)["item"].isNullOrBlank()
            p.address?.let { slots["address"] = it }
            p.quantity?.let { slots["qty"] = it.toString() }
            // Without the main value ("order pizza") it's only a guess: confirm (T13).
            return Candidate(flow, if (itemMissing) 0.7 else RELAXED_SCORE, slots, "template")
        }
        // 3. Similarity: intent verbs, app mention, and whether the slots are there. Capped at 0.75.
        val parsed = p
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
            }
        }
        when {
            parsed.source == null -> Unit
            flowSource != null -> { slots[flowSource] = parsed.source; score += 0.1 }
            else -> score -= 0.2 // "from Domino's" but this flow has no restaurant
        }
        // Quantity and address apply to any ordering flow (set at the cart / address sheet).
        parsed.quantity?.let { slots["qty"] = it.toString() }
        parsed.address?.let { slots["address"] = it }
        if (flow.slots.any { it.name == "item" } && slots["item"] != null) score += 0.2
        // Shared non-slot words with any example (weak evidence).
        val exampleWords = flow.examples.flatMap(TextNormalizer::tokens).filter { it !in Utterances.fillers }.toSet()
        val shared = parsed.tokens.count { it in exampleWords }
        if (shared > 0) score += minOf(0.1, 0.05 * shared)
        return Candidate(flow, score.coerceIn(0.0, SIMILAR_CAP), slots, "similar")
    }

    /** Relaxed template match on [Utterances.Parsed.core]; null if the shapes differ. */
    private fun relaxedMatch(p: Utterances.Parsed, flow: Flow): MutableMap<String, String>? {
        if (!flow.template.contains('{')) return null
        val qualifiers = flow.slots.flatMap { it.qualifiers }.toSet()
        val tpl = relaxTemplate(flow)
        if (tpl.none { it.slot == "item" } && flow.slots.any { it.name == "item" }) return null
        val names = mutableListOf<String>()
        val pattern = StringBuilder("^")
        var i = 0
        while (i < tpl.size) {
            val t = tpl[i]
            val next = tpl.getOrNull(i + 1)
            val nextSlot = next?.slot
            when {
                // "from {restaurant}": optional as a pair.
                t.slot == null && t.word in OPTIONAL_PREPS && nextSlot != null && nextSlot != "item" -> {
                    names += nextSlot
                    pattern.append("(?: ${Regex.escape(t.word)} (.+?))?")
                    i += 2
                    continue
                }
                t.slot != null -> {
                    names += t.slot
                    pattern.append(" (.+?)")
                }
                t.word in qualifiers -> pattern.append("(?: ${plural(t.word)})?")
                else -> pattern.append(" ${plural(t.word)}")
            }
            i++
        }
        pattern.append("$")
        val said = " " + dropPlacePreps(relax(p.core), placeWords(flow)).joinToString(" ")
        val m = runCatching { Regex(pattern.toString()) }.getOrNull()?.matchEntire(said) ?: return null
        val slots = mutableMapOf<String, String>()
        names.forEachIndexed { k, n ->
            val v = m.groupValues[k + 1].trim()
            // A value can't span a structural word ("margherita from dominos" isn't an item).
            if (v.split(' ').any { it in STRUCTURAL }) return null
            if (v.isNotBlank()) slots[n] = v
        }
        return slots
    }

    private data class Tok(val word: String, val slot: String? = null)

    /** The template without the app mention, quantity and address, in relaxed words. */
    private fun relaxTemplate(flow: Flow): List<Tok> {
        val raw = flow.template.trim().split(Regex("\\s+"))
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val w = raw[i]
            val next = raw.getOrNull(i + 1)
            when {
                w in APP_PREPS && next != null && Utterances.appNames.containsKey(next) -> i++
                Utterances.appNames.containsKey(w) -> Unit
                w in setOf("to", "at") && next == "{address}" -> i++
                w == "{address}" || w == "{qty}" -> Unit
                else -> out += w
            }
            i++
        }
        return dropPlacePreps(relax(out), placeWords(flow)).map { w -> Regex("^\\{(\\w+)\\}$").matchEntire(w)?.let { Tok(w, it.groupValues[1]) } ?: Tok(w) }
    }

    /**
     * Fixed words of the template that follow "on/from/at/in": where the task happens ("on dominos").
     * Said with any of those prepositions it's the same place.
     */
    private fun placeWords(flow: Flow): Set<String> {
        val raw = flow.template.trim().split(Regex("\\s+")).map { if (it.startsWith("{")) it else TextNormalizer.normalize(it) }
        return raw.indices.filter { i ->
            i > 0 && raw[i - 1] in PLACE_PREPS && !raw[i].startsWith("{") && raw[i] !in PLACE_PREPS && verbGroup(raw[i]) == null
        }.map { raw[it] }.toSet()
    }

    private fun dropPlacePreps(tokens: List<String>, places: Set<String>): List<String> =
        tokens.filterIndexed { i, t -> !(t in PLACE_PREPS && tokens.getOrNull(i + 1) in places) }

    /** Drop articles and politeness, map verbs to their group, collapse repeats ("want to order"). */
    private fun relax(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        tokens.forEachIndexed { i, t ->
            val next = tokens.getOrNull(i + 1)
            val w = when {
                t.startsWith("{") -> t
                t in RELAX_DROP -> null
                t == "to" && next != null && verbGroup(next) != null -> null
                // "look up coldplay", "look for shoes", "check out …": the particle belongs to the verb.
                t in VERB_PARTICLES && out.lastOrNull()?.let { it in verbGroups.keys } == true -> null
                t in setOf("deliver", "delivered", "delivery") && out.isNotEmpty() -> null
                else -> verbGroup(t) ?: t
            }
            if (w != null && w != out.lastOrNull()) out += w
        }
        return out
    }

    /**
     * Tidy slot values: "a phone case" -> "phone case"; qualifiers the flow learned are removed
     * ("farmhouse pizzas" -> "farmhouse"). A value that becomes empty is left out (asked mid-flow).
     */
    fun cleanSlots(flow: Flow, slots: Map<String, String>): Map<String, String> = slots.mapNotNull { (k, v) ->
        val def = flow.slots.firstOrNull { it.name == k }
        if (def?.type == SlotType.NUMBER || k == "qty") return@mapNotNull k to v
        var words = TextNormalizer.tokens(v)
        while (words.size > 1 && words.first() in ARTICLES) words = words.drop(1)
        val q = def?.qualifiers.orEmpty().map(::singular).toSet()
        if (q.isNotEmpty()) words = words.filterIndexed { i, word ->
            val base = singular(word)
            val exact = base in q
            // ASR can duplicate a letter in a plural qualifier ("pizzaas"). Only a trailing
            // plural can use this tolerance, and only against a qualifier this flow learned.
            val closePlural = i == words.lastIndex && word.endsWith("s") && base.length >= 5 &&
                q.any { qualifier -> qualifier.length >= 5 && editDistance(base, qualifier) == 1 }
            !exact && !closePlural
        }
        // "two choco lava cakes": the app lists one "Choco Lava Cake", and its search may not match the plural.
        val many = (slots["qty"]?.toIntOrNull() ?: 1) > 1
        val last = words.lastOrNull()
        if (many && last != null && last.length > 4 && last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("ies")) {
            val one = if (Regex("(?:sh|ch|x|z|o)es$").containsMatchIn(last)) last.dropLast(2) else last.dropLast(1)
            words = words.dropLast(1) + one
        }
        if (words.isEmpty() || words.all { it in ARTICLES }) null else k to soundsLikeTaught(words.joinToString(" "), def?.taughtValue)
    }.toMap()

    /**
     * Speech recognition spells names the common way ("brick oven" for "Brik Oven",
     * "margarita" for "margherita"). A value that differs from the taught one by a letter or two
     * per word is the taught one, spelled as the app spells it.
     */
    private fun soundsLikeTaught(value: String, taught: String?): String {
        val t = taught?.let(TextNormalizer::tokens) ?: return value
        val v = TextNormalizer.tokens(value)
        if (v.size != t.size || v == t) return value
        val close = v.zip(t).all { (a, b) -> a == b || (minOf(a.length, b.length) >= 4 && editDistance(a, b) <= if (b.length >= 8) 2 else 1) }
        return if (close) t.joinToString(" ") else value
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    companion object {
        const val SIMILAR_CAP = 0.75
        const val RELAXED_SCORE = 0.88
        const val CROSS_APP_SCORE = 0.78
        private val ARTICLES = setOf("a", "an", "the", "some", "any")
        private val RELAX_DROP = setOf(
            "a", "an", "the", "some", "me", "please", "can", "could", "would", "will", "you", "i", "im", "like",
            "just", "now", "kindly", "hey", "for", "us", "lets", "quickly",
        )
        private val OPTIONAL_PREPS = setOf("from", "at", "in", "to")
        private val APP_PREPS = setOf("on", "from", "in", "using", "via")
        private val STRUCTURAL = setOf("from", "on")
        private val PLACE_PREPS = setOf("on", "from", "at", "in")

        private fun plural(word: String) = Regex.escape(word) + "(?:s|es)?"
        fun singular(w: String) = when {
            w.length > 4 && w.endsWith("es") && !w.endsWith("ses") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }

        private val REPORT = listOf("what happened", "last run", "last time", "did it work", "how did it go", "status report", "previous run", "succeed", "successful", "did it go through", "did it fail")
        private val LIST = listOf("what can you do", "what have you learned", "list flows", "what do you know", "show flows")

        private val VERB_PARTICLES = setOf("up", "for", "out")
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
