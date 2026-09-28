package com.echoflow.core.teach

import com.echoflow.core.flow.Descriptors
import com.echoflow.core.flow.ElementDescriptor
import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.text.TextNormalizer
import kotlinx.serialization.Serializable

@Serializable
enum class RawKind { TAP, TYPE }

/** One thing the teacher did, as recorded before any cleanup. */
@Serializable
data class RawAction(
    val kind: RawKind,
    val atMs: Long,
    val packageName: String?,
    val activity: String?,
    val target: ElementDescriptor,
    /** Own label of the element that was tapped (null for fields). */
    val label: String? = null,
    /** Final text typed into the field (TYPE only). Never recorded for password fields. */
    val typed: String? = null,
    /** Screen fingerprint before the action. */
    val preFingerprint: String,
    /** Screen fingerprint after the action; null until the next screen is captured. */
    val postFingerprint: String? = null,
)

object Fingerprints {
    /**
     * A coarse screen identity: activity plus the first 30 short labels. Good enough to tell
     * "the screen changed" and "we're back where we were", not to identify screens exactly.
     */
    fun of(snapshot: ScreenSnapshot): String {
        val labels = snapshot.appElements()
            .asSequence()
            .filter { it.visible && !it.editable }
            .mapNotNull { it.label }
            .map(TextNormalizer::normalize)
            .filter { it.isNotEmpty() && it.length <= 40 && it.none(Char::isDigit) }
            .distinct()
            .take(30)
            .sorted()
            .joinToString("|")
        return "${snapshot.activityName ?: snapshot.packageName}::${labels.hashCode()}"
    }

    /** Builds a TAP [RawAction] for element [index] of [snapshot]. */
    fun tap(snapshot: ScreenSnapshot, index: Int, atMs: Long): RawAction {
        val e = snapshot.elements[index]
        return RawAction(
            kind = RawKind.TAP,
            atMs = atMs,
            packageName = e.packageName ?: snapshot.packageName,
            activity = snapshot.activityName,
            target = Descriptors.describe(snapshot, index),
            label = e.label,
            preFingerprint = of(snapshot),
        )
    }

    fun type(snapshot: ScreenSnapshot, index: Int, text: String, atMs: Long): RawAction {
        val e = snapshot.elements[index]
        return RawAction(
            kind = RawKind.TYPE,
            atMs = atMs,
            packageName = e.packageName ?: snapshot.packageName,
            activity = snapshot.activityName,
            target = Descriptors.describe(snapshot, index).copy(text = null),
            typed = text,
            preFingerprint = of(snapshot),
        )
    }
}
