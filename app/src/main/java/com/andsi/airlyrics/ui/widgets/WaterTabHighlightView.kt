package com.andsi.airlyrics.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.PI
import kotlin.math.sin

class WaterTabHighlightView(
    context: Context,
    private val accentColor: Int
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        alpha = 54
    }
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    private var currentLeft = 0f
    private var currentTop = 0f
    private var currentWidth = 0f
    private var currentHeight = 0f
    private var stretch = 0f
    var hasPosition = false
        private set

    fun moveTo(
        targetCenterX: Float,
        targetCenterY: Float,
        targetWidth: Float,
        targetHeight: Float,
        animate: Boolean
    ) {
        if (targetWidth <= 0f || targetHeight <= 0f) return
        animator?.cancel()

        if (width <= 0) return
        val safeInset = resources.displayMetrics.density * 8f
        val halfWidth = targetWidth / 2f
        val clampedTargetCenterX = targetCenterX.coerceIn(
            safeInset + halfWidth,
            width - safeInset - halfWidth
        )
        val targetLeft = clampedTargetCenterX - halfWidth
        val targetTop = targetCenterY - targetHeight / 2f

        if (!hasPosition || !animate) {
            currentLeft = targetLeft
            currentTop = targetTop
            currentWidth = targetWidth
            currentHeight = targetHeight
            stretch = 0f
            hasPosition = true
            invalidate()
            return
        }

        val startLeft = currentLeft
        val startTop = currentTop
        val startWidth = currentWidth
        val startHeight = currentHeight
        val startCenter = startLeft + startWidth / 2f
        val targetCenter = targetLeft + targetWidth / 2f
        val travel = kotlin.math.abs(targetCenter - startCenter)

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (260L + (travel / resources.displayMetrics.density * 1.2f).toLong()).coerceAtMost(430L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                val eased = 0.5f - kotlin.math.cos((t * PI).toFloat()) / 2f
                currentLeft = lerp(startLeft, targetLeft, eased)
                currentTop = lerp(startTop, targetTop, eased)
                currentWidth = lerp(startWidth, targetWidth, eased)
                currentHeight = lerp(startHeight, targetHeight, eased)
                stretch = sin((t * PI).toFloat()) * travel * 0.26f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasPosition) return

        val centerX = currentLeft + currentWidth / 2f
        val centerY = currentTop + currentHeight / 2f
        val safeInset = resources.displayMetrics.density * 8f
        val wantedHalfWidth = currentWidth / 2f + stretch
        val halfHeight = currentHeight / 2f
        val radius = halfHeight.coerceAtLeast(1f)

        rect.set(
            centerX - wantedHalfWidth,
            centerY - halfHeight,
            centerX + wantedHalfWidth,
            centerY + halfHeight
        )
        if (rect.left < safeInset) {
            rect.offset(safeInset - rect.left, 0f)
        }
        if (rect.right > width - safeInset) {
            rect.offset(width - safeInset - rect.right, 0f)
        }
        canvas.drawRoundRect(rect, radius, radius, glowPaint)

        val inset = resources.displayMetrics.density * 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, (radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f), paint)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float {
        return start + (end - start) * amount
    }
}

