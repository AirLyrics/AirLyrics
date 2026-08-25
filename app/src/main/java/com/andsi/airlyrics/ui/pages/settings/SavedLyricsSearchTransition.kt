package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.core.graphics.withTranslation
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val SEARCH_TRANSITION_MS = 280L
private const val SEARCH_TRANSITION_MIN_MS = 60L
private const val SEARCH_ICON_CANVAS_DP = 24f
private const val SEARCH_BAR_REST_SCALE_X = 0.985f

/** Draws one continuous search-to-close glyph instead of cross-fading two icons. */
@SuppressLint("ViewConstructor")
internal class SavedLyricsSearchIconView(
    context: MainUiHost,
    @ColorInt color: Int,
    initiallyClose: Boolean
) : View(context) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringBounds = RectF()
    private var morphProgress = if (initiallyClose) 1f else 0f

    internal fun setMorphProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (morphProgress == clamped) return
        morphProgress = clamped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val desiredIconSize = SEARCH_ICON_CANVAS_DP * density
        val fit = min(1f, min(width, height) / desiredIconSize)
        val drawingScale = density * fit
        val drawnSize = SEARCH_ICON_CANVAS_DP * drawingScale

        canvas.withTranslation((width - drawnSize) / 2f, (height - drawnSize) / 2f) {
            scale(drawingScale, drawingScale)

            val progress = morphProgress

            // The ring thins and unspools from the handle while that handle grows
            // into the first diagonal of the close glyph.
            val ringCollapse = smoothSegment(progress, 0.06f, 0.66f)
            val ringAlpha = 1f - smoothSegment(progress, 0.40f, 0.70f)
            if (ringAlpha > 0.001f && ringCollapse < 0.999f) {
                strokePaint.strokeWidth = lerp(
                    start = 1.95f,
                    end = 1.05f,
                    fraction = smoothSegment(progress, 0f, 0.54f)
                )
                strokePaint.alpha = (255f * ringAlpha).roundToInt()
                ringBounds.set(4.2f, 4.2f, 15.4f, 15.4f)
                drawArc(
                    ringBounds,
                    45f,
                    360f * (1f - ringCollapse),
                    false,
                    strokePaint
                )
            }

            val primaryLine = smoothSegment(progress, 0.06f, 0.76f)
            strokePaint.strokeWidth = 1.95f
            strokePaint.alpha = 255
            drawLine(
                lerp(13.9f, 6.65f, primaryLine),
                lerp(13.9f, 6.65f, primaryLine),
                lerp(19.35f, 17.35f, primaryLine),
                lerp(19.35f, 17.35f, primaryLine),
                strokePaint
            )

            // The second diagonal grows out from the center only after the first
            // line is recognizable, keeping a clear silhouette throughout.
            val secondaryLine = smoothSegment(progress, 0.48f, 0.94f)
            if (secondaryLine > 0.001f) {
                strokePaint.alpha = (
                    255f * smoothSegment(progress, 0.50f, 0.70f)
                ).roundToInt()
                drawLine(
                    lerp(12f, 17.35f, secondaryLine),
                    lerp(12f, 6.65f, secondaryLine),
                    lerp(12f, 6.65f, secondaryLine),
                    lerp(12f, 17.35f, secondaryLine),
                    strokePaint
                )
            }

        }
    }
}

/** Keeps glyph morphing and search-bar reveal on the same reversible progress. */
internal class SavedLyricsSearchTransition(
    host: MainUiHost,
    private val icon: SavedLyricsSearchIconView,
    private val searchBar: View,
    private val expandedBottomMargin: Int,
    initiallyExpanded: Boolean
) {
    private val expandedHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
    private val slideDistance = host.dp(AirUiTokens.Space.Lg).toFloat()
    private var progress = if (initiallyExpanded) 1f else 0f
    private var targetExpanded = initiallyExpanded
    private var animator: ValueAnimator? = null
    private var settledCallback: (() -> Unit)? = null

    init {
        applySettledState(initiallyExpanded)
        searchBar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                cancelAnimation()
                progress = if (targetExpanded) 1f else 0f
                settleAtTarget()
            }

            override fun onViewDetachedFromWindow(view: View) {
                cancelAnimation()
                progress = if (targetExpanded) 1f else 0f
                settleAtTarget()
            }
        })
    }

    internal fun setExpanded(
        expanded: Boolean,
        animate: Boolean,
        onSettled: (() -> Unit)? = null
    ) {
        if (targetExpanded == expanded && animator == null) {
            onSettled?.invoke()
            return
        }

        targetExpanded = expanded
        settledCallback = null
        cancelAnimation()
        settledCallback = onSettled

        val targetProgress = if (expanded) 1f else 0f
        val distance = abs(targetProgress - progress)
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || distance < 0.001f) {
            progress = targetProgress
            settleAtTarget()
            return
        }

        applyAnimatedProgress(progress)
        val activeAnimator = ValueAnimator.ofFloat(progress, targetProgress).apply {
            duration = (SEARCH_TRANSITION_MS * distance)
                .roundToInt()
                .toLong()
                .coerceAtLeast(SEARCH_TRANSITION_MIN_MS)
            interpolator = if (expanded) {
                PathInterpolator(0.2f, 0f, 0f, 1f)
            } else {
                PathInterpolator(0.4f, 0f, 1f, 1f)
            }
            addUpdateListener { valueAnimator ->
                progress = valueAnimator.animatedValue as Float
                applyAnimatedProgress(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation || targetExpanded != expanded) return
                    progress = targetProgress
                    animator = null
                    settleAtTarget()
                }
            })
        }
        animator = activeAnimator
        activeAnimator.start()
    }

    private fun applyAnimatedProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val reveal = smoothSegment(clamped, 0f, 1f)
        val layoutParams = searchBar.layoutParams as LinearLayout.LayoutParams

        icon.setMorphProgress(clamped)
        searchBar.visibility = if (clamped > 0f || targetExpanded) View.VISIBLE else View.GONE
        searchBar.alpha = reveal
        searchBar.translationY = -slideDistance * (1f - reveal)
        searchBar.scaleX = lerp(SEARCH_BAR_REST_SCALE_X, 1f, reveal)
        layoutParams.height = (expandedHeight * clamped).roundToInt()
        layoutParams.bottomMargin = (expandedBottomMargin * clamped).roundToInt()
        searchBar.layoutParams = layoutParams
    }

    private fun applySettledState(expanded: Boolean) {
        val layoutParams = searchBar.layoutParams as LinearLayout.LayoutParams
        icon.setMorphProgress(if (expanded) 1f else 0f)
        searchBar.alpha = if (expanded) 1f else 0f
        searchBar.translationY = if (expanded) 0f else -slideDistance
        searchBar.scaleX = if (expanded) 1f else SEARCH_BAR_REST_SCALE_X
        searchBar.visibility = if (expanded) View.VISIBLE else View.GONE
        layoutParams.height = if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        layoutParams.bottomMargin = if (expanded) expandedBottomMargin else 0
        searchBar.layoutParams = layoutParams
    }

    private fun settleAtTarget() {
        applySettledState(targetExpanded)
        settledCallback?.let { callback ->
            settledCallback = null
            callback()
        }
    }

    private fun cancelAnimation() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }
}

private fun smoothSegment(value: Float, start: Float, end: Float): Float {
    val normalized = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return normalized * normalized * (3f - 2f * normalized)
}

private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}
