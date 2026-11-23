package com.andsi.airlyrics.ui.pages.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.Layout
import android.text.StaticLayout
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.applyAirTonalButtonStyle
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
internal class FloatingColorSaveButton(private val host: MainUiHost, onSave: () -> Unit) : AppCompatTextView(host) {
    private enum class Visual { IDLE, WAITING, REVEAL, SAVED }
    private data class Letter(val start: Int, val end: Int)
    private val saveLabel = host.getString(R.string.ui_save_color_swatch)
    private val savedLabel = host.getString(R.string.ui_saved)
    private val before = letters(saveLabel)
    private val after = letters(savedLabel)
    private val dotStart = maxOf(170L, 40L + (before.size - 1) * 23L + 80L)
    private val minimumWait = maxOf(740L, dotStart + 300L)
    private val revealDuration = maxOf(620L, 200L + (after.size - 1) * 25L + 380L)
    private val exitCurve = PathInterpolator(0.4f, 0f, 0.8f, 0.3f)
    private val fillCurve = PathInterpolator(0.22f, 0.8f, 0.18f, 1f)
    private val hopCurve = PathInterpolator(0.45f, 0f, 0.35f, 1f)
    private val shape = Path()
    private val revealMask = Path()
    private val check = Path()
    private val checkSegment = Path()
    private val checkMeasure = PathMeasure()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private var status: ColorSaveStatus? = null
    private var visual = Visual.IDLE
    private var animator: ValueAnimator? = null
    private var waitingMs = 0L
    private var revealMs = 0L
    private var successReady = false
    private var successLayout: StaticLayout? = null
    private var successLayoutWidth = 0

    init {
        text = saveLabel
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(host.dp(AirUiTokens.Space.ButtonH),
            host.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
            host.dp(AirUiTokens.Space.ButtonH),
            host.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = host.dp(AirUiTokens.Space.Xxl) }
        isFocusable = true
        setTag(R.id.interaction_anchor, "floating.color.save")
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        host.bindAppearance(this) {
            host.applyAirTonalButtonStyle(it, AirTonalButtonTone.PRIMARY)
            it.invalidate()
        }
        // This button owns the complete press sequence; generic press/pulse animators would fight it.
        setOnClickListener { if (isEnabled) onSave() }
    }

    fun render(next: ColorSaveStatus, canSave: Boolean) {
        isEnabled = canSave
        contentDescription = host.getString(when (next) {
            ColorSaveStatus.SAVED -> R.string.ui_saved
            ColorSaveStatus.SAVING -> R.string.ui_saving
            else -> R.string.ui_save_color_swatch
        })
        ViewCompat.setStateDescription(this, when (next) {
            ColorSaveStatus.FAILED -> host.getString(R.string.ui_save_failed)
            else -> null
        })
        if (status == next) return
        val previous = status
        status = next
        when (next) {
            ColorSaveStatus.SAVING -> startWaiting()
            ColorSaveStatus.SAVED -> {
                if (previous == ColorSaveStatus.SAVING && visual == Visual.WAITING && canAnimateFloatingControl()) {
                    successReady = true
                    if (waitingMs >= minimumWait) startReveal()
                } else settle()
            }
            else -> settle()
        }
        invalidate()
    }

    private fun startWaiting() {
        cancelClock()
        visual = Visual.WAITING
        waitingMs = 0L
        successReady = false
        if (canAnimateFloatingControl()) startClock(waiting = true)
        invalidate()
    }

    private fun startReveal() {
        cancelClock()
        scaleX = 1f
        scaleY = 1f
        visual = Visual.REVEAL
        revealMs = 0L
        startClock(waiting = false)
    }

