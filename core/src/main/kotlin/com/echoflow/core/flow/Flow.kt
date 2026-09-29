package com.echoflow.core.flow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How to find one UI element again on a later run. Text fields may contain slot placeholders
 * like "{item}", which are filled in before matching (see [ElementResolver]).
 */
@Serializable
data class ElementDescriptor(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String = "",
    /** Class + view id of the parent node, e.g. "LinearLayout#in.swiggy.android:id/row". */
    val parentSignature: String? = null,
    /** Labels in the element's row/card (not its own label), e.g. the dish name next to an ADD button. */
    val context: List<String> = emptyList(),
    /** Centre of the element as a fraction of the screen, used only to break ties. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val packageName: String? = null,
) {
    /** Short human-readable label for the inspector and spoken reports. */
    val display: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: readableId()
            // Icon-font glyphs (private-use characters) aren't readable: skip them.
            ?: context.firstOrNull { c -> c.any { it.isLetterOrDigit() } }?.let { "the button near \"$it\"" }
            ?: className.substringAfterLast('.')

    /** "in.swiggy.android:id/search_bar" -> "search bar"; null for generic ids ("container"). */
    private fun readableId(): String? {
        val words = viewId?.substringAfter(":id/")?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            ?.split('_', '-', ' ')?.map { it.lowercase() }?.filter { it.isNotBlank() && it !in GENERIC_ID_WORDS }
            ?: return null
        return words.takeIf { it.isNotEmpty() && it.none { w -> w.any(Char::isDigit) } }?.joinToString(" ")
    }

    private companion object {
        val GENERIC_ID_WORDS = setOf("container", "layout", "root", "view", "item", "itemlayout", "wrapper", "holder", "frame", "ll", "rl", "cl", "v2")
    }
}

@Serializable
enum class SlotType { TEXT, NUMBER }

@Serializable
data class SlotDef(
    val name: String,
    val type: SlotType,
    /** The value used while teaching, e.g. "garlic bread". */
    val taughtValue: String,
    /**
     * Words said with the value that weren't part of it on screen: "margherita pizza" taught on a
     * menu that says "Margherita" gives value "margherita" and qualifiers ["pizza"]. Stripped from
     * new values ("farmhouse pizzas" -> "farmhouse").
     */
    val qualifiers: List<String> = emptyList(),
)

/** Slot names for "from <X>": a restaurant in food apps, a store elsewhere. */
object SourceSlots {
    val names = setOf("restaurant", "store")
    private val foodApps = setOf("in.swiggy.android", "com.application.zomato")

    fun nameFor(appPackage: String) = if (appPackage in foodApps) "restaurant" else "store"
}

@Serializable
sealed class Step {
    /** One-line description for the inspector ("Tap \"ADD\" next to {item}"). */
    abstract val description: String

    @Serializable
    @SerialName("launch")
    data class LaunchApp(val packageName: String, val appLabel: String? = null) : Step() {
        override val description get() = "Open ${appLabel ?: packageName}"
    }

    @Serializable
    @SerialName("tap")
    data class Tap(
        val target: ElementDescriptor,
        /** Slot whose value must appear in the target or its row (search result, saved address). */
        val slot: String? = null,
        val activity: String? = null,
        /** "first": the taught command said "the first result", so position wins over text (T8/T9). */
        val pick: String? = null,
    ) : Step() {
        override val description get() =
            if (pick == "first") "Tap the first result" + (slot?.let { " for {$it}" } ?: "")
            else if (pick == "add_to_cart") "Tap \"Add to cart\""
            else "Tap \"${target.display}\"" + (slot?.let { " (the one matching {$it})" } ?: "")
    }

    @Serializable
    @SerialName("type")
    data class TypeText(
        val target: ElementDescriptor,
        /** Literal text, when the typed value isn't a slot. */
        val literal: String? = null,
        val slot: String? = null,
        val activity: String? = null,
    ) : Step() {
        override val description get() = "Type ${slot?.let { "{$it}" } ?: "\"$literal\""} into \"${target.display}\""
    }

    /** Tap the same element (a "+" stepper) enough times that the quantity equals the slot value. */
    @Serializable
    @SerialName("repeat_tap")
    data class RepeatTap(
        val target: ElementDescriptor,
        val slot: String,
        /** Taps = slot value - offset. Taught with qty 3 and two "+" taps -> offset 1. */
        val offset: Int,
        val activity: String? = null,
    ) : Step() {
        override val description get() = "Tap \"${target.display}\" until the quantity is {$slot}"
    }
}

@Serializable
data class Flow(
    val id: String,
    val name: String,
    val appPackage: String,
    val appLabel: String? = null,
    /** The teaching utterance with slot values replaced, e.g. "order {qty} {item}". */
    val template: String,
    /** Utterances this flow is known to answer to (teaching utterance + successful paraphrases). */
    val examples: List<String>,
    val slots: List<SlotDef>,
    val steps: List<Step>,
    /** Why teaching stopped: "PAYMENT", "CHECKOUT", "OTP", "user". */
    val endedAt: String = "user",
    /** Raw actions recorded vs kept after noise filtering (bonus B1). */
    val recordedActions: Int = 0,
    val createdAtMs: Long = 0,
) {
    fun slot(name: String): SlotDef? = slots.firstOrNull { it.name == name }
}
