package com.echoflow.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.echoflow.app.EchoRuntime
import com.echoflow.core.flow.Flow
import com.echoflow.core.flow.SlotDef
import com.echoflow.core.flow.Step
import java.text.DateFormat
import java.util.Date

/** Shows a saved flow: what it answers to, its changeable values, and every step (T1). */
class FlowInspectorActivity : Activity() {
    private lateinit var kit: Kit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoRuntime.init(this)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val flow = EchoRuntime.orchestrator.flows.get(id) ?: return finish()
        kit = Kit(this)

        val title = readableTitle(flow)
        val root = kit.column(0, 0)
        root.addView(kit.header(title, subtitle(flow), onBack = { finish() }))

        val body = kit.column(16, 16)
        body.addView(youCanSayCard(flow))
        body.addView(whatCanChangeCard(flow))
        body.addView(stepsCard(flow))
        body.addView(statsCaption(flow))
        addButtons(body, flow, title)
        body.addView(kit.space(24))

        root.addView(body)
        setContentView(kit.screen(this, root))
    }

    // ---- Header ----

    private fun readableTitle(flow: Flow): String =
        // With the learned values: "Order a margherita pizza from brik oven on Zomato".
        Words.template(flow.template, flow.slots.associate { it.name to it.taughtValue }, flow.appLabel).toString()
            .replace(Regex("\\s+"), " ").trim()

    private fun subtitle(flow: Flow): String {
        val app = flow.appLabel ?: flow.appPackage
        val n = flow.steps.size
        val parts = mutableListOf(app, "$n ${if (n == 1) "step" else "steps"}")
        if (flow.createdAtMs > 0) {
            parts += "taught " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(flow.createdAtMs))
        }
        return parts.joinToString(" · ")
    }

    // ---- "You can say" ----

    private fun youCanSayCard(flow: Flow) = kit.card {
        addView(cardTitle("You can say"))
        val values = flow.slots.map { it.taughtValue }
        flow.examples.forEach { example ->
            addView(kit.body(quoted(highlightValues(example, values))).apply { setPadding(0, kit.dp(4), 0, kit.dp(4)) })
        }
        val hint = if (flow.slots.isEmpty()) {
            "This one always does the same thing."
        } else {
            "Change the highlighted parts, like ${listWords(flow.slots.map { "the " + Words.slotName(it.name) })}, and EchoFlow does the rest."
        }
        addView(kit.caption(hint).apply { setPadding(0, kit.dp(6), 0, 0) })
    }

    /** Wraps [text] in curly quotes, keeping its highlights. */
    private fun quoted(text: CharSequence): CharSequence = SpannableStringBuilder("“").append(text).append("”")

    /** Marks every taught value inside [example] the same way Words.template marks slots. */
    private fun highlightValues(example: String, values: List<String>): CharSequence {
        val out = SpannableStringBuilder(example.replaceFirstChar { it.uppercase() })
        val lower = out.toString().lowercase()
        val taken = BooleanArray(lower.length)
        values.filter { it.isNotBlank() }.map { it.lowercase() }.distinct().sortedByDescending { it.length }.forEach { v ->
            var from = 0
            while (true) {
                val at = lower.indexOf(v, from)
                if (at < 0) break
                val end = at + v.length
                if ((at until end).none { taken[it] }) {
                    (at until end).forEach { taken[it] = true }
                    out.setSpan(BackgroundColorSpan(Palette.MINT_TINT), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(Palette.MINT), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.BOLD), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                from = end
            }
        }
        return out
    }

    /** ["the item"] -> "the item"; ["a", "b", "c"] -> "a, b or c". */
    private fun listWords(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " or " + words.last()
    }

    // ---- "What can change" ----

    private fun whatCanChangeCard(flow: Flow) = kit.card {
        addView(cardTitle("What can change"))
        if (flow.slots.isEmpty()) {
            addView(kit.body("Nothing. This one always does the same thing."))
        }
        flow.slots.forEachIndexed { i, slot ->
            if (i > 0) addView(kit.divider())
            addView(slotRow(slot))
        }
        if (flow.template.trim().lowercase().startsWith("order")) {
            addView(kit.caption("You can always add how many and where: say a number or “to home” / “to work” too.").apply {
                setPadding(0, kit.dp(12), 0, 0)
            })
        }
    }

    private fun slotRow(slot: SlotDef): View {
        val chip = kit.chip(Words.slotName(slot.name)).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; marginEnd = kit.dp(12) }
        }
        val texts = kit.column(0, 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(kit.body(SpannableStringBuilder("learned as ").append(mintBold("“${slot.taughtValue}”"))))
            if (slot.qualifiers.isNotEmpty()) {
                addView(kit.caption("(you said “${slot.taughtValue} ${slot.qualifiers.joinToString(" ")}”)"))
            }
        }
        return kit.row(chip, texts).apply { minimumHeight = kit.dp(44) }
    }

    private fun mintBold(s: String): CharSequence = SpannableStringBuilder(s).apply {
        setSpan(ForegroundColorSpan(Palette.MINT), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ---- Steps ----

    private fun stepsCard(flow: Flow) = kit.card {
        addView(cardTitle("Steps"))
        flow.steps.forEachIndexed { i, step ->
            addView(stepRow(i + 1, step))
        }
        val where = if (flow.endedAt.uppercase() in setOf("CHECKOUT", "PAYMENT")) "at checkout" else "at the end"
        addView(kit.divider())
        addView(
            kit.row(
                decorative(kit.iconCircle("🛑", Palette.GOLD_TINT, 36)),
                kit.body("Then EchoFlow stops and hands over to you $where, before paying.").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            ).apply { minimumHeight = kit.dp(44) }
        )
    }

    private fun stepRow(number: Int, step: Step): View {
        val (symbol, tint) = when (step) {
            is Step.LaunchApp -> "▶" to Palette.CORAL_TINT
            is Step.TypeText -> "⌨" to if (step.slot != null) Palette.MINT_TINT else Palette.BG
            is Step.Tap -> when (step.pick) {
                "first" -> "🥇"
                "add_to_cart" -> "🛒"
                else -> "👆"
            } to if (step.slot != null) Palette.MINT_TINT else Palette.BG
            is Step.RepeatTap -> "🔁" to Palette.MINT_TINT
        }
        val target = when (step) {
            is Step.Tap -> step.target
            is Step.TypeText -> step.target
            is Step.RepeatTap -> step.target
            is Step.LaunchApp -> null
        }
        val near = target?.context.orEmpty()
            .map { it.trim() }
            .filter { s -> s.any { it.isLetterOrDigit() } }
            .distinct()
            .take(3)
            .map { if (it.length > 40) it.take(39) + "…" else it }

        val sentence = SpannableStringBuilder()
        val numStart = sentence.length
        sentence.append("$number.  ")
        sentence.setSpan(StyleSpan(Typeface.BOLD), numStart, sentence.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sentence.append(Words.template(step.description))

        val texts = kit.column(0, 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(kit.body(sentence))
            if (near.isNotEmpty()) addView(kit.caption("near: " + near.joinToString(" · ")))
        }
        return kit.row(decorative(kit.iconCircle(symbol, tint, 36)), texts).apply {
            minimumHeight = kit.dp(48)
            setPadding(0, kit.dp(6), 0, kit.dp(6))
        }
    }

    /** Icons next to text are decoration: screen readers read the sentence instead. */
    private fun decorative(v: View) = v.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    // ---- Stats ----

    private fun statsCaption(flow: Flow): View {
        val n = flow.steps.size
        val kept = "$n ${if (n == 1) "step" else "steps"}"
        val first = if (flow.recordedActions > 0) "Recorded ${flow.recordedActions} actions, kept $kept." else "Kept $kept."
        return kit.caption("$first Accidental taps are left out automatically.").apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(8), kit.dp(4), kit.dp(8), kit.dp(12))
        }
    }

    // ---- Buttons ----

    private fun addButtons(col: LinearLayout, flow: Flow, title: String) {
        flow.examples.firstOrNull()?.let { example ->
            col.addView(kit.primaryButton("▶  Try it now") {
                EchoRuntime.orchestrator.onTyped(example)
                finish()
            }.apply { contentDescription = "Try it now: $example" })
        }
        col.addView(kit.secondaryButton("Technical details") {
            AlertDialog.Builder(this)
                .setTitle("Technical details")
                .setMessage(EchoRuntime.orchestrator.flows.rawJson(flow.id))
                .setPositiveButton("Close", null)
                .show()
        })
        col.addView(kit.secondaryButton("Delete this flow", Palette.RED) {
            AlertDialog.Builder(this)
                .setMessage("Delete “$title”? EchoFlow will forget how to do it.")
                .setPositiveButton("Delete") { _, _ -> EchoRuntime.orchestrator.flows.delete(flow.id); finish() }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    private fun cardTitle(s: String) = kit.text(s, 18f, bold = true).apply { setPadding(0, 0, 0, kit.dp(8)) }

    companion object {
        const val EXTRA_ID = "flow_id"
    }
}
