package com.echoflow.core.flow

import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement
import com.echoflow.core.text.TextNormalizer

/** Builds [ElementDescriptor]s from snapshots and finds them again later. */
object Descriptors {
    private const val MAX_CONTEXT = 8
    private const val MAX_LABEL_CHARS = 80
    private const val MAX_ROW_LABELS = 12

    fun describe(snapshot: ScreenSnapshot, index: Int): ElementDescriptor {
        val e = snapshot.elements[index]
        val parent = snapshot.elements.getOrNull(e.parent)
        val cx = (e.bounds.left + e.bounds.right) / 2f / snapshot.screenWidth.coerceAtLeast(1)
        val cy = (e.bounds.top + e.bounds.bottom) / 2f / snapshot.screenHeight.coerceAtLeast(1)
        return ElementDescriptor(
            viewId = e.viewId,
            text = e.text?.takeIf { !e.editable && !e.password && it.isNotBlank() }?.let(::clip),
            contentDescription = e.contentDescription?.takeIf { it.isNotBlank() }?.let(::clip),
            className = e.className,
            parentSignature = parent?.let(::signature),
            context = rowContext(snapshot, index),
            centerX = cx.coerceIn(0f, 1f),
            centerY = cy.coerceIn(0f, 1f),
            packageName = e.packageName,
        )
    }

    fun signature(e: UiElement): String = e.simpleClassName + (e.viewId?.let { "#$it" } ?: "")

    /**
     * Labels of the element's "row": the nearest ancestor holding a small group of labels
     * (a list item or card). This is what tells one "ADD" button apart from another.
     */
    fun rowContext(snapshot: ScreenSnapshot, index: Int): List<String> {
        val self = snapshot.elements[index]
        val own = self.label?.let(TextNormalizer::normalize)
        var cursor = self.parent
        var hops = 0
        while (cursor >= 0 && hops < 6) {
            val labels = snapshot.descendants(cursor, maxDepth = 6)
                .filter { it.visible && !it.editable && it.index != index }
                .sortedBy { it.index }
                .mapNotNull { it.label?.let(::clip) }
                .filter { TextNormalizer.normalize(it) != own }
                .distinct()
                .toList()
            if (labels.size > MAX_ROW_LABELS) break
            if (labels.isNotEmpty()) return labels.take(MAX_CONTEXT)
            cursor = snapshot.elements[cursor].parent
            hops++
        }
        return emptyList()
    }

    /** Nearest clickable element at or above [index] (text views inside clickable rows). */
    fun clickableFor(snapshot: ScreenSnapshot, index: Int): Int {
        var cursor = index
        var hops = 0
        while (cursor >= 0 && hops < 8) {
            val e = snapshot.elements[cursor]
            if (e.clickable && e.enabled) return cursor
            cursor = e.parent
            hops++
        }
        return index
    }

    private fun clip(s: String): String = if (s.length <= MAX_LABEL_CHARS) s else s.take(MAX_LABEL_CHARS)
}

data class Resolution(
    /** Best-matching element. */
    val index: Int,
    /** Element to act on (nearest clickable ancestor for taps). */
    val actionIndex: Int,
    val score: Double,
    val runnerUpScore: Double,
)

/**
 * Weighted multi-feature locator, after SkillDroid (arXiv 2604.14872) with a stronger weight on
 * row context, because Swiggy/Lynx screens rarely expose view ids. Scores are normalised over
 * the features the descriptor actually has.
 */
