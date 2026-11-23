package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorText
import kotlin.math.roundToLong

private const val STITCH_DURATION_MS = 320L
private const val SCENE_WIDTH = 288f
private const val SCENE_HEIGHT = 57f
private const val SEAM_Y = 44f
private const val SEAM_START = 4f
private const val SEAM_END = SCENE_WIDTH - SEAM_START
private const val STITCH_LENGTH = 21f
private const val STITCH_COUNT = 9
private const val STITCH_PITCH = (SEAM_END - SEAM_START - STITCH_LENGTH) / (STITCH_COUNT - 1)

/** Draws one stitch per confirmed download segment. */
@SuppressLint("ViewConstructor")
internal class UpdateDownloadProgressView(host: MainUiHost) :
    ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal) {
    private val guidePaint = stroke(ColorUtils.blendARGB(host.colorCard, host.colorText, 0.25f), 1.7f)
    private val seamPaint = stroke(ColorUtils.blendARGB(host.colorAccentText, host.colorAccent, 0.28f), 2.65f)
    private val seam = Path().apply {
        repeat(STITCH_COUNT) { index ->
            val start = SEAM_START + STITCH_PITCH * index
            moveTo(start, SEAM_Y)
            quadTo(start + STITCH_LENGTH * 0.5f, SEAM_Y - 0.75f, start + STITCH_LENGTH, SEAM_Y)
        }
    }
    private var aggregatedVisible = false
    private var advance: ValueAnimator? = null
    private var displayedStitches = 0f
    private val targetStitches: Int get() = progress * STITCH_COUNT / max
    private val sceneScale: Float get() = minOf(width / SCENE_WIDTH, height / SCENE_HEIGHT)
    val lineCenterY: Float get() = (height - SCENE_HEIGHT * sceneScale) / 2f + SEAM_Y * sceneScale

    init {
        isIndeterminate = false
        progressDrawable = null
        max = 100
        contentDescription = host.getString(R.string.ui_app_update_progress)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun showProgress(percent: Int, animate: Boolean = true) {
        val next = percent.coerceIn(0, max)
        val restarting = next < progress
        progress = next
        if (!animate || restarting) settleProgress() else advanceStitches()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        if (isVisible) advanceStitches() else settleProgress()
    }

    override fun onDetachedFromWindow() {
        aggregatedVisible = false
        settleProgress()
        super.onDetachedFromWindow()
    }

    private fun advanceStitches() {
        if (!aggregatedVisible || advance != null || displayedStitches >= targetStitches) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            settleProgress()
            return
        }

        val target = targetStitches.toFloat()
        val animator = ValueAnimator.ofFloat(displayedStitches, target).apply {
            duration = ((target - displayedStitches) * STITCH_DURATION_MS).roundToLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    displayedStitches = it.animatedValue as Float
                    invalidate()
                } else settleProgress()
            }
            doOnEnd {
                advance = null
                advanceStitches()
            }
        }
        advance = animator
        animator.start()
    }

    private fun settleProgress() {
        advance?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        advance = null
        displayedStitches = targetStitches.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = sceneScale
        if (scale <= 0f) return

        canvas.withTranslation((width - SCENE_WIDTH * scale) / 2f, (height - SCENE_HEIGHT * scale) / 2f) {
            scale(scale, scale)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                translate(SCENE_WIDTH, 0f)
                scale(-1f, 1f)
            }

            drawPath(seam, guidePaint)
            if (displayedStitches > 0f) {
                val filledEnd = SEAM_START + (displayedStitches - 1f) * STITCH_PITCH + STITCH_LENGTH
                withClip(0f, 0f, (filledEnd + seamPaint.strokeWidth).coerceAtLeast(0f), SCENE_HEIGHT) {
                    drawPath(seam, seamPaint)
                }
            }
        }
    }
}

private fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    style = Paint.Style.STROKE
}
