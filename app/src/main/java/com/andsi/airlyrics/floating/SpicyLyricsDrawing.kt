package com.andsi.airlyrics.floating

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import androidx.core.graphics.withSave
import kotlin.math.abs
import kotlin.math.min

/** Hides only the timed ink from TextView; layout, selection and accessibility remain native. */
internal class SpicyLyricsSpan(val timing: SpicyTiming, val highlight: Int) : CharacterStyle(), UpdateAppearance {
    var hidden = false
    override fun updateDrawState(tp: TextPaint) { if (hidden) { tp.alpha = 0; tp.clearShadowLayer() } }
}

/** Cached geometry and shaped drawing runs. Nothing here measures or replaces text per frame. */
internal class SpicyLyricsDrawing(private val view: FloatingLyricsTextView) {
    private class Run(
        val start: Int, val end: Int, val contextStart: Int, val contextEnd: Int,
        val x: Float, val baseline: Float, val width: Float, val rtl: Boolean,
        val offset: Float, var totalWidth: Float, val span: SpicyLyricsSpan, val paint: TextPaint
    ) {
        val matrix = Matrix()
        var edge = 0f
            private set
        private val baseColor = (paint.color and 0x00ffffff) or ((Color.alpha(paint.color) / 2) shl 24)
        // Shader alpha is multiplied by Paint alpha. Normalize once so font opacity is
        // applied once, while color emoji still receive the same overall paint opacity.
        private val alpha = maxOf(Color.alpha(baseColor), Color.alpha(span.highlight))
        private fun normalized(color: Int): Int = (color and 0x00ffffff) or
            ((if (alpha == 0) 0 else Color.alpha(color) * 255 / alpha) shl 24)
        private val bright = normalized(span.highlight)
        private val dim = normalized(baseColor)
        lateinit var gradient: LinearGradient
            private set
        fun configureWidth(width: Float) {
            totalWidth = width
            // Normal-mode CSS uses a fade spanning 20% of the word/letter width.
            edge = (width * .2f).coerceAtLeast(.5f)
            gradient = LinearGradient(0f, 0f, edge, 0f, bright, dim, Shader.TileMode.CLAMP)
        }
        init { paint.alpha = alpha }
        val scale = SpicySpring(.88, .64, .95f)
        val lift = SpicySpring(1.45, .4, .01f)
        val glow = SpicySpring(1.18, .56, 0f)
        val target = SpicyLyricsCurves.Pose()
        val haloPaint = TextPaint(paint).apply { shader = null; color = Color.WHITE; clearShadowLayer() }
        val cutoutPaint = TextPaint(haloPaint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        val inkTop = paint.fontMetrics.top
        val inkBottom = paint.fontMetrics.bottom
    }
    private var cachedLayout: Layout? = null
    private var cachedText: CharSequence? = null
    private var runs = emptyList<Run>()
    private var lastPosition: Long? = null
    private var lastFrame = 0L

    fun reset() {
        for (run in runs) run.span.hidden = false
        cachedLayout = null; cachedText = null; runs = emptyList(); lastPosition = null
    }

    private fun rebuild(layout: Layout, text: Spanned) {
        val built = mutableListOf<Run>()
        // TextView reports -1 when height is constrained in pixels (as in the overlay).
        // In that mode there is no line-count limit; the window clips to its pixel height.
        val maxLines = view.maxLines.takeIf { it >= 0 } ?: Int.MAX_VALUE
        for (span in text.getSpans(0, text.length, SpicyLyricsSpan::class.java)) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            span.hidden = false
            if ((layout.getLineForOffset(start)..layout.getLineForOffset((end - 1).coerceAtLeast(start))).any {
                it >= maxLines || layout.getEllipsisCount(it) > 0
            }) continue
            span.hidden = true
            val pieces = mutableListOf<Run>()
            var offset = 0f
            var from = start
            while (from < end) {
                val line = layout.getLineForOffset(from)
                if (line >= maxLines || layout.getEllipsisCount(line) > 0) break
                val to = min(end, layout.getLineEnd(line))
                if (to <= from) break
                val rtl = layout.isRtlCharAt(from)
                // A bidi boundary is a shaping boundary; never draw through the next run.
                var runEnd = from + 1
                while (runEnd < to && layout.isRtlCharAt(runEnd) == rtl) runEnd++
                val a = layout.getPrimaryHorizontal(from)
                val b = if (runEnd == layout.getLineEnd(line)) {
                    if (rtl) layout.getLineLeft(line) else layout.getLineRight(line)
                } else layout.getPrimaryHorizontal(runEnd)
                val width = abs(b - a)
                val paint = TextPaint(view.paint)
                paint.color = view.currentTextColor
                for (style in text.getSpans(from, runEnd, CharacterStyle::class.java)) {
                    if (style !is SpicyLyricsSpan) style.updateDrawState(paint)
                }
                pieces += Run(from, runEnd, layout.getLineStart(line), layout.getLineEnd(line), min(a, b),
                    layout.getLineBaseline(line).toFloat(), width, rtl, offset, 0f, span, paint)
                offset += width
                from = runEnd
            }
            for (piece in pieces) {
                piece.configureWidth(offset)
                built += piece
            }
        }
        runs = built
        cachedLayout = layout
        cachedText = text
    }

