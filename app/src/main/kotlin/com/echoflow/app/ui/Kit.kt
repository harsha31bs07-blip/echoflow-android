package com.echoflow.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.echoflow.core.runlog.RunStatus

/**
 * EchoFlow's colours (the presentation deck's palette). Coral, mint, red and the muted grey are
 * the deck's hues darkened just enough for WCAG AA: white text on them, and them as text on the
 * light backgrounds, all reach 4.5:1 (the launcher icon keeps the brighter deck colours).
 */
object Palette {
    const val INK = 0xFF161A33.toInt()
    const val INK_SOFT = 0xFF2A2F55.toInt()
    const val CORAL = 0xFFC8401F.toInt() // deck FF6B4A: white on it was 2.8:1
    const val MINT = 0xFF0F766E.toInt() // deck 22B8A7: white on it was 2.5:1
    const val GOLD = 0xFFF4B63F.toInt()
    const val RED = 0xFFC52A30.toInt()
    const val BG = 0xFFF4F5FA.toInt()
    const val CARD = Color.WHITE
    const val TEXT = 0xFF161A33.toInt()
    const val MUTED = 0xFF5B6075.toInt() // ≥ 5.4:1 on every background here
    /** Gold is a fill only (INK text on it); this is gold as text. */
    const val GOLD_TEXT = 0xFF8A5A00.toInt()
    const val LINE = 0xFFE3E5EE.toInt()
    const val MINT_TINT = 0xFFE3F6F3.toInt()
    const val CORAL_TINT = 0xFFFFECE7.toInt()
    const val GOLD_TINT = 0xFFFDF3DC.toInt()
    const val RED_TINT = 0xFFFDE8E8.toInt()
}

/**
 * Small view kit for EchoFlow's screens (framework views only, no AndroidX): one place for
 * sizes, colours, cards, buttons and chips, so every screen looks the same.
 */
class Kit(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Number): Int = (v.toFloat() * density).toInt()

    fun rounded(color: Int, radiusDp: Float = 16f, strokeColor: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * density
        strokeColor?.let { setStroke((strokeDp * density).toInt().coerceAtLeast(1), it) }
    }

    /** A root for an activity: status bar in ink, a light background, scrolling content. */
    fun screen(activity: Activity, content: LinearLayout): View {
        activity.window.statusBarColor = Palette.INK
        activity.window.navigationBarColor = Palette.BG
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
            clipToPadding = false
            addView(content)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // Android 15 draws apps edge to edge: keep the content out from under the status and
        // navigation bars (an ink strip behind the status bar continues the header).
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.INK)
            addView(scroll)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                scroll.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }
    }

    fun column(padH: Int = 16, padV: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun row(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        views.forEach(::addView)
    }

    /** The ink header band at the top of a screen. */
    fun header(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.INK)
        setPadding(dp(20), dp(20), dp(20), dp(24))
        if (onBack != null) addView(text("‹ Back", 15f, Palette.GOLD).apply {
            // A full-size touch target, announced as a button.
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(16), dp(4))
            contentDescription = "Back"
            accessibilityDelegate = Kit.ROLE_BUTTON
            setOnClickListener { onBack() }
        })
        addView(text(title, 26f, Color.WHITE, bold = true).apply { isAccessibilityHeading = true })
        subtitle?.let { addView(text(it, 14f, 0xFFC9CCE0.toInt()).apply { setPadding(0, dp(6), 0, 0) }) }
    }

    fun text(value: CharSequence, sizeSp: Float = 15f, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    /** A section title; marked as a heading so TalkBack users can jump between sections. */
    fun heading(value: String) = text(value, 18f, bold = true).apply {
        setPadding(0, dp(20), 0, dp(8))
        isAccessibilityHeading = true
    }

    fun body(value: CharSequence) = text(value, 15f)

    fun caption(value: CharSequence) = text(value, 13f, Palette.MUTED)

    /** A white rounded card; add children to it. */
    fun card(tint: Int = Palette.CARD, block: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(tint, 18f, if (tint == Palette.CARD) Palette.LINE else null)
        elevation = dp(1).toFloat()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        block()
    }

    private fun pressable(bg: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null)

    /** A filled pill button (coral by default). */
    fun primaryButton(label: String, color: Int = Palette.CORAL, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = pressable(rounded(color, 28f))
        stateListAnimator = null
        minHeight = dp(52)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** An outlined pill button. */
    fun secondaryButton(label: String, color: Int = Palette.INK, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(color)
        background = pressable(rounded(Color.WHITE, 24f, color, 1.5f))
        stateListAnimator = null
        minHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** A small rounded label ("item", "Zomato", "6 steps"). Clickable if [onClick] is given. */
    fun chip(label: String, color: Int = Palette.MINT, tint: Int = Palette.MINT_TINT, onClick: (() -> Unit)? = null) = TextView(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (onClick != null) pressable(rounded(tint, 14f)) else rounded(tint, 14f)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
            topMargin = dp(6)
        }
        onClick?.let { c ->
            // Tappable chips get a full 48dp target and are announced as buttons.
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            accessibilityDelegate = ROLE_BUTTON
            setOnClickListener { c() }
        }
    }

    /** A round icon holder with an emoji or symbol. */
    fun iconCircle(symbol: String, tint: Int, sizeDp: Int = 40) = TextView(ctx).apply {
        text = symbol
        textSize = sizeDp * 0.45f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(12) }
    }

    /** Wrapping row of chips (simple flow layout: new rows every [perRow]). */
    fun chipRows(chips: List<View>, perRow: Int = 3) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        chips.chunked(perRow).forEach { addView(row(*it.toTypedArray(), gravity = Gravity.START)) }
    }

    fun space(heightDp: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    companion object {
        /** Makes TalkBack say "button" for a tappable TextView. */
        val ROLE_BUTTON = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    fun divider() = View(ctx).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        }
    }
}

