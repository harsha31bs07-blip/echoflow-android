package com.echoflow.core.nlu

import com.echoflow.core.text.TextNormalizer

/**
 * Local (no-network) parsing of spoken commands: numbers, "to <address>", app names and the
 * leftover item words. Good enough for the teaching utterance and for replay commands that
 * follow the taught shape; paraphrases go through the LLM when it's available.
 */
object Utterances {
    val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "single" to 1, "two" to 2, "couple" to 2, "three" to 3,
        "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5,
    )

    /** Leading words that mean "teach this": stripped from the teaching utterance. */
    private val teachPrefixes = listOf(
        "teach me how to", "teach me to", "teach you to", "teach you how to", "teach", "learn how to",
        "learn to", "learn", "remember how to", "new flow",
    ).map(TextNormalizer::tokens)

    /** Verbs and filler that don't carry slot values. */
    val fillers = setOf(
        "order", "get", "buy", "book", "add", "search", "find", "look", "for", "me", "please", "can", "you",
        "i", "want", "need", "would", "like", "to", "some", "the", "a", "an", "of", "on", "from", "in",
        "and", "deliver", "delivered", "delivery", "send", "bring", "place", "my", "it", "up", "show", "open",
        "at", "with", "using", "via", "quantity", "qty", "pieces", "piece", "plates", "plate", "items", "item",
        "x", "just", "now", "quickly", "again", "same", "one", "ones", "go", "do", "that", "this", "let", "us", "lets",
    )

    val appNames = mapOf(
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "myntra" to "com.myntra.android",
        "blinkit" to "com.grofers.customerapp",
        "zepto" to "com.zeptoconsumerapp",
    )

    data class Parsed(
        val tokens: List<String>,
        val quantity: Int? = null,
        val quantityToken: String? = null,
        val address: String? = null,
        val appMention: String? = null,
        /** Content words left after removing fillers, numbers, address, app and source ("garlic bread"). */
        val item: String? = null,
        /** Where from: "from Domino's" -> "dominos" (a restaurant or store; not the app). */
        val source: String? = null,
        /** Tokens without the app mention, the address phrase and the quantity (used for relaxed matching). */
        val core: List<String> = tokens,
    )

    fun stripTeachPrefix(utterance: String): String {
        val tokens = TextNormalizer.tokens(utterance)
        val p = teachPrefixes.firstOrNull { pre -> pre.size <= tokens.size && pre.indices.all { tokens[it] == pre[it] } }
        return (if (p != null) tokens.drop(p.size) else tokens).joinToString(" ")
    }

    /** Words that end a "from <source>" phrase. */
    private val sourceStops = setOf(
        "on", "in", "to", "at", "and", "please", "deliver", "delivered", "delivery", "for", "with", "using", "via", "now",
    )

    fun parse(utterance: String): Parsed {
        val tokens = TextNormalizer.tokens(utterance)
        var qty: Int? = null
        var qtyToken: String? = null
        var address: String? = null
        var app: String? = null
        var source: String? = null
        // Tokens taken by the app mention, the address or the quantity; then the source as well.
        val skip = BooleanArray(tokens.size)
        val used = BooleanArray(tokens.size)
        fun take(i: Int) { skip[i] = true; used[i] = true }

        tokens.forEachIndexed { i, t ->
            if (appNames.containsKey(t)) {
                app = t
                take(i)
                if (i > 0 && tokens[i - 1] in setOf("on", "from", "in", "using", "via")) take(i - 1)
            }
        }
        // "to home", "deliver to work", "at office" at the end (1–2 words).
        val addrIdx = tokens.indices.lastOrNull { i ->
            tokens[i] in setOf("to", "at") && i + 1 < tokens.size && !used[i + 1] &&
                !(tokens[i] == "to" && i >= 1 && tokens[i - 1] in setOf("want", "like", "need", "how"))
        }
        if (addrIdx != null) {
            var k = addrIdx + 1
            if (k < tokens.size && tokens[k] in setOf("my", "the")) k++
            val words = mutableListOf<String>()
            while (k < tokens.size && words.size < 2 && !used[k] && tokens[k] !in setOf("please", "now", "address")) {
                words += tokens[k]
                k++
            }
            if (words.isNotEmpty() && words.none { it.all(Char::isDigit) } && words.first() !in setOf("cart", "bag")) {
                address = words.joinToString(" ")
                for (j in addrIdx until k) take(j)
            }
        }
        // "from Domino's" (not "from Swiggy": app mentions are already taken).
        val fromIdx = tokens.indices.firstOrNull { i -> tokens[i] == "from" && !used[i] && i + 1 < tokens.size && !used[i + 1] }
        if (fromIdx != null) {
            var k = fromIdx + 1
            if (k < tokens.size && tokens[k] == "the") k++
            val words = mutableListOf<String>()
            while (k < tokens.size && words.size < 3 && !used[k] && tokens[k] !in sourceStops) {
                words += tokens[k]
                k++
            }
            if (words.isNotEmpty() && words.none { it.all(Char::isDigit) }) {
                source = words.joinToString(" ")
                for (j in fromIdx until k) used[j] = true
            }
        }
        tokens.forEachIndexed { i, t ->
            if (used[i] || qty != null) return@forEachIndexed
            val n = t.toIntOrNull()?.takeIf { it in 1..99 } ?: numberWords[t]?.takeIf { t !in setOf("a", "an", "do") }
            if (n != null) {
                qty = n
                qtyToken = t
                take(i)
            }
        }
        val item = tokens.filterIndexed { i, t -> !used[i] && t !in fillers }.joinToString(" ").ifBlank { null }
        val core = tokens.filterIndexed { i, _ -> !skip[i] }
        return Parsed(tokens, qty, qtyToken, address, app, item, source, core)
    }
}
