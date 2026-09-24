package com.echoflow.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes UI strings into lowercase tokens so lexicon phrases match regardless of case,
 * punctuation, or symbols: "Proceed to Pay ₹349" -> [proceed, to, pay, ₹, 349].
 * Devanagari combining marks are kept so Hindi words stay intact.
 */
object TextNormalizer {
    private val separators = Regex("[^\\p{L}\\p{M}\\p{N}₹@]+")
    private val camelBoundary = Regex("(?<=[a-z])(?=[A-Z])")

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace("&", " and ")
            .replace("₹", " ₹ ")
            .lowercase(Locale.ROOT)
            .replace(separators, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun tokens(raw: String?): List<String> = normalize(raw).split(' ').filter { it.isNotEmpty() }

    /** "com.foo:id/cardNumber_input" -> [card, number, input] */
    fun viewIdTokens(viewId: String?): List<String> {
        if (viewId.isNullOrBlank()) return emptyList()
        val entry = viewId.substringAfter(":id/", viewId)
        return tokens(entry.replace(camelBoundary, " ").replace('_', ' '))
    }

    fun containsPhrase(tokens: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > tokens.size) return false
        for (start in 0..tokens.size - phrase.size) {
            if (phrase.indices.all { tokens[start + it] == phrase[it] }) return true
        }
        return false
    }

    fun startsWithPhrase(tokens: List<String>, phrase: List<String>): Boolean =
        phrase.isNotEmpty() && phrase.size <= tokens.size && phrase.indices.all { tokens[it] == phrase[it] }
}

/** A lexicon phrase, pre-tokenized once. */
class Phrase(val source: String) {
    val tokens: List<String> = TextNormalizer.tokens(source)

    fun foundIn(tokens: List<String>): Boolean = TextNormalizer.containsPhrase(tokens, this.tokens)
    fun startsOf(tokens: List<String>): Boolean = TextNormalizer.startsWithPhrase(tokens, this.tokens)
    override fun toString(): String = source
}

fun phrases(vararg items: String): List<Phrase> = items.map(::Phrase)
