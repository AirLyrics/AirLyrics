package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import android.widget.TextView
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorTextStrong

/** A transient, non-modal label above its source button. */
internal class LyricsSourceTooltip(
    private val host: MainUiHost,
    private val anchor: View
) {
    private var popup: PopupWindow? = null
    private val dismiss = Runnable { popup?.dismiss() }

    fun show(title: CharSequence?) {
        popup?.dismiss()
        if (!anchor.isAttachedToWindow || title.isNullOrBlank()) return

        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val margin = host.dp(12)
        val maxWidth = minOf(host.dp(280), frame.width() - margin * 2).coerceAtLeast(1)
        val label = TextView(host).apply {
            text = title
            textSize = AirUiTokens.TextSize.BodySmall
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(host.colorTextStrong)
            setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
                setColor(host.colorCard)
                setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
            }
            measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val left = (location[0] + (anchor.width - label.measuredWidth) / 2)
            .coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - margin - label.measuredWidth))
        // START aligns to the anchor's right edge in RTL; keep the clamped screen position.
        val xOffset = left - location[0] +
            if (anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL) label.measuredWidth - anchor.width else 0
        val gap = host.dp(8)
        val fitsAbove = location[1] - label.measuredHeight - gap >= frame.top
        val window = PopupWindow(label, label.measuredWidth, label.measuredHeight, false).apply {
            setBackgroundDrawable(label.background)
            elevation = host.dp(6).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        val observer = anchor.viewTreeObserver
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { window.dismiss() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { window.dismiss() }
        }
        window.setOnDismissListener {
            anchor.removeCallbacks(dismiss)
            anchor.removeOnAttachStateChangeListener(attachListener)
            if (observer.isAlive) observer.removeOnScrollChangedListener(scrollListener)
            if (popup === window) popup = null
        }
        popup = window
        window.showAsDropDown(
            anchor,
            xOffset,
            if (fitsAbove) -anchor.height - label.measuredHeight - gap else gap,
            android.view.Gravity.START
        )
        anchor.addOnAttachStateChangeListener(attachListener)
        observer.addOnScrollChangedListener(scrollListener)
        anchor.postDelayed(dismiss, 2500L)
    }
}
