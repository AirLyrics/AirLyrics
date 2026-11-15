package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Port of geometry(), panelContour(), draw() and animateTo() in the confirmed HTML.
 * Coordinates come from measured Views; only the prototype's dimensionless ratios remain.
 */
internal object AppearancePanelMotion {
    fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun travel(progress: Float): Float {
        val remaining = 1f - progress.coerceIn(0f, 1f)
        return 1f - remaining * remaining * remaining
    }

    fun contentAlpha(progress: Float) = smooth((progress - 0.48f) / 0.37f)

    fun duration(from: Float, target: Float, expandMs: Long, collapseMs: Long): Long =
        ((if (target > from) expandMs else collapseMs) * max(0.3f, abs(target - from))).toLong()

    data class Geometry(
        val anchorX: Float,
        val anchorY: Float,
        val buttonWidth: Float,
        val buttonHeight: Float,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val radius: Float
    )

    data class Contour(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val radius: Float, val crest: Float, val wobble: Float
    )

    fun contour(g: Geometry, progress: Float): Contour {
        val p = progress.coerceIn(0f, 1f)
        val travel = travel(p)
        val spread = smooth((p - 0.03f) / 0.71f)
        fun mix(from: Float, to: Float, t: Float) = from + (to - from) * t
        val unit = g.buttonHeight / 40f
        val w = mix(g.buttonWidth * 35f / 40f, g.width, spread)
        val h = mix(g.buttonHeight * 30f / 40f, g.height, travel((p - 0.04f) / 0.85f))
        val cx = mix(g.anchorX, g.left + g.width / 2f, travel)
        val cy = mix(g.anchorY + 8f * unit, g.top + g.height / 2f, travel)
        val tension = sin(PI * ((p - 0.08f) / 0.72f).coerceIn(0f, 1f)).toFloat()
        val crest = if (p < 0.82f) max(0f, tension) * 6.5f else
            sin((p - 0.82f) / 0.18f * PI).toFloat() * 0.8f
        val wobble = sin(p * PI * 2).toFloat() * sin(p * PI).toFloat() * 1.3f
        return Contour(
            cx - w / 2f, cy - h / 2f, w, h,
            min(mix(g.buttonHeight * 18f / 40f, g.radius, spread), min(h, w) / 2f),
            crest * unit, wobble * unit
        )
    }
}

/** Only the surface deforms. Native text and controls are drawn separately, without blur. */
internal class AppearancePanelSurface {
    private val panel = Path()
    private val neck = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun draw(
        canvas: Canvas,
        g: AppearancePanelMotion.Geometry,
        progress: Float,
        fillColor: Int,
        strokeColor: Int,
        strokeWidth: Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p == 0f || g.width <= 0f || g.height <= 0f) return
        val c = AppearancePanelMotion.contour(g, p)
        contourPath(panel, g, c)
        fill.color = fillColor
        // A short, pinching neck. Drawing the union avoids an internal seam.
        neck.reset()
        if (p > 0.025f && p < 0.61f) {
            val unit = g.buttonHeight / 40f
            val neckWidth = 11f * (1f - AppearancePanelMotion.smooth((p - 0.12f) / 0.49f)) * unit
            val fromY = g.anchorY + 12f * unit
            val toY = c.y - c.crest + 5f * unit
            val midY = (fromY + toY) / 2f
            val a = clamp(g.anchorX, c.x + c.radius + 3f * unit, c.x + c.width - c.radius - 3f * unit)
            val base = max(3f * unit, neckWidth * 0.8f)
            if (neckWidth > 0.3f * unit && toY > fromY - 2f * unit) {
                neck.moveTo(g.anchorX - g.buttonWidth / 4f, fromY)
                neck.cubicTo(g.anchorX - neckWidth * 0.22f, midY, a - base * 0.2f, midY, a - base, toY)
                neck.lineTo(a + base, toY)
                neck.cubicTo(a + base * 0.2f, midY, g.anchorX + neckWidth * 0.22f, midY, g.anchorX + g.buttonWidth / 4f, fromY)
                neck.close()
                neck.op(panel, Path.Op.UNION)
                canvas.drawPath(neck, fill)
            } else canvas.drawPath(panel, fill)
        } else canvas.drawPath(panel, fill)
        if (p > 0.62f) {
            stroke.color = strokeColor
            stroke.alpha = (android.graphics.Color.alpha(strokeColor) *
                AppearancePanelMotion.smooth((p - 0.62f) / 0.38f)).toInt()
            stroke.strokeWidth = strokeWidth
            canvas.drawPath(panel, stroke)
        }
    }

    fun clipContent(canvas: Canvas, g: AppearancePanelMotion.Geometry, progress: Float) {
        contourPath(panel, g, AppearancePanelMotion.contour(g, progress))
        canvas.clipPath(panel)
    }

    private fun contourPath(path: Path, g: AppearancePanelMotion.Geometry, c: AppearancePanelMotion.Contour) {
        val (x, y, w, h, r, crest, wobble) = c
        val right = x + w
        val bottom = y + h
        val unit = g.buttonWidth / 40f
        val a = clamp(g.anchorX, x + r + 5f * unit, right - r - 5f * unit)
        val half = min(19f * unit, max(3f * unit, (w - 2f * r) / 4f))
        val lo = max(x + r, a - half)
        val hi = min(right - r, a + half)
        path.rewind()
        path.moveTo(x + r, y)
        path.lineTo(lo, y)
        path.cubicTo(a - half * 0.42f, y, a - half * 0.37f, y - crest, a, y - crest)
        path.cubicTo(a + half * 0.37f, y - crest, a + half * 0.42f, y, hi, y)
        path.lineTo(right - r, y)
        path.quadTo(right, y, right + wobble, y + r)
        path.lineTo(right, bottom - r)
        path.quadTo(right, bottom, right - r, bottom)
        path.lineTo(x + r, bottom)
        path.quadTo(x, bottom, x - wobble, bottom - r)
        path.lineTo(x, y + r)
        path.quadTo(x, y, x + r, y)
        path.close()
    }

    // Like the HTML's clamp, remains defined when the tiny initial contour has no flat edge.
    private fun clamp(value: Float, low: Float, high: Float) = max(low, min(high, value))
}
