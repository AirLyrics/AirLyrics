package com.andsi.airlyrics.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.Gravity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withSave
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import kotlin.math.roundToInt

/**
 * Lets the renderer animate lyric glyphs without transforming the floating bubble itself.
 *
 * A TextView draws its background before [onDraw], so applying the transition only while
 * delegating to TextView's content drawing keeps the background fully visible and stationary.
 */
internal class FloatingLyricsTextView @JvmOverloads constructor(
    context: Context,
    private val preview: Boolean = false
) : AppCompatTextView(context),
    LyricsTextAnimationTarget {

    internal var lyricsGlowStyle = LyricsGlowStyle()
        private set

    internal fun updateLyricsGlow(style: LyricsGlowStyle) {
        val normalized = style.normalized()
        if (lyricsGlowStyle == normalized) return
        val previousInset = spicyInset()
        lyricsGlowStyle = normalized
        if (previousInset != spicyInset()) requestLayout()
        invalidate()
    }

    internal var previewEffectsVisible = !preview
        private set
    internal var onPreviewVisibilityChanged: ((Boolean) -> Unit)? = null

    internal fun syncPreviewVisibility(visible: Boolean) {
        if (!preview || previewEffectsVisible == visible) return
        previewEffectsVisible = visible
        onPreviewVisibilityChanged?.invoke(visible)
        // Use the same clean restart for folding and scrolling out of the viewport.
        spicyDrawing.reset()
        spicyLineDrawing.reset()
        cancelLyricsTextAnimation()
        setLyricsTextAnimationState(REST_ALPHA, 0f, REST_SCALE, REST_SCALE)
        updateSpicyPlayback(spicyPosition, spicyPlaying)
    }

    private val spicyDrawing = SpicyLyricsDrawing(this)
    private val spicyLineDrawing = SpicyLineDrawing(this)
    private var spicyLineActive = false
    private var lineScaleInset = 0
    private val spicyBasePaint = android.text.TextPaint()
    private var spicyPosition: (() -> Long)? = null
    private var spicyPlaying = false
    private var spicyRequestedPosition: Long? = null
    private val spicyVisibleRect = android.graphics.Rect()
    private val spicyFrame = object : Runnable {
        override fun run() {
            if (!previewEffectsVisible || spicyPosition == null || !spicyPlaying || !isShown || windowVisibility != VISIBLE || !isAttachedToWindow) return
            if (getLocalVisibleRect(spicyVisibleRect)) {
                invalidate()
                postOnAnimation(this)
            } else {
                // A preview scrolled off screen needs no frames; resume when it returns.
                postDelayed(this, 300L)
            }
        }
    }

    internal fun updateSpicyPlayback(position: (() -> Long)?, playing: Boolean) {
        val lineActive = position != null && (text as? android.text.Spanned)?.getSpans(
            0, text.length, SpicyLineSpan::class.java
        )?.any { it.animated } == true
        val changed = (spicyPosition == null) != (position == null) || lineActive != spicyLineActive
        spicyLineActive = lineActive
        val requestedPosition = position?.invoke()
        val needsRedraw = changed || playing != spicyPlaying || requestedPosition != spicyRequestedPosition
        spicyRequestedPosition = requestedPosition
        spicyPosition = position
        spicyPlaying = playing
        removeCallbacks(spicyFrame)
        if (position == null) {
            spicyDrawing.reset()
            spicyLineDrawing.reset()
        }
        else if (previewEffectsVisible && playing && isShown && windowVisibility == VISIBLE && isAttachedToWindow) postOnAnimation(spicyFrame)
        if (changed) requestLayout()
        if (needsRedraw) invalidate()
    }

    private fun spicyInset(): Int = if (spicyPosition != null) maxOf(
        kotlin.math.ceil(textSize * .24f).toInt(),
        // Keep the preview geometry fixed while tuning glow; the overlay uses the actual outset.
        LyricsHalo.outset(textSize, if (preview) PREVIEW_GLOW_SPACE else lyricsGlowStyle)
    ) else 0
    override fun getCompoundPaddingLeft() = super.getCompoundPaddingLeft() + spicyInset() + if (spicyLineActive) lineScaleInset else 0
    override fun getCompoundPaddingRight() = super.getCompoundPaddingRight() + spicyInset() + if (spicyLineActive) lineScaleInset else 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reserve the 5% activation scale at either alignment without resizing the window per frame.
        lineScaleInset = if (spicyLineActive) kotlin.math.ceil(
            (MeasureSpec.getSize(widthMeasureSpec) - super.getCompoundPaddingLeft() - super.getCompoundPaddingRight())
                .coerceAtLeast(0) * .05f
        ).toInt() else 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun getCompoundPaddingTop() = super.getCompoundPaddingTop() + spicyInset()
    override fun getCompoundPaddingBottom() = super.getCompoundPaddingBottom() + spicyInset()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Called by TextView's constructor, before Kotlin fields are initialized.
        if (spicyPosition != null) updateSpicyPlayback(spicyPosition, spicyPlaying)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (spicyPosition != null) updateSpicyPlayback(spicyPosition, spicyPlaying)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpicyPlayback(spicyPosition, spicyPlaying)
    }

    // Called inside onDraw: draw() would recurse and redraw the stationary bubble background.
    @android.annotation.SuppressLint("WrongCall")
    private fun drawLyrics(canvas: Canvas) {
        val position = spicyPosition
        if (position == null || !previewEffectsVisible) {
            super.onDraw(canvas)
            return
        }
        spicyDrawing.prepare()
        spicyLineDrawing.prepare()
        // TextView may pass its own paint to CharacterStyle. The temporary ink mask
        // must not permanently clear the user's shadow (or leak transparent paint).
        spicyBasePaint.set(paint)
        try {
            super.onDraw(canvas)
        } finally {
            paint.set(spicyBasePaint)
        }
        val textLayout = layout ?: return
        val space = height - compoundPaddingTop - compoundPaddingBottom - textLayout.height
        val verticalOffset = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> space
            Gravity.CENTER_VERTICAL -> space / 2
            else -> 0
        }.coerceAtLeast(0)
        canvas.withSave {
            translate(compoundPaddingLeft.toFloat(), (extendedPaddingTop + verticalOffset).toFloat())
            val currentPosition = position()
            val now = SystemClock.uptimeMillis()
            spicyDrawing.draw(this, currentPosition, now)
            spicyLineDrawing.draw(this, currentPosition, now)
        }
    }

    internal var onWindowConfigurationChanged: (() -> Unit)? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onWindowConfigurationChanged?.invoke()
    }

    private var lyricContentAlpha: Float = REST_ALPHA
    private var lyricContentTranslationY: Float = 0f
    private var lyricContentScaleX: Float = REST_SCALE
    private var lyricContentScaleY: Float = REST_SCALE

    private var lyricAnimator: ValueAnimator? = null

    override fun cancelLyricsTextAnimation() {
        val animator = lyricAnimator ?: return
        lyricAnimator = null
        animator.cancel()
    }

    override fun setLyricsTextAnimationState(
        alpha: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        lyricContentAlpha = alpha.coerceIn(0f, 1f)
        lyricContentTranslationY = translationY
        lyricContentScaleX = scaleX.coerceAtLeast(0f)
        lyricContentScaleY = scaleY.coerceAtLeast(0f)
        invalidate()
    }

    override fun animateLyricsTextToRest(
        durationMs: Long,
        interpolator: TimeInterpolator
    ) {
        cancelLyricsTextAnimation()

        val startAlpha = lyricContentAlpha
        val startTranslationY = lyricContentTranslationY
        val startScaleX = lyricContentScaleX
        val startScaleY = lyricContentScaleY
        if (
            startAlpha == REST_ALPHA &&
            startTranslationY == 0f &&
            startScaleX == REST_SCALE &&
            startScaleY == REST_SCALE
        ) {
            return
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                setLyricsTextAnimationState(
                    alpha = lerp(startAlpha, REST_ALPHA, fraction),
                    translationY = lerp(startTranslationY, 0f, fraction),
                    scaleX = lerp(startScaleX, REST_SCALE, fraction),
                    scaleY = lerp(startScaleY, REST_SCALE, fraction)
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (lyricAnimator !== animation) return
                    lyricAnimator = null
                    setLyricsTextAnimationState(
                        alpha = REST_ALPHA,
                        translationY = 0f,
                        scaleX = REST_SCALE,
                        scaleY = REST_SCALE
                    )
                }
            })
        }
        lyricAnimator = animator
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (lyricContentAlpha <= 0f) return

        canvas.withSave {
            // Contain shadow layers and animated glyphs even under a scrolling parent's clip.
            clipRect(0, 0, width, height)
            translate(0f, lyricContentTranslationY)
            scale(
                lyricContentScaleX,
                lyricContentScaleY,
                width / 2f,
                height / 2f
            )
            if (lyricContentAlpha >= REST_ALPHA) {
                drawLyrics(this)
            } else {
                val layerSaveCount = saveLayerAlpha(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    (lyricContentAlpha * 255f).roundToInt()
                )
                drawLyrics(this)
                restoreToCount(layerSaveCount)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(spicyFrame)
        spicyDrawing.reset()
        spicyLineDrawing.reset()
        cancelLyricsTextAnimation()
        setLyricsTextAnimationState(
            alpha = REST_ALPHA,
            translationY = 0f,
            scaleX = REST_SCALE,
            scaleY = REST_SCALE
        )
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private companion object {
        val PREVIEW_GLOW_SPACE = LyricsGlowStyle(spreadPercent = LyricsGlowStyle.MAX_SPREAD_PERCENT)
        const val REST_ALPHA = 1f
        const val REST_SCALE = 1f
    }
}

internal interface LyricsTextAnimationTarget {
    fun cancelLyricsTextAnimation()

    fun setLyricsTextAnimationState(
        alpha: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float
    )

    fun animateLyricsTextToRest(durationMs: Long, interpolator: TimeInterpolator)
}
