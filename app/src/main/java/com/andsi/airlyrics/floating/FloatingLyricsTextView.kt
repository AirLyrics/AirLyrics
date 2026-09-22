package com.andsi.airlyrics.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withSave
import kotlin.math.roundToInt

/**
 * Lets the renderer animate lyric glyphs without transforming the floating bubble itself.
 *
 * A TextView draws its background before [onDraw], so applying the transition only while
 * delegating to TextView's content drawing keeps the background fully visible and stationary.
 */
internal class FloatingLyricsTextView(context: Context) : AppCompatTextView(context),
    LyricsTextAnimationTarget {

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
            translate(0f, lyricContentTranslationY)
            scale(
                lyricContentScaleX,
                lyricContentScaleY,
                width / 2f,
                height / 2f
            )
            if (lyricContentAlpha >= REST_ALPHA) {
                super.onDraw(this)
            } else {
                val layerSaveCount = saveLayerAlpha(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    (lyricContentAlpha * 255f).roundToInt()
                )
                super.onDraw(this)
                restoreToCount(layerSaveCount)
            }
        }
    }

    override fun onDetachedFromWindow() {
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
