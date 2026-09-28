package com.echoflow.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.echoflow.app.EchoRuntime
import com.echoflow.core.flow.Step
import java.text.DateFormat
import java.util.Date

/** Shows a saved flow: what it answers to, its changeable values, and every step (T1). */
class FlowInspectorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EchoRuntime.init(this)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val flow = EchoRuntime.orchestrator.flows.get(id) ?: return finish()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun t(s: String, size: Float = 14f, bold: Boolean = false) = col.addView(TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 4, 0, pad / 4)
        })

        t(flow.template, 22f, bold = true)
        t("App: ${flow.appLabel ?: flow.appPackage}")
        t("Taught: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(flow.createdAtMs))}")
        t("Teaching ended at: ${flow.endedAt}")
        t("Recorded ${flow.recordedActions} actions → kept ${flow.steps.size - 1} steps (plus opening the app)")

        t("Answers to", 17f, bold = true)
        flow.examples.forEach { t("• “$it”") }

        t("Changeable values (slots)", 17f, bold = true)
        if (flow.slots.isEmpty()) t("None — this flow always does the same thing.")
        flow.slots.forEach { t("• {${it.name}} — ${it.type.name.lowercase()}, taught as “${it.taughtValue}”") }

        t("Steps", 17f, bold = true)
        flow.steps.forEachIndexed { i, s ->
            val ctx = when (s) {
                is Step.Tap -> s.target.context
                is Step.RepeatTap -> s.target.context
                else -> emptyList()
            }
            t("${i + 1}. ${s.description}" + if (ctx.isNotEmpty()) "\n     near: ${ctx.take(3).joinToString(" · ")}" else "")
        }
        t("Then: hand over to you at checkout / payment. EchoFlow never pays.", 13f)

        col.addView(Button(this).apply {
            text = "Run now (taught values)"
            isAllCaps = false
            setOnClickListener {
                EchoRuntime.orchestrator.onTyped(flow.examples.first())
                finish()
            }
        })
        col.addView(Button(this).apply {
            text = "Show raw JSON"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@FlowInspectorActivity)
                    .setTitle(flow.id)
                    .setMessage(EchoRuntime.orchestrator.flows.rawJson(flow.id))
                    .setPositiveButton("Close", null)
                    .show()
            }
        })
        col.addView(Button(this).apply {
            text = "Delete this flow"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@FlowInspectorActivity)
                    .setMessage("Delete “${flow.template}”?")
                    .setPositiveButton("Delete") { _, _ -> EchoRuntime.orchestrator.flows.delete(flow.id); finish() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    companion object {
        const val EXTRA_ID = "flow_id"
    }
}