/** Human-friendly text for flows and runs. */
object Words {
    /**
     * "order a {item} pizza from {restaurant} on zomato" → "Order a [item] pizza from [restaurant] on Zomato",
     * with each changeable part highlighted. [values] fills slots instead (the taught values).
     */
    fun template(template: String, values: Map<String, String> = emptyMap(), appLabel: String? = null): CharSequence {
        val out = SpannableStringBuilder()
        val parts = Regex("\\{(\\w+)\\}").split(template)
        val names = Regex("\\{(\\w+)\\}").findAll(template).map { it.groupValues[1] }.toList()
        parts.forEachIndexed { i, p ->
            out.append(if (i == 0) p.replaceFirstChar { it.uppercase() } else p)
            names.getOrNull(i)?.let { name ->
                val shown = values[name] ?: slotName(name)
                // "order a {item}" shown as a name reads "an item".
                if (shown.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u') && out.endsWith(" a ")) {
                    out.replace(out.length - 2, out.length - 1, "an")
                }
                val start = out.length
                out.append(" $shown ")
                out.setSpan(BackgroundColorSpan(Palette.MINT_TINT), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(Palette.MINT), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // App names read better capitalised ("on zomato" -> "on Zomato").
        appLabel?.let { label ->
            val at = out.toString().lowercase().lastIndexOf(label.lowercase())
            if (at >= 0) out.replace(at, at + label.length, label)
        }
        return out
    }

    fun slotName(name: String) = when (name) {
        "qty" -> "quantity"
        else -> name
    }

    /** A status line a person understands, with its colours (text, tint). */
    fun status(status: RunStatus): Triple<String, Int, Int> = when (status) {
        RunStatus.HANDED_OFF -> Triple("Ready for you to pay", Palette.MINT, Palette.MINT_TINT)
        RunStatus.COMPLETED -> Triple("Done", Palette.MINT, Palette.MINT_TINT)
        RunStatus.HALTED -> Triple("Stopped", Palette.RED, Palette.RED_TINT)
        RunStatus.NO_ANSWER -> Triple("Waiting for your answer", Palette.GOLD_TEXT, Palette.GOLD_TINT)
        RunStatus.CANCELLED -> Triple("Cancelled", Palette.MUTED, Palette.LINE)
    }
}
