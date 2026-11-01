package com.andsi.airlyrics.app.search

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

private fun smoothStep(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

private fun segment(value: Float, start: Float, end: Float) = smoothStep((value - start) / (end - start))

/** A measured child stays at its natural size while its parent reveals or hides the space. */
// Motion Prompt #052 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#052
internal class LyricsSearchReveal(
    private val slot: FrameLayout,
    private val content: View,
    private val expandedBottomMargin: Int,
    private val durationMs: Long,
    private val slidePx: Float,
    private val alphaStart: Float = 0.2f
) {
    private var progress = 0f
    private var expanded = false
    private var animator: ValueAnimator? = null
    private var measuredContentHeight = 0

    init {
        slot.clipChildren = true
        slot.clipToPadding = true
        settle()
        slot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = finish()
        })
    }

    fun setExpanded(next: Boolean, animate: Boolean) {
        if (expanded == next) return
        expanded = next
        cancel()
        val width = (slot.width.takeIf { it > 0 } ?: (slot.parent as? View)?.width ?: 0) -
            slot.paddingLeft - slot.paddingRight
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !slot.isAttachedToWindow || width <= 0) {
            progress = if (next) 1f else 0f
            settle()
            return
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        measuredContentHeight = content.measuredHeight
        if (measuredContentHeight <= 0) {
            progress = if (next) 1f else 0f
            settle()
            return
        }
        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
            height = measuredContentHeight
        }
        slot.visibility = View.VISIBLE
        slot.importantForAccessibility = if (next) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val target = if (next) 1f else 0f
        val distance = abs(target - progress)
        if (distance < 0.001f) {
            progress = target
            settle()
            return
        }
        val running = ValueAnimator.ofFloat(progress, target).apply {
            duration = (durationMs * distance).roundToInt().toLong().coerceAtLeast(50L)
            interpolator = if (next) PathInterpolator(0.2f, 0f, 0f, 1f)
                else PathInterpolator(0.4f, 0f, 1f, 1f)
            addUpdateListener { animation ->
                if (animator !== animation) return@addUpdateListener
                progress = animation.animatedValue as Float
                applyProgress()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    progress = target
                    settle()
                }
            })
        }
        animator = running
        applyProgress()
        running.start()
    }

    fun finish() {
        cancel()
        progress = if (expanded) 1f else 0f
        settle()
    }

    fun contentChanged() {
        if (animator == null) return
        val width = (slot.width.takeIf { it > 0 } ?: (slot.parent as? View)?.width ?: 0) -
            slot.paddingLeft - slot.paddingRight
        if (width <= 0) return
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (measuredContentHeight != content.measuredHeight) {
            measuredContentHeight = content.measuredHeight
            content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
                height = measuredContentHeight
            }
            applyProgress()
        }
    }

    fun prepareAnnouncement() {
        slot.visibility = View.VISIBLE
        slot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun applyProgress() {
        val params = slot.layoutParams as LinearLayout.LayoutParams
        val height = (measuredContentHeight * progress).roundToInt()
        val margin = (expandedBottomMargin * progress).roundToInt()
        if (params.height != height || params.bottomMargin != margin) {
            params.height = height
            params.bottomMargin = margin
            slot.layoutParams = params
        }
        content.alpha = segment(progress, alphaStart, 1f)
        content.translationY = slidePx * (1f - progress)
    }

    private fun settle() {
        val params = slot.layoutParams as LinearLayout.LayoutParams
        params.height = if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        params.bottomMargin = if (expanded) expandedBottomMargin else 0
        slot.layoutParams = params
        content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        content.alpha = 1f
        content.translationY = 0f
        slot.visibility = if (expanded) View.VISIBLE else View.GONE
        slot.importantForAccessibility = if (expanded) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun cancel() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }
}

/** Keeps the exiting preview visible but non-interactive until the handoff settles. */
internal class LyricsSearchPreviewPanel(context: Context) : LinearLayout(context) {
    var blocksInteraction = false
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        blocksInteraction || super.onInterceptTouchEvent(event)

    // This pane only absorbs input while another pane is taking over; it has no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        blocksInteraction || super.onTouchEvent(event)
}

/** One reversible progress connects result and preview panes, including rapid Back gestures. */
// Motion Prompt #057 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#057
internal class LyricsSearchPaneMotion(
    private val results: LyricsSourcePages,
    private val preview: LyricsSearchPreviewPanel,
    private val distancePx: Float,
    private val onSettled: (Boolean) -> Unit
) {
    private var progress = 0f
    private var targetPreview = false
    private var hasResults = false
    private var initialized = false
    private var animator: ValueAnimator? = null

    fun show(previewSelected: Boolean, resultsAvailable: Boolean) {
        val resultsChanged = hasResults != resultsAvailable
        hasResults = resultsAvailable
        results.blocksInteraction = previewSelected || !resultsAvailable
        preview.blocksInteraction = !previewSelected
        results.importantForAccessibility = if (results.blocksInteraction)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        preview.importantForAccessibility = if (preview.blocksInteraction)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (targetPreview == previewSelected && !resultsChanged && initialized) return
        results.animate().cancel()
        results.translationY = 0f
        targetPreview = previewSelected
        if (!initialized || !resultsAvailable || !ValueAnimator.areAnimatorsEnabled() ||
            results.width <= 0 || !results.isAttachedToWindow) {
            initialized = true
            cancel()
            progress = if (previewSelected) 1f else 0f
            settle()
            return
        }
        cancel()
        results.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        val target = if (previewSelected) 1f else 0f
        val distance = abs(target - progress)
        if (distance < 0.001f) {
            settle()
            return
        }
        val running = ValueAnimator.ofFloat(progress, target).apply {
            duration = (210f * distance).roundToInt().toLong().coerceAtLeast(65L)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                if (animator !== animation) return@addUpdateListener
                progress = animation.animatedValue as Float
                applyProgress()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    progress = target
                    settle()
                }
            })
        }
        animator = running
        applyProgress()
        running.start()
    }

    fun finish() {
        cancel()
        progress = if (targetPreview) 1f else 0f
        settle()
    }

    private fun applyProgress() {
        val resultVisibility = 1f - segment(progress, 0.24f, 0.8f)
        val previewVisibility = segment(progress, 0.2f, 0.76f)
        results.alpha = resultVisibility
        results.translationX = -distancePx * 0.65f * progress
        preview.alpha = previewVisibility
        preview.translationX = distancePx * (1f - progress)
        val resultReveal = 1f - segment(progress, 0.55f, 1f)
        val previewReveal = segment(progress, 0f, 0.45f)
        results.clipBounds = Rect(0, 0, results.width, (results.height * resultReveal).roundToInt())
        preview.clipBounds = Rect(0, 0, preview.width, (preview.height * previewReveal).roundToInt())
    }

    private fun settle() {
        results.visibility = if (!targetPreview && hasResults) View.VISIBLE else View.GONE
        preview.visibility = if (targetPreview) View.VISIBLE else View.GONE
        results.alpha = 1f
        results.translationX = 0f
        results.clipBounds = null
        preview.alpha = 1f
        preview.translationX = 0f
        preview.clipBounds = null
        onSettled(targetPreview)
    }

    private fun cancel() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }
}