class ElementResolver(
    private val minScore: Double = MIN_TAP_SCORE,
) {
    fun resolve(
        snapshot: ScreenSnapshot,
        descriptor: ElementDescriptor,
        slots: Map<String, String> = emptyMap(),
        requiredValue: String? = null,
        editableOnly: Boolean = false,
    ): Resolution? {
        val d = descriptor.fill(slots)
        val scored = snapshot.appElements()
            .filter { it.visible && it.bounds.area > 0 }
            .filter { !editableOnly || it.editable }
            .mapNotNull { e ->
                val ctx = Descriptors.rowContext(snapshot, e.index)
                var s = score(snapshot, d, e, ctx) ?: return@mapNotNull null
                if (requiredValue != null) {
                    val q = valueMatch(requiredValue, e.label, ctx)
                    if (q == 0.0) return@mapNotNull null
                    s = 0.6 * s + 0.4 * q
                }
                e.index to s
            }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < minScore) return null
        return Resolution(
            index = best.first,
            actionIndex = Descriptors.clickableFor(snapshot, best.first),
            score = best.second,
            runnerUpScore = scored.getOrNull(1)?.second ?: 0.0,
        )
    }

    /** Best score for a descriptor, even below the threshold (for diagnostics and lookahead). */
    fun bestScore(snapshot: ScreenSnapshot, descriptor: ElementDescriptor, slots: Map<String, String> = emptyMap()): Double =
        ElementResolver(0.0).resolve(snapshot, descriptor, slots)?.score ?: 0.0

    private fun score(snapshot: ScreenSnapshot, d: ElementDescriptor, e: UiElement, ctx: List<String>): Double? {
        var total = 0.0
        var weights = 0.0
        fun add(w: Double, m: Double) {
            total += w * m
            weights += w
        }
        d.viewId?.takeIf { it.isNotBlank() }?.let { add(W_ID, if (it == e.viewId) 1.0 else 0.0) }
        d.text?.takeIf { it.isNotBlank() }?.let { add(W_TEXT, textMatch(it, if (e.editable) null else e.text)) }
        d.contentDescription?.takeIf { it.isNotBlank() }?.let { add(W_DESC, textMatch(it, e.contentDescription)) }
        if (d.className.isNotEmpty()) add(W_CLASS, if (d.className == e.className) 1.0 else 0.0)
        d.parentSignature?.let { sig ->
            val p = snapshot.elements.getOrNull(e.parent)
            add(W_PARENT, if (p != null && Descriptors.signature(p) == sig) 1.0 else 0.0)
        }
        if (d.context.isNotEmpty()) {
            val found = d.context.count { c -> ctx.any { textMatch(c, it) >= 0.6 } || textMatch(c, e.label) >= 0.6 }
            add(W_CONTEXT, found.toDouble() / d.context.size)
        }
        val cx = (e.bounds.left + e.bounds.right) / 2.0 / snapshot.screenWidth.coerceAtLeast(1)
        val cy = (e.bounds.top + e.bounds.bottom) / 2.0 / snapshot.screenHeight.coerceAtLeast(1)
        val dist = kotlin.math.hypot(cx - d.centerX, cy - d.centerY)
        add(W_POSITION, (1.0 - dist * 2).coerceAtLeast(0.0))
        // Only position scored: the descriptor carries nothing identifying.
        if (weights <= W_POSITION + 1e-9) return null
        return total / weights
    }

    companion object {
        const val MIN_TAP_SCORE = 0.7
        const val W_ID = 0.30
        const val W_TEXT = 0.20
        const val W_DESC = 0.15
        const val W_CLASS = 0.05
        const val W_PARENT = 0.10
        const val W_CONTEXT = 0.20
        const val W_POSITION = 0.05

        /** 1.0 equal, 0.6 containment, else 0 (normalised, case- and punctuation-insensitive). */
        fun textMatch(expected: String, actual: String?): Double {
            if (actual.isNullOrBlank()) return 0.0
            val a = TextNormalizer.normalize(expected)
            val b = TextNormalizer.normalize(actual)
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            if (b.contains(a) || a.contains(b)) return 0.6
            return 0.0
        }

        /**
         * How well a slot value appears on an element: exact own label 1.0, own label contains it
         * 0.8, its row contains it 0.6. Plural "s" and word order are tolerated.
         */
        fun valueMatch(value: String, label: String?, context: List<String>): Double {
            val v = stem(TextNormalizer.tokens(value))
            if (v.isEmpty()) return 0.0
            val own = stem(TextNormalizer.tokens(label))
            if (own.isNotEmpty() && own.toSet() == v.toSet()) return 1.0
            if (covers(own, v)) return 0.8
            if (context.any { covers(stem(TextNormalizer.tokens(it)), v) }) return 0.6
            return 0.0
        }

        /** Every value word appears in [label], exactly or as a word prefix ("bread" → "breadsticks"). */
        private fun covers(label: List<String>, value: List<String>) =
            value.all { w -> label.any { it == w || (w.length >= 4 && it.startsWith(w)) } }

        private fun stem(tokens: List<String>) = tokens.map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
    }
}

/** Replaces "{slot}" placeholders with values. */
fun String.fillSlots(slots: Map<String, String>): String =
    slots.entries.fold(this) { acc, (k, v) -> acc.replace("{$k}", v, ignoreCase = true) }

fun ElementDescriptor.fill(slots: Map<String, String>): ElementDescriptor =
    if (slots.isEmpty()) this else copy(
        text = text?.fillSlots(slots),
        contentDescription = contentDescription?.fillSlots(slots),
        context = context.map { it.fillSlots(slots) },
    )
