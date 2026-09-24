package com.echoflow.core.safety

import com.echoflow.core.model.ScreenSnapshot
import com.echoflow.core.model.UiElement

/**
 * Removes user data from snapshots before they leave memory (fixture dumps, logs, and later
 * LLM prompts). App UI strings are kept so classification still works on the dump.
 *  - editable fields: typed text dropped entirely (hints kept);
 *  - digit runs of 4+ masked (phone numbers, card digits, OTPs, order ids);
 *  - email addresses masked.
 */
object Redactor {
    private val longDigits = Regex("\\d(?:[\\s-]?\\d){3,}")
    private val email = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")

    fun redact(snapshot: ScreenSnapshot): ScreenSnapshot =
        snapshot.copy(
            elements = snapshot.elements.map(::redact),
            windows = snapshot.windows.map { it.copy(title = maskText(it.title)) },
        )

    fun redact(element: UiElement): UiElement =
        element.copy(
            text = if (element.editable || element.password) null else maskText(element.text),
            contentDescription = maskText(element.contentDescription),
            hintText = maskText(element.hintText),
        )

    fun maskText(value: String?): String? =
        value
            ?.replace(email, "[email]")
            ?.replace(longDigits) { m -> m.value.replace(Regex("\\d"), "#") }
}
