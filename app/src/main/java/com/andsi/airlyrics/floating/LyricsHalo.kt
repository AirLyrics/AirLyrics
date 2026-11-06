package com.andsi.airlyrics.floating

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.text.TextPaint
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import kotlin.math.abs

/** Cached paints shared by every highlighting mode. Only shadow parameters change per frame. */
internal class LyricsHalo(private val view: TextView, paint: TextPaint, private val highlight: Int) {
    private val shadowPaint = TextPaint(paint).apply { shader = null; color = Color.WHITE; clearShadowLayer() }
    private val cutout = TextPaint(shadowPaint).apply {
        alpha = 255
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val inkTop = paint.fontMetrics.top
    private val inkBottom = paint.fontMetrics.bottom
    private val fontSize = paint.textSize
    // Preserve hue while giving dark accent colors a visible light source.
    private val light = ColorUtils.blendARGB(highlight or (0xff shl 24), Color.WHITE, .65f) and 0x00ffffff

    fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, contextStart: Int, contextEnd: Int,
        x: Float, baseline: Float, rtl: Boolean, width: Float, envelope: Float,
        style: LyricsGlowStyle, includeUserShadow: Boolean = true
    ) {
        val strength = if (style.enabled) (envelope * style.strengthPercent / 100f).coerceIn(0f, 2f) else 0f
        val alpha = (Color.alpha(highlight) * strength * .8f).toInt().coerceIn(0, 255)
        val userShadow = includeUserShadow && view.shadowRadius > 0f && Color.alpha(view.shadowColor) > 0
        if (alpha == 0 && !userShadow) return
        val radius = radius(fontSize, style)
        val margin = maxOf(radius, view.shadowRadius) * 3f + fontSize * .15f +
            maxOf(abs(view.shadowDx), abs(view.shadowDy))
        val save = canvas.saveLayer(x - margin, baseline + inkTop - margin,
            x + width + margin, baseline + inkBottom + margin, null)
        if (userShadow) {
            shadowPaint.setShadowLayer(view.shadowRadius, view.shadowDx, view.shadowDy, view.shadowColor)
            canvas.drawTextRun(text, start, end, contextStart, contextEnd, x, baseline, rtl, shadowPaint)
        }
        if (alpha > 0) {
            shadowPaint.setShadowLayer(radius, 0f, 0f, light or (alpha shl 24))
            canvas.drawTextRun(text, start, end, contextStart, contextEnd, x, baseline, rtl, shadowPaint)
            // A soft broad halo plus a compact bright core stays legible at small overlay sizes.
            shadowPaint.setShadowLayer(radius * .4f, 0f, 0f, light or ((alpha / 2) shl 24))
            canvas.drawTextRun(text, start, end, contextStart, contextEnd, x, baseline, rtl, shadowPaint)
        }
        canvas.drawTextRun(text, start, end, contextStart, contextEnd, x, baseline, rtl, cutout)
        canvas.restoreToCount(save)
    }

    companion object {
        private fun radius(fontSize: Float, style: LyricsGlowStyle): Float =
            fontSize * .18f * style.spreadPercent / 100f

        fun outset(fontSize: Float, style: LyricsGlowStyle): Int =
            if (style.enabled && style.strengthPercent > 0) kotlin.math.ceil(radius(fontSize, style) * 3f).toInt() else 0
    }
}
