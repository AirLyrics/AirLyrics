package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import java.text.NumberFormat

/** Keeps the count's layout fixed while the old and new glyphs briefly separate and settle. */
@SuppressLint("ViewConstructor")
internal class SavedLyricsSelectionCountView(
    context: Context,
    initialCount: Int,
    private val numberFormat: NumberFormat
) : AppCompatTextView(context) {
    var count: Int = initialCount
        private set
    private var outgoingText: String? = null
    private var fraction = 1f
    private var direction = 1f
    private var animator: ValueAnimator? = null

    init {
        text = numberFormat.format(initialCount)
    }

    fun setCount(value: Int, animate: Boolean) {
        if (value == count) return
        val previousCount = count
        val previousText = if (animator != null && fraction < 0.5f) {
            outgoingText ?: text.toString()
        } else {
            text.toString()
        }
        finishAnimation()
        count = value
        text = numberFormat.format(value)
        if (!animate || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) return

        outgoingText = previousText
        direction = if (value >= previousCount) 1f else -1f
        fraction = 0f
        val next = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L
            interpolator = PathInterpolator(0.25f, 0f, 0.2f, 1f)
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    outgoingText = null
                    fraction = 1f
                    invalidate()
                }
            })
        }
        animator = next
        next.start()
    }

    fun finishAnimation() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        outgoingText = null
        fraction = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        finishAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val oldText = outgoingText ?: return super.onDraw(canvas)
        val baseline = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val originalAlpha = paint.alpha
        drawNumber(canvas, oldText, baseline, incoming = false, originalAlpha = originalAlpha)
        drawNumber(canvas, text.toString(), baseline, incoming = true, originalAlpha = originalAlpha)
        paint.alpha = originalAlpha
    }

    private fun drawNumber(
        canvas: Canvas,
        value: String,
        baseline: Float,
        incoming: Boolean,
        originalAlpha: Int
    ) {
        val glyphs = value.map(Char::toString)
        val widths = glyphs.map { paint.measureText(it) }
        var x = (width - widths.sum()) / 2f
        val distance = 7f * resources.displayMetrics.density
        val split = 2f * resources.displayMetrics.density
        for (index in glyphs.indices) {
            val delay = (glyphs.size - index - 1) * 0.035f
            val progress = ((fraction - delay) / (1f - delay)).coerceIn(0f, 1f)
            val opacity = if (incoming) {
                ((progress - 0.12f) / 0.76f).coerceIn(0f, 1f)
            } else {
                (1f - progress / 0.82f).coerceIn(0f, 1f)
            }
            if (opacity > 0f) {
                paint.alpha = (originalAlpha * opacity).toInt()
                val remaining = if (incoming) 1f - progress else progress
                val vertical = direction * distance * remaining * if (incoming) 1f else -1f
                val scale = if (incoming) 1f + 0.07f * remaining else 1f - 0.04f * progress
                drawSplitGlyph(canvas, glyphs[index], x + widths[index] / 2f,
                    baseline + vertical, widths[index], scale, split * remaining)
            }
            x += widths[index]
        }
    }

    private fun drawSplitGlyph(
        canvas: Canvas,
        glyph: String,
        centerX: Float,
        baseline: Float,
        glyphWidth: Float,
        scale: Float,
        split: Float
    ) {
        canvas.withSave {
            translate(centerX, baseline)
            scale(scale, scale)
            val fm = paint.fontMetrics
            for (half in 0..1) {
                val topHalf = half == 0
                withClip(-glyphWidth / 2f - 2f, if (topHalf) fm.top - 2f else 0f,
                    glyphWidth / 2f + 2f, if (topHalf) 0f else fm.bottom + 2f) {
                    translate(0f, if (topHalf) -split else split)
                    drawText(glyph, -glyphWidth / 2f, 0f, paint)
                }
            }
        }
    }
}
