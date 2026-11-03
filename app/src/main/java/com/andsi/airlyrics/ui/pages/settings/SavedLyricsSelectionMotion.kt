package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
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

/** A transparent layer over the list; only the toolbar and its handle consume touches. */
@SuppressLint("ViewConstructor")
internal class SavedLyricsSelectionPanel(
    private val host: MainUiHost,
    private val content: View,
    private val toggle: ImageView,
    private val actionGroup: View,
    private val onCollapse: () -> Unit = {}
) : FrameLayout(host) {
    var progress = 0f
        private set
    var expanded = true
        private set
    private val capsuleBounds = RectF()
    private val expandedCapsuleBounds = RectF()
    private val collapsedCapsuleBounds = RectF()
    private val handleBounds = RectF()
    private val screenLocation = IntArray(2)
    private val surfaceAlpha = if (host.isDarkTheme()) 238 else 250
    private val capsuleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(host.colorSurfaceLight, surfaceAlpha)
    }
    private val capsuleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = host.colorStroke
        style = Paint.Style.STROKE
        strokeWidth = host.dp(1).toFloat()
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(host.colorSurfaceLight, Color.BLACK, if (host.isDarkTheme()) 0.18f else 0.08f)
    }
    private val expansion = SavedLyricsSelectionMotion(toggle, 1f, 210L, ::renderExpansion)
    private var shellTouchActive = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        elevation = host.dp(5).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (content.width == 0 || toggle.width == 0) {
                    outline.setEmpty()
                    return
                }
                updateCapsuleBounds()
                outline.setRoundRect(
                    capsuleBounds.left.roundToInt(), capsuleBounds.top.roundToInt(),
                    capsuleBounds.right.roundToInt(), capsuleBounds.bottom.roundToInt(),
                    capsuleBounds.height() / 2f
                )
            }
        }
        addView(content, LayoutParams(WRAP_CONTENT, host.dp(56), android.view.Gravity.END or android.view.Gravity.BOTTOM).apply {
            marginEnd = host.dp(52)
            bottomMargin = host.dp(24)
        })
        addView(toggle, LayoutParams(host.dp(48), host.dp(56), android.view.Gravity.END or android.view.Gravity.BOTTOM).apply {
            marginEnd = host.dp(4)
            bottomMargin = host.dp(24)
        })
        toggle.setOnClickListener { setExpanded(!expanded) }
        updateToggleDescription()
        renderExpansion(1f)
    }

    fun reveal(value: Float) {
        progress = value
        visibility = if (value > 0f) VISIBLE else GONE
        renderExpansion(expansion.progress)
    }

    fun setExpanded(value: Boolean, animate: Boolean = true) {
        if (expanded == value) return
        expanded = value
        if (!value) onCollapse()
        updateToggleDescription()
        content.importantForAccessibility = if (value) {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        expansion.moveTo(if (value) 1f else 0f, animate)
    }

    fun containsControl(screenX: Int, screenY: Int): Boolean {
        if (visibility != VISIBLE) return false
        getLocationOnScreen(screenLocation)
        updateCapsuleBounds()
        return activeCapsuleBounds().contains(
            screenX - screenLocation[0].toFloat(), screenY - screenLocation[1].toFloat()
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val bodyHeight = host.dp(if (resources.configuration.fontScale >= 1.2f) 64 else 56)
        val edgeInset = min(host.dp(18), ((width - host.dp(280)) / 2).coerceAtLeast(host.dp(2)))
        (toggle.layoutParams as LayoutParams).apply {
            marginEnd = edgeInset
            bottomMargin = host.dp(24) + (bodyHeight - host.dp(56)) / 2
        }
        (content.layoutParams as LayoutParams).marginEnd = edgeInset + host.dp(48)
        val available = (width - edgeInset - host.dp(48)).coerceAtLeast(0)
        val bodyWidth = min(host.dp(265), available)
        val actionWidth = min(host.dp(200), (bodyWidth - host.dp(64)).coerceAtLeast(host.dp(192)))
        if (actionGroup.layoutParams.width != actionWidth) actionGroup.layoutParams.width = actionWidth
        if (content.layoutParams.width != bodyWidth) content.layoutParams.width = bodyWidth
        if (content.layoutParams.height != bodyHeight) content.layoutParams.height = bodyHeight
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateCapsuleBounds()
        canvas.withTranslation(content.translationX, 0f) {
            drawCapsule(this, expandedCapsuleBounds, content.alpha)
        }
        drawCapsule(canvas, collapsedCapsuleBounds, progress * (1f - expansion.progress))
        handleBounds.set(
            toggle.left + host.dp(2).toFloat(), toggle.top + host.dp(2).toFloat(),
            toggle.right - host.dp(2).toFloat(), toggle.bottom - host.dp(2).toFloat()
        )
        val handleRadius = handleBounds.height() / 2f
        handleFill.alpha = (255f * progress).roundToInt()
        canvas.drawRoundRect(handleBounds, handleRadius, handleRadius, handleFill)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            updateCapsuleBounds()
            shellTouchActive = activeCapsuleBounds().contains(event.x, event.y)
            return shellTouchActive
        }
        val wasActive = shellTouchActive
        if (event.actionMasked == MotionEvent.ACTION_UP && wasActive) performClick()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            shellTouchActive = false
        }
        return wasActive
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateCapsuleBounds() {
        val top = content.top - host.dp(4).toFloat()
        val right = toggle.right + host.dp(2).toFloat()
        val bottom = content.bottom + host.dp(4).toFloat()
        expandedCapsuleBounds.set(content.left.coerceAtLeast(0).toFloat(), top, right, bottom)
        collapsedCapsuleBounds.set(toggle.left - host.dp(2).toFloat(), top, right, bottom)
        capsuleBounds.set(
            collapsedCapsuleBounds.left +
                (expandedCapsuleBounds.left - collapsedCapsuleBounds.left) * expansion.progress,
            top, right, bottom
        )
    }

    private fun activeCapsuleBounds(): RectF = if (expansion.progress > 0f) {
        expandedCapsuleBounds
    } else {
        collapsedCapsuleBounds
    }

    private fun drawCapsule(canvas: Canvas, bounds: RectF, opacity: Float) {
        if (opacity <= 0f) return
        val radius = bounds.height() / 2f
        capsuleFill.alpha = (surfaceAlpha * opacity).roundToInt()
        capsuleStroke.alpha = (Color.alpha(host.colorStroke) * opacity).roundToInt()
        canvas.drawRoundRect(bounds, radius, radius, capsuleFill)
        canvas.drawRoundRect(bounds, radius, radius, capsuleStroke)
    }

    private fun updateToggleDescription() {
        toggle.contentDescription = host.getString(if (expanded) {
            R.string.ui_saved_lyrics_toolbar_collapse
        } else {
            R.string.ui_saved_lyrics_toolbar_expand
        })
    }

    private fun renderExpansion(value: Float) {
        content.visibility = if (value > 0f) VISIBLE else INVISIBLE
        content.alpha = progress * value
        content.translationX = host.dp(36) * (1f - value) + host.dp(12) * (1f - progress)
        toggle.alpha = progress
        toggle.translationX = host.dp(12) * (1f - progress)
        toggle.rotation = 180f * (1f - value)
        invalidate()
        invalidateOutline()
    }
}
