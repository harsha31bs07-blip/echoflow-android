package com.echoflow.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * EchoFlow's small line icons, drawn in code so they stay crisp at any size and share one style
 * (rounded caps and joins): sound waves for speaking, stop, home, move, close and a check.
 */
class Glyph(private val kind: Kind, color: Int, private val sizePx: Int) : Drawable() {
    enum class Kind { WAVES, STOP, HOME, MOVE, CLOSE, CHECK }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val l = b.exactCenterX() - s / 2
        val t = b.exactCenterY() - s / 2
        line.strokeWidth = s * 0.11f
        when (kind) {
            Kind.WAVES -> {
                // Five rounded bars, tallest in the middle: a voice wave.
                val heights = floatArrayOf(0.34f, 0.66f, 1f, 0.66f, 0.34f)
                val w = s * 0.12f
                val gap = s * 0.07f
                var x = l + (s - (5 * w + 4 * gap)) / 2
                val cy = t + s / 2
                heights.forEach { h ->
                    val half = s * 0.46f * h
                    rect.set(x, cy - half, x + w, cy + half)
                    canvas.drawRoundRect(rect, w / 2, w / 2, fill)
                    x += w + gap
                }
            }
            Kind.STOP -> {
                val side = s * 0.44f
                rect.set(l + (s - side) / 2, t + (s - side) / 2, l + (s + side) / 2, t + (s + side) / 2)
                canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, fill)
            }
            Kind.HOME -> {
                line.strokeWidth = s * 0.09f
                path.reset()
                path.moveTo(l + s * 0.18f, t + s * 0.48f)
                path.lineTo(l + s * 0.5f, t + s * 0.2f)
                path.lineTo(l + s * 0.82f, t + s * 0.48f)
                path.moveTo(l + s * 0.28f, t + s * 0.42f)
                path.lineTo(l + s * 0.28f, t + s * 0.8f)
                path.lineTo(l + s * 0.72f, t + s * 0.8f)
                path.lineTo(l + s * 0.72f, t + s * 0.42f)
                canvas.drawPath(path, line)
            }
            Kind.MOVE -> {
                line.strokeWidth = s * 0.09f
                path.reset()
                // Up arrow (left) and down arrow (right).
                val a = l + s * 0.36f
                val c = l + s * 0.64f
                path.moveTo(a, t + s * 0.78f); path.lineTo(a, t + s * 0.22f)
                path.moveTo(a - s * 0.13f, t + s * 0.35f); path.lineTo(a, t + s * 0.22f); path.lineTo(a + s * 0.13f, t + s * 0.35f)
                path.moveTo(c, t + s * 0.22f); path.lineTo(c, t + s * 0.78f)
                path.moveTo(c - s * 0.13f, t + s * 0.65f); path.lineTo(c, t + s * 0.78f); path.lineTo(c + s * 0.13f, t + s * 0.65f)
                canvas.drawPath(path, line)
            }
            Kind.CLOSE -> {
                line.strokeWidth = s * 0.1f
                val p = s * 0.3f
                canvas.drawLine(l + p, t + p, l + s - p, t + s - p, line)
                canvas.drawLine(l + s - p, t + p, l + p, t + s - p, line)
            }
            Kind.CHECK -> {
                line.strokeWidth = s * 0.12f
                path.reset()
                path.moveTo(l + s * 0.2f, t + s * 0.52f)
                path.lineTo(l + s * 0.42f, t + s * 0.72f)
                path.lineTo(l + s * 0.8f, t + s * 0.3f)
                canvas.drawPath(path, line)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        line.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        line.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
