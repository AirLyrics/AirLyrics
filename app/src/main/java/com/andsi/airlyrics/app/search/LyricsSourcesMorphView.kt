package com.andsi.airlyrics.app.search

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One measured panel, one drawn surface, one reversible clock. Only clipping, drawing and
 * child render properties change per frame; neither the panel nor the activity is remeasured.
 */
@SuppressLint("ViewConstructor")
internal class LyricsSourcesMorphView(
    context: Context,
    private val palette: AirLyricsPalette,
    private val content: View,
    private val capsule: TextView,
    private val anchor: () -> View?,
    private val onContentProgress: (Float) -> Unit,
    private val onDim: (Float) -> Unit,
    private val onCancel: () -> Unit,
    private val onReopen: () -> Unit,
    private val onClosed: () -> Unit,
    private val onOpened: () -> Unit
) : ViewGroup(context) {
    internal var progress = 0f
        private set
    internal val capsuleBounds = RectF()
    internal val panelBounds = RectF()
    internal val surfaceBounds = RectF()
    private val outline = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenLocation = IntArray(2)
    private val anchorLocation = IntArray(2)
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private var animator: ValueAnimator? = null
    private var targetOpen = true
    private var started = false
    private var disposed = false
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private var waitingForIme = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(content)
        addView(capsule)
        capsule.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        content.alpha = 0f
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val wasWaitingForIme = waitingForIme
            waitingForIme = !started && insets.isVisible(WindowInsetsCompat.Type.ime())
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            if (wasWaitingForIme != waitingForIme || safeLeft != safe.left || safeTop != safe.top ||
                safeRight != safe.right || safeBottom != safe.bottom) {
                safeLeft = safe.left
                safeTop = safe.top
                safeRight = safe.right
                safeBottom = safe.bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val margin = dp(12)
        val left = safeLeft + margin
        val top = safeTop + margin
        val right = (width - safeRight - margin).coerceAtLeast(left)
        val bottom = (height - safeBottom - margin).coerceAtLeast(top)
        getLocationOnScreen(screenLocation)
        val trigger = anchor()?.takeIf { it.isLaidOut && it.width > 0 }
        if (trigger != null) {
            trigger.getLocationOnScreen(anchorLocation)
            capsuleBounds.set(
                (anchorLocation[0] - screenLocation[0]).toFloat(),
                (anchorLocation[1] - screenLocation[1]).toFloat(),
                (anchorLocation[0] - screenLocation[0] + trigger.width).toFloat(),
                (anchorLocation[1] - screenLocation[1] + trigger.height).toFloat()
            )
        } else {
            capsule.measure(atMost((right - left).coerceAtLeast(0)), unspecified())
            capsuleBounds.set((right - capsule.measuredWidth).toFloat(), top.toFloat(),
                right.toFloat(), (top + capsule.measuredHeight.coerceAtLeast(dp(48))).toFloat())
        }
        val panelRight = capsuleBounds.right.coerceIn(left.toFloat(), right.toFloat())
        val panelWidth = min(dp(420), (panelRight - left).roundToInt()).coerceAtLeast(0)
        // On short landscape windows move the expanded top up to keep fixed controls usable.
        val panelTop = min(capsuleBounds.top, (bottom - dp(260)).toFloat()).coerceAtLeast(top.toFloat())
        content.measure(exact(panelWidth), atMost((bottom - panelTop).roundToInt().coerceAtLeast(0)))
        panelBounds.set(panelRight - panelWidth, panelTop, panelRight, panelTop + content.measuredHeight)
        capsule.measure(exact(capsuleBounds.width().roundToInt()), exact(capsuleBounds.height().roundToInt()))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(panelBounds.left.roundToInt(), panelBounds.top.roundToInt(),
            panelBounds.right.roundToInt(), panelBounds.bottom.roundToInt())
        capsule.layout(capsuleBounds.left.roundToInt(), capsuleBounds.top.roundToInt(),
            capsuleBounds.right.roundToInt(), capsuleBounds.bottom.roundToInt())
        if (!started && !waitingForIme && isAttachedToWindow && width > 0 && height > 0) {
            started = true
            anchor()?.alpha = 0f
            animateTo(targetOpen)
        } else {
            renderProgress()
        }
    }

    fun setOpen(open: Boolean) {
        if (disposed || targetOpen == open) return
        targetOpen = open
        if (started) animateTo(open)
    }

    private fun animateTo(open: Boolean) {
        cancelAnimator()
        val target = if (open) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow || abs(target - progress) < 0.001f) {
            progress = target
            renderProgress()
            settled()
            return
        }
        val running = ValueAnimator.ofFloat(progress, target).apply {
            // Linear clock; geometry and supporting content sample the same timeline in both directions.
            duration = ((if (open) OPEN_MS else CLOSE_MS) * abs(target - progress)).roundToInt().toLong()
                .coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                renderProgress()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animation !== animator) return
                    animator = null
                    progress = target
                    renderProgress()
                    settled()
                }
            })
        }
        animator = running
        running.start()
    }

    private fun settled() {
        if (targetOpen) onOpened() else onClosed()
    }

    private fun renderProgress() {
        val shape = easeOut.getInterpolation((progress / 0.72f).coerceIn(0f, 1f))
        surfaceBounds.set(
            lerp(capsuleBounds.left, panelBounds.left, shape),
            lerp(capsuleBounds.top, panelBounds.top, shape),
            lerp(capsuleBounds.right, panelBounds.right, shape),
            lerp(capsuleBounds.bottom, panelBounds.bottom, shape)
        )
        val radius = lerp(capsuleBounds.height() / 2f, dp(24).toFloat(), shape)
        outline.rewind()
        outline.addRoundRect(surfaceBounds, radius, radius, Path.Direction.CW)
        paint.color = palette.surfaceLight
        capsule.alpha = 1f - segment(shape, 0f, 0.3f)
        content.alpha = 1f
        content.importantForAccessibility = if (targetOpen && progress >= 0.99f)
            IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (content as? ViewGroup)?.descendantFocusability = if (targetOpen && progress >= 0.99f)
            FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        onContentProgress(progress)
        onDim(segment(progress, 0f, 0.72f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = palette.stroke
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.FILL
        paint.color = palette.surfaceLight
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(outline)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // Transparent/clipped panel children must never receive invisible taps during the morph.
        return !targetOpen || progress < 0.99f || !panelBounds.contains(event.x, event.y)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!targetOpen && capsuleBounds.contains(event.x, event.y)) onReopen()
            else if (!surfaceBounds.contains(event.x, event.y) ||
                (progress < 0.99f && capsuleBounds.contains(event.x, event.y))) onCancel()
        }
        return true
    }

    fun dispose() {
        disposed = true
        cancelAnimator()
        anchor()?.alpha = 1f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        cancelAnimator()
        anchor()?.alpha = 1f
        super.onDetachedFromWindow()
    }

    private fun cancelAnimator() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(value.coerceAtLeast(0), MeasureSpec.AT_MOST)
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    companion object {
        internal const val OPEN_MS = 450L
        internal const val CLOSE_MS = 360L
        internal fun segment(value: Float, start: Float, end: Float): Float {
            val p = ((value - start) / (end - start)).coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
        private fun lerp(start: Float, end: Float, p: Float) = start + (end - start) * p
    }
}