    private fun startClock(waiting: Boolean) {
        val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (waiting) 920L else revealDuration
            repeatCount = if (waiting) ValueAnimator.INFINITE else 0
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    settle()
                } else if (waiting) {
                    waitingMs = it.currentPlayTime
                    press(waitingMs)
                    if (successReady && waitingMs >= minimumWait) startReveal()
                } else {
                    revealMs = it.currentPlayTime
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) settle()
                }
            })
        }
        animator = clock
        clock.start()
    }

    private fun press(time: Long) {
        fun value(low: Float, pressed: Float, rebound: Float): Float = when {
            time < 87L -> mix(low, pressed, ease(time / 87f))
            time < 229L -> mix(pressed, rebound, ease((time - 87L) / 142f))
            else -> mix(rebound, low, ease((time - 229L) / 81f))
        }
        scaleX = value(1f, 0.972f, 1.008f)
        scaleY = value(1f, 0.94f, 1.02f)
    }

    private fun cancelClock() {
        animator?.removeAllUpdateListeners()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    private fun settle() {
        cancelClock()
        scaleX = 1f
        scaleY = 1f
        visual = when (status) {
            ColorSaveStatus.SAVED -> Visual.SAVED
            ColorSaveStatus.SAVING -> Visual.WAITING
            else -> Visual.IDLE
        }
        successReady = false
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) settle()
        else if (status == ColorSaveStatus.SAVING && animator == null) startWaiting()
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.text = contentDescription
    }

    override fun onDraw(canvas: Canvas) {
        val labelLayout = layout ?: return
        val radius = host.dp(AirUiTokens.Radius.Md).toFloat()
        val buttonWidth = width.toFloat()
        val buttonHeight = height.toFloat()
        shape.rewind()
        shape.addRoundRect(0f, 0f, buttonWidth, buttonHeight, radius, radius, Path.Direction.CW)
        canvas.withClip(shape) {
            if (visual == Visual.SAVED || visual == Visual.REVEAL) {
                val progress = if (visual == Visual.SAVED) 1f else
                    fillCurve.getInterpolation(fraction(revealMs, 80L, 450L))
                revealMask.rewind()
                revealMask.addCircle(
                    buttonWidth / 2f, buttonHeight / 2f,
                    hypot(buttonWidth / 2f, buttonHeight / 2f) * progress, Path.Direction.CW
                )
                withClip(revealMask) {
                    drawColor(SUCCESS_FILL)
                }
            }
            when (visual) {
                Visual.IDLE -> drawLetters(this, labelLayout, before, paddingLeft.toFloat(), entering = false, static = true)
                Visual.WAITING -> {
                    if (animator != null) drawLetters(this, labelLayout, before, paddingLeft.toFloat(), entering = false)
                    drawDots(this)
                }
                Visual.REVEAL -> {
                    drawDots(this)
                    drawSaved(this)
                }
                Visual.SAVED -> drawSaved(this)
            }
        }
    }

    private fun drawLetters(
        canvas: Canvas, textLayout: Layout, letters: List<Letter>, left: Float,
        entering: Boolean, static: Boolean = false
    ) {
        val top = paddingTop + ((height - paddingTop - paddingBottom - textLayout.height).coerceAtLeast(0)) / 2f
        paint.color = if (entering) SUCCESS_INK else currentTextColor
        letters.forEachIndexed { index, letter ->
            val progress = if (static) 1f else if (entering)
                fraction(revealMs, 200L + index * 25L, 380L)
            else fraction(waitingMs, 40L + index * 23L, 180L)
            val alpha: Float
            val dy: Float
            val scale: Float
            if (static) {
                alpha = 1f; dy = 0f; scale = 1f
            } else if (entering) {
                alpha = (progress / 0.65f).coerceIn(0f, 1f)
                dy = if (progress < 0.65f) mix(dp(15f), -dp(2f), ease(progress / 0.65f))
                    else mix(-dp(2f), 0f, ease((progress - 0.65f) / 0.35f))
                scale = 1f
            } else {
                val exit = exitCurve.getInterpolation(progress)
                alpha = 1f - exit; dy = -dp(19f) * exit; scale = 1f - 0.2f * exit
            }
            if (alpha <= 0f) return@forEachIndexed
            val line = textLayout.getLineForOffset(letter.start)
            val rtl = textLayout.isRtlCharAt(letter.start)
            val x = left + textLayout.getPrimaryHorizontal(if (rtl) letter.end else letter.start)
            val y = top + textLayout.getLineBaseline(line)
            canvas.withTranslation(y = dy) {
                scale(scale, scale, x + paint.measureText(textLayout.text, letter.start, letter.end) / 2f, y - textSize * 0.35f)
                paint.alpha = (alpha * 255).toInt()
                drawTextRun(textLayout.text, letter.start, letter.end,
                    textLayout.getLineStart(line), textLayout.getLineEnd(line), x, y, rtl, paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawDots(canvas: Canvas) {
        val collapsing = if (visual == Visual.REVEAL) ease(fraction(revealMs, 0L, 160L)) else 0f
        val opacity = if (animator == null) 1f else ease(fraction(waitingMs, dotStart, 90L))
        ink.style = Paint.Style.FILL
        ink.color = currentTextColor
        ink.alpha = (255 * opacity * (1f - collapsing)).toInt()
        repeat(3) { index ->
            val phase = ((waitingMs - dotStart - index * 90L).coerceAtLeast(0L) % 460L) / 460f
            val hop = if (animator == null) 0f else hopCurve.getInterpolation(
                if (phase < 0.45f) phase / 0.45f else (1f - phase) / 0.55f)
            val x = width / 2f + dp((index - 1) * 12f) * (1f - collapsing)
            val y = height / 2f - dp(5f) * hop * (1f - collapsing)
            val size = dp(3f)
            canvas.withScale(mix(1.08f, 0.92f, hop), mix(0.92f, 1.12f, hop), x, y) {
                drawCircle(x, y, size, ink)
            }
        }
        ink.alpha = 255
    }

    private fun drawSaved(canvas: Canvas) {
        val static = visual == Visual.SAVED
        val icon = dp(18f)
        val gap = dp(8f)
        val available = (width - paddingLeft - paddingRight - icon - gap).toInt().coerceAtLeast(1)
        val labelWidth = paint.measureText(savedLabel).toInt().plus(1).coerceAtMost(available)
        if (successLayout == null || successLayoutWidth != labelWidth) {
            successLayoutWidth = labelWidth
            successLayout = StaticLayout.Builder.obtain(savedLabel, 0, savedLabel.length, paint, labelWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(includeFontPadding).build()
        }
        val left = (width - icon - gap - labelWidth) / 2f
        drawLetters(canvas, requireNotNull(successLayout), after, left + icon + gap, entering = true, static = static)

        val pop = if (static) 1f else fraction(revealMs, 180L, 420L)
        val scale = if (static) 1f else if (pop < 0.6f) mix(0.5f, 1.1f, ease(pop / 0.6f))
            else mix(1.1f, 1f, ease((pop - 0.6f) / 0.4f))
        val draw = if (static) 1f else fraction(revealMs, 250L, 220L)
        canvas.withTranslation(left, (height - icon) / 2f) {
            scale(scale, scale, icon / 2f, icon / 2f)
            rotate(if (static) 0f else mix(-12f, 0f, ease(pop)), icon / 2f, icon / 2f)
            check.rewind()
            check.moveTo(dp(2f), dp(9f))
            check.lineTo(dp(7f), dp(14f))
            check.lineTo(dp(17f), dp(4f))
            checkMeasure.setPath(check, false)
            checkSegment.rewind()
            checkMeasure.getSegment(0f, checkMeasure.length * draw, checkSegment, true)
            ink.color = SUCCESS_INK
            ink.alpha = (255 * (pop / 0.6f).coerceIn(0f, 1f)).toInt()
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = dp(2.4f)
            ink.strokeCap = Paint.Cap.ROUND
            ink.strokeJoin = Paint.Join.ROUND
            drawPath(checkSegment, ink)
            ink.alpha = 255
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun ease(value: Float) = floatingPanelEaseOut.getInterpolation(value.coerceIn(0f, 1f))
    private fun mix(from: Float, to: Float, progress: Float) = from + (to - from) * progress
    private fun fraction(time: Long, start: Long, duration: Long) = ((time - start).toFloat() / duration).coerceIn(0f, 1f)

    private companion object {
        val SUCCESS_FILL = Color.rgb(167, 220, 181)
        val SUCCESS_INK = Color.rgb(22, 63, 41)

        fun letters(text: String): List<Letter> = buildList {
            val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
            var start = boundaries.first()
            var end = boundaries.next()
            while (end != BreakIterator.DONE) {
                add(Letter(start, end))
                start = end
                end = boundaries.next()
            }
        }
    }
}
