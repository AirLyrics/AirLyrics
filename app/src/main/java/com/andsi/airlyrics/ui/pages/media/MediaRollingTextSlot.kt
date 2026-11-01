package com.andsi.airlyrics.ui.pages.media

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.text.Layout
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.expandableText
import com.andsi.airlyrics.ui.model.MainUiHost
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class MediaRollingRole(val durationMs: Long, val staggerMs: Long, val maxGroups: Int) {
    TITLE(AirUiTokens.Motion.MediaTextSwapMs, 13L, 8),
    DETAIL(AirUiTokens.Motion.MediaSecondaryTextSwapMs, 10L, 6)
}

/**
 * A single accessible TextView supplies the final layout. During a change, two one-time snapshots
 * roll through its measured line windows; they never become children or participate in layout.
 *
 * Motion Prompt #156 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#156
 * Motion Prompt #005 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#005
 * Motion Prompt #002 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#002
 * These are motion references; this is a native Android rendering, not a Canvas port.
 */
@SuppressLint("ViewConstructor")
internal class MediaRollingTextSlot(
    host: MainUiHost,
    val textView: TextView,
    private val role: MediaRollingRole = MediaRollingRole.DETAIL
) : ViewGroup(host) {
    private data class Strip(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val delayIndex: Int
    ) {
        val height: Int get() = bottom - top
    }

    private data class TextFrame(val bitmap: Bitmap, val left: Int, val strips: List<Strip>)
    private data class StableEdges(val prefixEnd: Int, val suffixStart: Int)
    private data class StableInk(
        val bitmap: Bitmap,
        val source: Rect,
        val fromX: Float,
        val toX: Float,
        val top: Float
    )

    private val content = host.expandableText(textView)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destinationRect = RectF()
    private var displayedText = textView.text.toString()
    private var animator: ValueAnimator? = null
    private var outgoing: TextFrame? = null
    private var incoming: TextFrame? = null
    private var stableInk = emptyList<StableInk>()
    private var motionTimeMs = 0f
    private var motionMaxDelayMs = 0f
    private var contentWidth = -1
    private var contentHeight = 0
    private var heightOverride: Int? = null
    private var fromHeight = 0
    private var toHeight = 0
    private var motionWidth = 0

    init {
        layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(
        text: String,
        delayMs: Long = 0L,
        stablePrefix: String? = null,
        stableSuffix: String? = null
    ) {
        if (displayedText == text) return
        val canAnimate = ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow &&
            width > 0 && textView.width > 0 && textView.maxLines <= 2
        val oldFrame = if (canAnimate) {
            if (animator != null) captureVisibleFrame() else captureTextFrame()
        } else null
        val oldEdges = if (canAnimate && animator == null) stableEdges(displayedText, stablePrefix, stableSuffix) else null
        val oldHeight = heightOverride ?: measuredHeight
        clearMotion(requestFinalLayout = false)
        displayedText = text
        textView.text = text
        contentWidth = -1
        if (oldFrame == null) {
            requestLayout()
            return
        }

        measureContent(width)
        val newFrame = captureTextFrame()
        if (newFrame == null) {
            requestLayout()
            return
        }
        val newEdges = stableEdges(text, stablePrefix, stableSuffix)
        if (oldEdges != null && newEdges != null) {
            stableInk = buildStableInk(oldFrame, newFrame, oldEdges, newEdges, stablePrefix, stableSuffix)
            outgoing = oldFrame.clippedTo(oldEdges.prefixEnd, oldEdges.suffixStart)
            incoming = newFrame.clippedTo(newEdges.prefixEnd, newEdges.suffixStart)
        } else {
            outgoing = oldFrame
            incoming = newFrame
        }
        fromHeight = oldHeight
        toHeight = contentHeight
        motionWidth = width
        heightOverride = oldHeight
        motionMaxDelayMs = maxOf(
            outgoing?.strips?.maxOfOrNull(Strip::delayIndex) ?: 0,
            incoming?.strips?.maxOfOrNull(Strip::delayIndex) ?: 0
        ) * role.staggerMs.toFloat()
        motionTimeMs = 0f
        textView.alpha = 0f
        val running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = role.durationMs
            startDelay = delayMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (animator !== animation) return@addUpdateListener
                val fraction = animation.animatedValue as Float
                motionTimeMs = role.durationMs * fraction
                updateHeight(fraction)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) clearMotion(requestFinalLayout = true)
                }
            })
        }
        animator = running
        running.start()
    }

    fun finish() = clearMotion(requestFinalLayout = true)

    override fun onDetachedFromWindow() {
        clearMotion(requestFinalLayout = false)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        // The new text was measured once in show(). Repeated provisional width passes during
        // the height animation must not remeasure it or discard its captured line windows.
        if (animator == null &&
            (contentWidth != availableWidth || content.isLayoutRequested || contentHeight == 0)) {
            measureContent(availableWidth)
        }
        setMeasuredDimension(
            resolveSize(availableWidth, widthMeasureSpec),
            resolveSize(heightOverride ?: contentHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Weighted LinearLayouts may measure this slot at a provisional width before its final
        // width. Only a real layout-width change invalidates the snapshots captured in show().
        if (animator != null && width != motionWidth) {
            clearMotion(requestFinalLayout = false)
            measureContent(width)
            requestLayout()
        } else {
            content.layout(0, 0, width, contentHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (outgoing != null && incoming != null) drawMotion(canvas)
    }

    private fun measureContent(availableWidth: Int) {
        val exactWidth = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY)
        content.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        contentWidth = availableWidth
        contentHeight = content.measuredHeight
        content.layout(0, 0, availableWidth, contentHeight)
    }

    private fun captureTextFrame(): TextFrame? {
        val w = textView.width
        val h = textView.height
        if (w <= 0 || h <= 0) return null
        val bitmap = createBitmap(w, h)
        val wasAlpha = textView.alpha
        textView.alpha = 1f
        textView.draw(Canvas(bitmap))
        textView.alpha = wasAlpha
        return TextFrame(bitmap, textView.left, textStrips(w, h))
    }

    private fun captureVisibleFrame(): TextFrame? {
        val w = width
        val h = (heightOverride ?: height).coerceAtLeast(0)
        if (w <= 0 || h <= 0) return null
        val bitmap = createBitmap(w, h)
        drawMotion(Canvas(bitmap))
        return TextFrame(bitmap, 0, listOf(Strip(0, 0, w, h, 0)))
    }

    private fun stableEdges(text: String, prefix: String?, suffix: String?): StableEdges? {
        if (prefix == null && suffix == null) return null
        val layout = textView.layout ?: return null
        if (layout.lineCount != 1 || layout.getEllipsisCount(0) > 0 ||
            layout.getParagraphDirection(0) != Layout.DIR_LEFT_TO_RIGHT ||
            (prefix != null && !text.startsWith(prefix)) ||
            (suffix != null && !text.endsWith(suffix))) return null
        val w = textView.width
        val prefixEnd = if (prefix == null) 0 else
            (textView.compoundPaddingLeft + layout.getPrimaryHorizontal(prefix.length)).roundToInt().coerceIn(0, w)
        val suffixStart = if (suffix == null) w else
            (textView.compoundPaddingLeft + layout.getPrimaryHorizontal(text.length - suffix.length))
                .roundToInt().coerceIn(0, w)
        if (suffixStart <= prefixEnd) return null
        return StableEdges(prefixEnd, suffixStart)
    }

    private fun TextFrame.clippedTo(start: Int, end: Int) = copy(strips = strips.mapNotNull { strip ->
        val left = maxOf(strip.left, start)
        val right = minOf(strip.right, end)
        if (right > left) strip.copy(left = left, right = right) else null
    })

    private fun buildStableInk(
        old: TextFrame,
        next: TextFrame,
        oldEdges: StableEdges,
        nextEdges: StableEdges,
        prefix: String?,
        suffix: String?
    ): List<StableInk> = buildList {
        if (prefix != null && oldEdges.prefixEnd > 0) {
            add(StableInk(old.bitmap, Rect(0, 0, oldEdges.prefixEnd, old.bitmap.height),
                old.left.toFloat(), next.left.toFloat(), 0f))
        }
        if (suffix != null && oldEdges.suffixStart < old.bitmap.width) {
            add(StableInk(old.bitmap,
                Rect(oldEdges.suffixStart, 0, old.bitmap.width, old.bitmap.height),
                (old.left + oldEdges.suffixStart).toFloat(),
                (next.left + nextEdges.suffixStart).toFloat(), 0f))
        }
    }

    private fun textStrips(w: Int, h: Int): List<Strip> {
        val layout = textView.layout ?: return listOf(Strip(0, 0, w, h, 0))
        val lineCount = layout.lineCount.coerceIn(1, 2)
        val result = mutableListOf<Strip>()
        for (line in 0 until lineCount) {
            val top = if (line == 0) 0 else (textView.compoundPaddingTop + layout.getLineTop(line)).coerceIn(0, h)
            val bottom = if (line == lineCount - 1) h else
                (textView.compoundPaddingTop + layout.getLineBottom(line)).coerceIn(top, h)
            if (bottom <= top) continue
            result += lineStrips(layout, line, w, top, bottom)
        }
        return result.ifEmpty { listOf(Strip(0, 0, w, h, 0)) }
    }

    private fun lineStrips(layout: Layout, line: Int, w: Int, top: Int, bottom: Int): List<Strip> {
        val fallback = listOf(Strip(0, top, w, bottom, line * 2))
        if (layout.getParagraphDirection(line) != Layout.DIR_LEFT_TO_RIGHT ||
            layout.getEllipsisCount(line) > 0) return fallback
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line).let { if (it > start && layout.text[it - 1] == '\n') it - 1 else it }
        if (end <= start || end - start > 96) return fallback
        // Old Android ICU releases may disagree about newer emoji graphemes. Keep that line intact.
        if ((start until end).any { index ->
                val char = layout.text[index]
                Character.isSurrogate(char) || char == '\u200D' ||
                    Character.getType(char) == Character.NON_SPACING_MARK.toInt()
            }) return fallback
        val boundaries = mutableListOf(start)
        val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
        iterator.setText(layout.text.toString())
        var boundary = iterator.following(start)
        while (boundary != BreakIterator.DONE && boundary < end) {
            boundaries += boundary
            boundary = iterator.next()
        }
        boundaries += end
        val graphemes = boundaries.size - 1
        if (graphemes <= 1) return fallback
        val groupSize = ceil(graphemes.toDouble() / role.maxGroups).toInt().coerceAtLeast(1)
        val leftEdge = (textView.compoundPaddingLeft + layout.getLineLeft(line)).roundToInt().coerceIn(0, w)
        val rightEdge = (textView.compoundPaddingLeft + layout.getLineRight(line)).roundToInt().coerceIn(leftEdge, w)
        if (rightEdge <= leftEdge) return fallback
        val strips = mutableListOf<Strip>()
        var left = leftEdge
        var groupIndex = 0
        var graphemeIndex = groupSize
        while (graphemeIndex < graphemes) {
            val right = (textView.compoundPaddingLeft + layout.getPrimaryHorizontal(boundaries[graphemeIndex]))
                .roundToInt().coerceIn(0, w)
            if (right <= left || right >= rightEdge) return fallback
            strips += Strip(left, top, right, bottom, groupIndex + line * 2)
            left = right
            groupIndex++
            graphemeIndex += groupSize
        }
        strips += Strip(left, top, rightEdge, bottom, groupIndex + line * 2)
        return strips
    }

    private fun drawMotion(canvas: Canvas) {
        val old = outgoing ?: return
        val next = incoming ?: return
        canvas.withClip(0, 0, width, heightOverride ?: height) {
            drawFrame(this, old, incomingFrame = false)
            drawFrame(this, next, incomingFrame = true)
            val progress = (motionTimeMs / role.durationMs).coerceIn(0f, 1f)
            val eased = progress * progress * (3f - 2f * progress)
            stableInk.forEach { ink ->
                val x = ink.fromX + (ink.toX - ink.fromX) * eased
                destinationRect.set(x, ink.top, x + ink.source.width(), ink.top + ink.source.height())
                drawBitmap(ink.bitmap, ink.source, destinationRect, bitmapPaint)
            }
        }
    }

    private fun drawFrame(canvas: Canvas, frame: TextFrame, incomingFrame: Boolean) {
        val travelDuration = (role.durationMs - motionMaxDelayMs).coerceAtLeast(1f)
        frame.strips.forEach { strip ->
            val local = ((motionTimeMs - strip.delayIndex * role.staggerMs) / travelDuration).coerceIn(0f, 1f)
            val eased = local * local * (3f - 2f * local) // the two rows stay one window apart
            val landing = if (incomingFrame && local > 0.78f) {
                eased + 0.055f * kotlin.math.sin(Math.PI.toFloat() * (local - 0.78f) / 0.22f)
            } else eased
            val dy = if (incomingFrame) strip.height * (1f - landing) else -strip.height * eased
            canvas.withClip(frame.left + strip.left, strip.top, frame.left + strip.right, strip.bottom) {
                drawBitmap(frame.bitmap, frame.left.toFloat(), dy, bitmapPaint)
            }
        }
    }

    private fun updateHeight(fraction: Float) {
        if (fromHeight == toHeight) return
        val heightProgress = if (toHeight > fromHeight) {
            ((fraction / 0.78f).coerceIn(0f, 1f))
        } else {
            ((fraction - 0.12f) / 0.88f).coerceIn(0f, 1f)
        }
        val eased = 1f - (1f - heightProgress).let { it * it * it }
        val nextHeight = (fromHeight + (toHeight - fromHeight) * eased).roundToInt()
        if (heightOverride != nextHeight) {
            heightOverride = nextHeight
            requestLayout()
        }
    }

    private fun clearMotion(requestFinalLayout: Boolean) {
        val previous = animator
        animator = null
        previous?.cancel()
        outgoing = null
        incoming = null
        stableInk = emptyList()
        motionWidth = 0
        textView.alpha = 1f
        if (heightOverride != null) {
            heightOverride = null
            if (requestFinalLayout) requestLayout()
        }
        invalidate()
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
