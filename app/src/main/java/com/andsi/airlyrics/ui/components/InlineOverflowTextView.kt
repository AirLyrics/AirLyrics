package com.andsi.airlyrics.ui.components

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import com.andsi.airlyrics.design.tokens.AirUiTokens
import androidx.core.widget.doAfterTextChanged
import kotlin.math.ceil

/** Animates drawing only: the ellipsized line's size, baseline and accessibility text stay intact. */
internal class InlineOverflowTextView(context: Context) : AppCompatTextView(context) {
    private var readingLayout: StaticLayout? = null
    internal var readingOffset = 0f
        private set
    internal val isReading get() = readingLayout != null
    internal var onReadingInvalidated: (() -> Unit)? = null

    init {
        setSingleLine(true)
        minLines = 1
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        ellipsize = TextUtils.TruncateAt.END
        doAfterTextChanged { onReadingInvalidated?.invoke() }
    }

    internal fun overflowLayout(): StaticLayout? {
        val available = width - compoundPaddingLeft - compoundPaddingRight
        if (available <= 0) return null
        val displayed = transformationMethod?.getTransformation(text, this) ?: text
        val fullWidth = ceil(Layout.getDesiredWidth(displayed, paint).toDouble()).toInt()
        if (fullWidth <= available) return null
        return StaticLayout.Builder.obtain(displayed, 0, displayed.length, paint, fullWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includeFontPadding)
            .setTextDirection(if (layoutDirection == LAYOUT_DIRECTION_RTL) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()
    }

    internal fun beginReading(fullLayout: StaticLayout): Int {
        readingLayout = fullLayout
        val distance = fullLayout.width - (width - compoundPaddingLeft - compoundPaddingRight)
        readingOffset = if (fullLayout.getParagraphDirection(0) == -1) distance.toFloat() else 0f
        invalidate()
        return distance
    }

    internal fun setReadingProgress(progress: Float, distance: Int) {
        val rtl = readingLayout?.getParagraphDirection(0) == -1
        readingOffset = distance * if (rtl) 1f - progress else progress
        invalidate()
    }

    internal fun endReading() {
        readingLayout = null
        readingOffset = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reserve one typography-based line, not a content-dependent fallback-font height.
        val metrics = paint.fontMetricsInt
        val lineHeight = maxOf(metrics.bottom - metrics.top,
            ceil(textSize * AirUiTokens.Layout.SettingTextLineHeightMultiplier).toInt())
        val height = resolveSize(lineHeight + compoundPaddingTop + compoundPaddingBottom, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        val fullLayout = readingLayout
        if (fullLayout == null) {
            super.onDraw(canvas)
            return
        }
        canvas.withClip(compoundPaddingLeft, 0, width - compoundPaddingRight, height) {
            translate(compoundPaddingLeft - readingOffset, (baseline - fullLayout.getLineBaseline(0)).toFloat())
            paint.color = currentTextColor
            fullLayout.draw(this)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) onReadingInvalidated?.invoke()
    }
}
