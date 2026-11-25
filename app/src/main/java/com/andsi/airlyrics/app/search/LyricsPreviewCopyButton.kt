package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import kotlin.math.roundToInt

internal class LyricsPreviewCopyButton(
    context: Context,
    label: Int,
    iconColor: Int,
    accent: Int
) : AppCompatImageButton(context) {
    private val density = resources.displayMetrics.density
    private val iconRadius = AirUiTokens.Layout.IconSize * density / 2f
    private val copyIcon = icon(R.drawable.ic_air_copy, iconColor)
    private val checkIcon = icon(R.drawable.ic_air_check, ColorUtils.blendARGB(iconColor, accent, 0.25f))
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.35f * density
    }
    private var animator: ValueAnimator? = null
    private var copied = false
    private var checkProgress = 0f
    private var burstProgress = 1f
    private val restore = Runnable {
        copied = false
        ViewCompat.setStateDescription(this, null)
        transitionTo(0f, RESTORE_MS)
    }

    init {
        contentDescription = context.getString(label)
        tooltipText = contentDescription
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 38)),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        )
    }

    fun showCopied() {
        if (!copied) {
            copied = true
            ViewCompat.setStateDescription(this, context.getString(R.string.search_copied))
            transitionTo(1f, BURST_MS, burst = true)
        }
        removeCallbacks(restore)
        postDelayed(restore, AirUiTokens.Motion.FeedbackHoldMs)
    }

    private fun transitionTo(target: Float, durationMs: Long, burst: Boolean = false) {
        animator?.cancel()
        animator = null
        burstProgress = 1f
        if (!isShown || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            checkProgress = target
            invalidate()
            return
        }

        val start = checkProgress
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedFraction
                checkProgress = start + (target - start) * EASE_OUT.getInterpolation(time)
                if (burst) burstProgress = time
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.save()
        canvas.translate(centerX, centerY)
        drawIcon(canvas, copyIcon, 1f - checkProgress, 1f - 0.06f * checkProgress)
        drawIcon(canvas, checkIcon, checkProgress, 0.90f + 0.10f * checkProgress)
        if (burstProgress > 0f && burstProgress < 1f) drawBurst(canvas, burstProgress)
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, opacity: Float, scale: Float) {
        if (opacity <= 0f) return
        canvas.save()
        canvas.scale(scale, scale)
        icon.alpha = (opacity * 255f).roundToInt()
        icon.draw(canvas)
        canvas.restore()
    }

    private fun drawBurst(canvas: Canvas, time: Float) {
        val travel = 0.48f + 0.52f * EASE_OUT.getInterpolation(time)
        val opacity = (time / 0.12f).coerceAtMost(1f) * ((1f - time) / 0.68f).coerceAtMost(1f)
        sparkPaint.alpha = (opacity * 230f).roundToInt()
        for (spark in SPARKS) {
            val x = spark.x * density * travel
            val y = spark.y * density * travel
            if (spark.ray) {
                val inner = 1f - 0.24f * (1f - time)
                canvas.drawLine(x * inner, y * inner, x, y, sparkPaint)
            } else {
                canvas.drawCircle(x, y, 1.45f * density * (1f - 0.35f * time), sparkPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(restore)
        animator?.cancel()
        animator = null
        copied = false
        checkProgress = 0f
        burstProgress = 1f
        ViewCompat.setStateDescription(this, null)
        super.onDetachedFromWindow()
    }

    private fun icon(resource: Int, color: Int): Drawable {
        val radius = iconRadius.roundToInt()
        return requireNotNull(AppCompatResources.getDrawable(context, resource)).mutate().apply {
            setTint(color)
            setBounds(-radius, -radius, radius, radius)
        }
    }

    private data class Spark(val x: Float, val y: Float, val ray: Boolean = false)

    private companion object {
        const val BURST_MS = 260L
        const val RESTORE_MS = 150L
        val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)
        val SPARKS = listOf(
            Spark(-17f, -11f, ray = true), Spark(-5f, -20f),
            Spark(12f, -16f, ray = true), Spark(20f, -4f),
            Spark(15f, 13f), Spark(1f, 20f, ray = true), Spark(-19f, 9f)
        )
    }
}