    fun prepare() {
        val layout = view.layout ?: return
        val text = view.text as? Spanned ?: return
        if (layout !== cachedLayout || text !== cachedText) rebuild(layout, text)
    }

    fun draw(canvas: Canvas, position: Long, now: Long) {
        val layout = view.layout ?: return
        val text = view.text as? Spanned ?: return
        if (layout !== cachedLayout || text !== cachedText) rebuild(layout, text)
        val delta = if (lastFrame == 0L || position == lastPosition) 0.0 else ((now - lastFrame) / 1000.0).coerceIn(0.0, .05)
        val seek = lastPosition == null || position < (lastPosition ?: position) || abs(position - (lastPosition ?: position)) > 250
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        for (run in runs) {
            val timing = run.span.timing
            val p = spicyProgress(position, timing.startMs, timing.endMs)
            val motion = motionEnabled && timing.motion
            SpicyLyricsCurves.targets(timing, position, run.target)
            val target = run.target
            if (seek || !motion) {
                run.scale.reset(target.scale)
                run.lift.reset(target.lift)
                run.glow.reset(target.glow)
            }
            val scale = if (motion) run.scale.step(target.scale, delta)
                .coerceAtMost(1f + run.paint.textSize * .35f / run.width.coerceAtLeast(1f)) else 1f
            val lift = if (motion) run.lift.step(target.lift, delta) * run.paint.textSize *
                (if (timing.emphasized) 2f else 1f) else 0f
            val springGlow = if (motion) run.glow.step(target.glow, delta).coerceIn(0f, 1f) else 0f
            // Keep spring smoothing, but do not let a short word develop a new glow peak
            // after its fill has finished. Emphasis follows the active letter's proximity.
            val glow = when {
                position >= timing.groupEndMs -> 0f
                !timing.emphasized && p >= .6f -> min(springGlow, target.glow)
                else -> springGlow
            }
            // The front travels over visual width, including across wrapped pieces of a word.
            val front = SpicyLyricsCurves.fillProgress(timing, position) * (run.totalWidth + run.edge) - run.edge - run.offset
            if (run.rtl) {
                run.matrix.setScale(-1f, 1f)
                run.matrix.postTranslate(run.x + run.width - front, 0f)
            } else run.matrix.setTranslate(run.x + front, 0f)
            run.gradient.setLocalMatrix(run.matrix)
            run.paint.shader = run.gradient
            run.paint.clearShadowLayer()
            canvas.withSave {
                translate(0f, lift)
                scale(scale, scale, run.x + run.width / 2f, run.baseline)
                drawHalo(this, text, run, glow)
                drawTextRun(text, run.start, run.end, run.contextStart, run.contextEnd,
                    run.x, run.baseline, run.rtl, run.paint)
            }
            run.paint.clearShadowLayer()
        }
        lastPosition = position
        lastFrame = now
    }
    /** Isolate the halo so it never replaces the user's shadow or paints the ink twice. */
    private fun drawHalo(canvas: Canvas, text: CharSequence, run: Run, strength: Float) {
        val opacity = SpicyLyricsCurves.glowOpacity(run.span.timing.emphasized, strength)
        val alpha = (Color.alpha(run.span.highlight) * opacity).toInt()
        val userShadow = view.shadowRadius > 0f && Color.alpha(view.shadowColor) > 0
        if (alpha == 0 && !userShadow) return
        val radius = SpicyLyricsCurves.glowRadius(run.paint.textSize, run.span.timing.emphasized, strength)
        val margin = maxOf(radius, view.shadowRadius) * 3f + run.paint.textSize * .2f +
            maxOf(abs(view.shadowDx), abs(view.shadowDy))
        val layer = canvas.saveLayer(run.x - margin, run.baseline + run.inkTop - margin,
            run.x + run.width + margin, run.baseline + run.inkBottom + margin, null)
        if (userShadow) {
            run.haloPaint.setShadowLayer(view.shadowRadius, view.shadowDx, view.shadowDy, view.shadowColor)
            canvas.drawTextRun(text, run.start, run.end, run.contextStart, run.contextEnd,
                run.x, run.baseline, run.rtl, run.haloPaint)
        }
        if (alpha > 0) {
            run.haloPaint.setShadowLayer(radius, 0f, 0f, (run.span.highlight and 0x00ffffff) or (alpha shl 24))
            canvas.drawTextRun(text, run.start, run.end, run.contextStart, run.contextEnd,
                run.x, run.baseline, run.rtl, run.haloPaint)
        }
        canvas.drawTextRun(text, run.start, run.end, run.contextStart, run.contextEnd,
            run.x, run.baseline, run.rtl, run.cutoutPaint)
        canvas.restoreToCount(layer)
    }
}
