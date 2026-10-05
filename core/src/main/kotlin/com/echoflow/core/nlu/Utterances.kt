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
        // Casual lead-ins ("I am craving…", "I really fancy…").
        "am", "is", "are", "craving", "crave", "fancy", "feel", "feeling", "wanna", "gonna", "really", "hungry", "kinda",
    )

    /** Pointer words before a source and generic nouns after it: "from that brick oven place". */
    private val sourcePointers = setOf("the", "that", "this", "those", "our", "my")
    private val sourceNouns = setOf("place", "restaurant", "shop", "store", "outlet", "joint", "cafe")

    val appNames = mapOf(
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "amazon" to "in.amazon.mShop.android.shopping",
        "flipkart" to "com.flipkart.android",
        "myntra" to "com.myntra.android",
        "blinkit" to "com.grofers.customerapp",
        "zepto" to "com.zeptoconsumerapp",
    )

    /** Apps that do the same kind of job: a flow taught on one may be tried on another (bonus B2). */
    val appCategory = mapOf(
        "in.amazon.mShop.android.shopping" to "shopping", "com.flipkart.android" to "shopping", "com.myntra.android" to "shopping",
        "in.swiggy.android" to "food", "com.application.zomato" to "food",
        "com.grofers.customerapp" to "grocery", "com.zeptoconsumerapp" to "grocery",
    )

    /** The spoken name for an app package ("myntra"), or null. */
    fun appWord(pkg: String): String? = appNames.entries.firstOrNull { it.value == pkg }?.key

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

    /**
     * A command with the parts EchoFlow can't do taken out: extras like "with extra cheese" (it
     * adds items as the app offers them) and "and pay with UPI" (it never pays). Both are said back
     * to the user instead of ending up in a restaurant or item name.
     */
    data class Extras(val command: String, val extras: List<String>, val wantsPayment: Boolean)

    fun splitExtras(utterance: String): Extras {
        var t = TextNormalizer.normalize(utterance)
        val pay = PAYMENT_TAIL.find(t)
        if (pay != null) t = t.substring(0, pay.range.first).trim()
        val extras = CUSTOMISATION.findAll(t).map { it.value.trim() }.toList()
        extras.forEach { t = t.replace(" $it", "") }
        return Extras(t.trim(), extras, pay != null)
    }

    /** "Don't order…", "do not search…", "never mind…", Hinglish "…mat karo": nothing should run. */
    fun isNegated(utterance: String): Boolean {
        val t = TextNormalizer.normalize(utterance)
        if (t.startsWith("dont forget") || t.startsWith("do not forget")) return false
        return NEGATION.containsMatchIn(t) || Regex("\\bmat (karo|karna|kar)\\b").containsMatchIn(t)
    }

    private val PAYMENT_TAIL = Regex("\\s+(?:and\\s+)?(?:then\\s+)?(?:pay|make\\s+(?:the\\s+)?payment|checkout|check\\s+out|place\\s+(?:the\\s+)?order|confirm\\s+(?:the\\s+)?order)\\b.*$")
    private val CUSTOMISATION = Regex("\\s+(?:with\\s+(?:extra|no|less|more|double|added)|without)\\s+[a-z]+(?:\\s+[a-z]+)?(?=\\s+(?:from|on|to|and)\\b|$)")
    private val NEGATION = Regex("^(?:please\\s+)?(?:i\\s+)?(?:dont|do not|never|no need to|nevermind|never mind)\\b")

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
            while (k < tokens.size && tokens[k] in sourcePointers) k++
            val words = mutableListOf<String>()
            while (k < tokens.size && words.size < 3 && !used[k] && tokens[k] !in sourceStops) {
                words += tokens[k]
                k++
            }
            // "…brick oven place": the generic noun isn't part of the name (it's still consumed).
            while (k < tokens.size && !used[k] && tokens[k] in sourceNouns) k++
            while (words.size > 1 && words.last() in sourceNouns) words.removeAt(words.size - 1)
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
                // "a couple of pizzas": "of" belongs to this quantity phrase, not the item.
                // Consume only the adjacent connector; an "of" in a product/source name stays.
                if (t == "couple" && tokens.getOrNull(i + 1) == "of" && !used[i + 1]) take(i + 1)
            }
        }
        val item = tokens.filterIndexed { i, t -> !used[i] && t !in fillers }.joinToString(" ").ifBlank { null }
        val core = tokens.filterIndexed { i, _ -> !skip[i] }
        return Parsed(tokens, qty, qtyToken, address, app, item, source, core)
    }
}
