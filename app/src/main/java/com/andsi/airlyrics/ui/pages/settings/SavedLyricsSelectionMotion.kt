package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.graphics.withTranslation
import com.andsi.airlyrics.ui.model.MainUiHost
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** A reversible progress value, settled on detach and when system animations are disabled. */
internal class SavedLyricsSelectionMotion(
    private val owner: View,
    initial: Float,
    private val durationMs: Long = 280L,
    private val render: (Float) -> Unit
) {
    var progress: Float = initial
        private set
    private var target = initial
    private var animator: ValueAnimator? = null

    init {
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                cancel()
                progress = target
                render(progress)
            }
        })
    }

    fun moveTo(value: Float, animate: Boolean = true) {
        if (target == value && animator != null) return
        cancel()
        target = value
        val distance = abs(value - progress)
        if (!animate || !owner.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() || distance < 0.001f) {
            progress = value
            render(progress)
            return
        }
        val next = ValueAnimator.ofFloat(progress, value).apply {
            duration = (durationMs * distance).roundToInt().toLong().coerceAtLeast(60L)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                render(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    progress = target
                    render(progress)
                }
            })
        }
        animator = next
        next.start()
    }

    private fun cancel() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }
}

/** The two outer list strokes fold into a close glyph; the small ticks recede. */
@SuppressLint("ViewConstructor")
internal class SavedLyricsSelectionIconView(host: MainUiHost, @ColorInt color: Int) : View(host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var progress = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(resources.displayMetrics.density, min(width, height) / 24f)
        canvas.withTranslation((width - 24f * scale) / 2f, (height - 24f * scale) / 2f) {
            scale(scale, scale)
            val p = progress
            paint.alpha = 255
            fun mix(a: Float, b: Float) = a + (b - a) * p
            drawLine(mix(11f, 6.5f), mix(6f, 6.5f), mix(20f, 17.5f), mix(6f, 17.5f), paint)
            drawLine(mix(11f, 6.5f), mix(18f, 17.5f), mix(20f, 17.5f), mix(18f, 6.5f), paint)
            paint.alpha = (255f * (1f - p)).roundToInt()
            drawLine(mix(11f, 15.5f), 12f, 20f, 12f, paint)
            repeat(3) { index ->
                val y = 6f + index * 6f
                drawLine(4f + 2f * p, y, 5.5f + 2f * p, y + 1.5f, paint)
                drawLine(5.5f + 2f * p, y + 1.5f, 8f + 2f * p, y - 1.5f, paint)
            }
        }
    }
}

/** Measures the full panel at the current width, including large text, then reveals its height. */
@SuppressLint("ViewConstructor")
internal class SavedLyricsSelectionPanel(host: MainUiHost, content: View) : FrameLayout(host) {
    var progress = 0f
        private set
    init {
        clipChildren = true
        addView(content, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun reveal(value: Float) {
        progress = value
        visibility = if (value > 0f) VISIBLE else GONE
        getChildAt(0).apply {
            alpha = value
            translationY = (1f - value) * 12f * resources.displayMetrics.density
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(measuredWidth, (measuredHeight * progress).roundToInt())
    }
}
