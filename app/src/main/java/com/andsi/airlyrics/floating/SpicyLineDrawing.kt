package com.andsi.airlyrics.floating

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import com.andsi.airlyrics.lyrics.display.FormattedPlainLyrics
import kotlin.math.abs
import kotlin.math.min

/** Original and translation are separate paragraphs with a shared source time window. */
internal data class SpicyLineTiming(
    val lineIndex: Int, val partIndex: Int, val startMs: Long, val endMs: Long
)

internal class SpicyLineSpan(val timing: SpicyLineTiming, val highlight: Int, val animated: Boolean = true) : CharacterStyle(), UpdateAppearance {
    var hidden = false
    override fun updateDrawState(tp: TextPaint) {
        if (hidden) { tp.alpha = 0; tp.clearShadowLayer() }
    }
}

internal fun spicyLineText(formatted: FormattedPlainLyrics, highlight: Int, animated: Boolean = true): CharSequence =
    SpannableString(formatted.text).apply {
        for (range in formatted.ranges) {
            setSpan(
                SpicyLineSpan(SpicyLineTiming(range.lineIndex, range.partIndex, range.startMs, range.endMs), highlight, animated),
                range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

/**
 * Spicy's normal Line mode: 200ms 1→1.05 activation, a vertical 20% soft edge
 * over the whole wrapped paragraph, and a (0,0),(.5,1),(1,0) glow spline.
 * Reference: spicylyrics/mobile 301f97620bb374ecc38401f186b52afff1a806a2,
 * LyricsAnimator.kt and LyricsRenderer.kt. RTL retains the reference's horizontal wipe.
 *
 * Layout and shaping are cached. Frame updates change only shaders and drawing transforms.
 */
internal class SpicyLineDrawing(private val view: FloatingLyricsTextView) {
    private inner class Run(
        val start: Int, val end: Int, val contextStart: Int, val contextEnd: Int,
        val x: Float, val baseline: Float, val width: Float, val rtl: Boolean,
        val paint: TextPaint, highlight: Int
    ) {
        val baseColor = paint.color
        val alpha = maxOf(Color.alpha(baseColor), Color.alpha(highlight))
        private fun normalized(color: Int): Int = (color and 0x00ffffff) or
            ((if (alpha == 0) 0 else Color.alpha(color) * 255 / alpha) shl 24)
        private val bright = normalized(highlight)
        private val dim = normalized((paint.color and 0x00ffffff) or ((Color.alpha(paint.color) * .35f).toInt() shl 24))
        val matrix = Matrix()
        lateinit var gradient: LinearGradient
        val halo = LyricsHalo(view, paint, highlight)

        init { paint.alpha = alpha }

        fun configure(height: Float, width: Float, rtl: Boolean) {
            gradient = if (rtl) LinearGradient(0f, 0f, -width * .2f, 0f, bright, dim, Shader.TileMode.CLAMP)
                else LinearGradient(0f, 0f, 0f, height * .2f, bright, dim, Shader.TileMode.CLAMP)
            paint.shader = gradient
            paint.clearShadowLayer()
        }
    }

    private class Group(
        val span: SpicyLineSpan, val top: Float, val bottom: Float,
        val left: Float, val right: Float, val rtl: Boolean, val runs: List<Run>,
        val glow: SpicySpring
    )

    private var cachedLayout: Layout? = null
    private var cachedText: CharSequence? = null
    private var groups = emptyList<Group>()
    private var lastPosition: Long? = null
    private var lastFrame = 0L

    fun reset() {
        for (group in groups) group.span.hidden = false
        groups = emptyList()
        cachedLayout = null
        cachedText = null
        lastPosition = null
        lastFrame = 0L
    }

    fun prepare() {
        val layout = view.layout ?: return
        val text = view.text as? Spanned ?: return
        if (layout === cachedLayout && text === cachedText) return
        val previous = groups.associateBy { it.span.timing }
        for (group in groups) group.span.hidden = false
        val built = mutableListOf<Group>()
        val selectionPath = android.graphics.Path()
        val selectionBounds = android.graphics.RectF()
        val maxLines = view.maxLines.takeIf { it >= 0 } ?: Int.MAX_VALUE
        for (span in text.getSpans(0, text.length, SpicyLineSpan::class.java)) {
            span.hidden = false
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val first = layout.getLineForOffset(start)
            val last = layout.getLineForOffset(end - 1)
            // Keep native ellipsis and constrained-line fallback readable.
            if ((first..last).any { it >= maxLines || layout.getEllipsisCount(it) > 0 }) continue
            val runs = mutableListOf<Run>()
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (row in first..last) {
                var from = maxOf(start, layout.getLineStart(row))
                val to = min(end, layout.getLineVisibleEnd(row))
                while (from < to) {
                    val rtl = layout.isRtlCharAt(from)
                    var runEnd = from + 1
                    // Equal direction can still cross embedding levels (digits inside RTL).
                    // Split at dual caret positions as well as direction changes.
                    while (runEnd < to && layout.isRtlCharAt(runEnd) == rtl &&
                        layout.getPrimaryHorizontal(runEnd) == layout.getSecondaryHorizontal(runEnd)) runEnd++
                    // A bidi boundary has two caret positions. Native selection geometry
                    // resolves the actual visual run, including nested numbers inside RTL text.
                    selectionPath.reset()
                    layout.getSelectionPath(from, runEnd, selectionPath)
                    selectionPath.computeBounds(selectionBounds, true)
                    val paint = TextPaint(view.paint).apply { color = view.currentTextColor }
                    for (style in text.getSpans(from, runEnd, CharacterStyle::class.java)) {
                        if (style !is SpicyLineSpan) style.updateDrawState(paint)
                    }
                    val x = selectionBounds.left
                    val width = selectionBounds.width()
                    left = minOf(left, x)
                    right = maxOf(right, x + width)
                    val opacity = if (Color.alpha(view.currentTextColor) == 0) 0f else
                        Color.alpha(paint.color).toFloat() / Color.alpha(view.currentTextColor)
                    val highlight = (span.highlight and 0x00ffffff) or
                        ((Color.alpha(span.highlight) * opacity).toInt().coerceIn(0, 255) shl 24)
                    runs += Run(from, runEnd, layout.getLineStart(row), layout.getLineEnd(row),
                        x, layout.getLineBaseline(row).toFloat(), width, rtl, paint, highlight)
                    from = runEnd
                }
            }
            if (runs.isEmpty()) continue
            val top = layout.getLineTop(first).toFloat()
            val bottom = layout.getLineBottom(last).toFloat()
            val rtl = layout.getParagraphDirection(first) < 0
            runs.forEach { it.configure((bottom - top).coerceAtLeast(1f), (right - left).coerceAtLeast(1f), rtl) }
            built += Group(span, top, bottom, left, right, rtl, runs,
                previous[span.timing]?.glow ?: SpicySpring(1.0, .5, 0f))
            span.hidden = span.animated
        }
        groups = built
        cachedLayout = layout
        cachedText = text
    }

    fun draw(canvas: Canvas, position: Long, now: Long) {
        prepare()
        val text = view.text as? Spanned ?: return
        val last = lastPosition
        val seek = last == null || position < last || abs(position - last) > 250
        val delta = if (lastFrame == 0L || position == last) 0.0
            else ((now - lastFrame) / 1000.0).coerceIn(0.0, .05)
        val motion = ValueAnimator.areAnimatorsEnabled()
        for (group in groups) {
            val timing = group.span.timing
            val active = position >= timing.startMs && position < timing.endMs
            val progress = spicyProgress(position, timing.startMs, timing.endMs)
            val targetGlow = if (!active) 0f else if (motion) SpicyLyricsCurves.lineGlow(progress) else .35f
            if (seek || !motion) group.glow.reset(targetGlow)
            val glow = if (active) group.glow.step(targetGlow, delta).coerceIn(0f, 1f) else 0f
            val scale = if (motion && group.span.animated) lineScale(position, timing.startMs, timing.endMs) else 1f
            // Use the native paragraph alignment as the scale origin.
            val alignment = view.layout.getParagraphAlignment(view.layout.getLineForOffset(text.getSpanStart(group.span)))
            val pivotX = when (alignment) {
                Layout.Alignment.ALIGN_CENTER -> (group.left + group.right) / 2f
                Layout.Alignment.ALIGN_OPPOSITE -> if (group.rtl) group.left else group.right
                else -> if (group.rtl) group.right else group.left
            }
            canvas.withSave {
                scale(scale, scale, pivotX, (group.top + group.bottom) / 2f)
                for (run in group.runs) {
                    if (group.rtl) run.matrix.setTranslate(group.right - progress * (group.right - group.left), 0f)
                    else run.matrix.setTranslate(0f, group.top + progress * (group.bottom - group.top))
                    run.gradient.setLocalMatrix(run.matrix)
                    // Neighbors stay subdued; the wipe belongs only to the active source sentence.
                    run.paint.alpha = run.alpha
                    run.paint.shader = if (active) run.gradient else null
                    if (!active) {
                        run.paint.color = run.baseColor
                        run.paint.alpha = (Color.alpha(run.baseColor) * .35f).toInt()
                    }
                    drawHalo(this, text, run, glow, group.span.animated)
                    if (group.span.animated) drawRun(this, text, run, run.paint)
                }
            }
        }
        lastPosition = position
        lastFrame = now
    }

    private fun drawHalo(canvas: Canvas, text: CharSequence, run: Run, strength: Float, includeUserShadow: Boolean) {
        run.halo.draw(canvas, text, run.start, run.end, run.contextStart, run.contextEnd,
            run.x, run.baseline, run.rtl, run.width, strength, view.lyricsGlowStyle, includeUserShadow)
    }

    private fun drawRun(canvas: Canvas, text: CharSequence, run: Run, paint: TextPaint) {
        canvas.drawTextRun(text, run.start, run.end, run.contextStart, run.contextEnd,
            run.x, run.baseline, run.rtl, paint)
    }

    internal companion object {
        private val scaleEasing = PathInterpolator(.37f, 0f, .63f, 1f)
        fun lineScale(position: Long, startMs: Long, endMs: Long): Float {
            val entering = spicyProgress(position, startMs, startMs + 200)
            val leaving = spicyProgress(position, endMs, endMs + 200)
            return 1f + .05f * scaleEasing.getInterpolation(entering) * (1f - scaleEasing.getInterpolation(leaving))
        }
    }
}
