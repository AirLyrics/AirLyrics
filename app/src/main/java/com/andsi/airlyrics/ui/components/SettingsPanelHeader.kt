package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

@SuppressLint("ViewConstructor")
internal class SettingsPanelHeader(
    private val host: MainUiHost,
    private val title: String,
    subtitle: String,
    onUndo: (() -> Unit)?,
    onClose: () -> Unit
) : LinearLayout(host) {
    private val undo = onUndo?.let { SettingsPanelUndoButton(host, it) }
    private val undoSlot = undo?.let { button ->
        FrameLayout(host).apply {
            clipChildren = false
            addView(button, FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP
            ))
        }
    }
    private val close = action(R.drawable.ic_air_close, R.string.ui_close, onClose)
    private var back: FrameLayout? = null
    private var accessory: FrameLayout? = null
    private lateinit var titleView: TextView
    private val titleColumn: LinearLayout
    private var titleBelowActions = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        isBaselineAligned = false
        clipChildren = false
        titleColumn = LinearLayout(host).apply {
            orientation = VERTICAL
            setPaddingRelative(0, host.dp(AirUiTokens.Space.Sm), host.dp(AirUiTokens.Space.Xl), 0)
            titleView = TextView(host).apply {
                text = title
                textSize = AirUiTokens.TextSize.Title
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.colorTextStrong)
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }
            addView(titleView)
            if (subtitle.isNotBlank()) addView(TextView(host).apply {
                text = subtitle
                textSize = AirUiTokens.TextSize.BodySmall
                setTextColor(host.colorTextMuted)
                setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
            })
        }
        addView(titleColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        undoSlot?.let { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
        addView(close)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        titleColumn.setPaddingRelative(0, host.dp(AirUiTokens.Space.Sm), host.dp(AirUiTokens.Space.Xl), 0)
        val widestWord = titleView.text.splitToSequence(' ', '\n')
            .maxOfOrNull { titleView.paint.measureText(it) } ?: 0f
        val titleWidth = android.text.Layout.getDesiredWidth(titleView.text, titleView.paint)
        val titlePadding = titleColumn.paddingStart + titleColumn.paddingEnd
        val actionHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (undo != null && undoSlot != null) {
            val occupied = actionHeight * (1 + listOfNotNull(back, accessory).count { it.visibility != GONE })
            val free = availableWidth - occupied
            val labelWidth = undo.labelWidth
            val roomWithUndo = free - labelWidth - titlePadding
            val minTitleWidth = if (titleWidth <= roomWithUndo) titleWidth else widestWord
            val showLabel = free >= labelWidth
            val targetBudget = if (showLabel) {
                (roomWithUndo - minTitleWidth).toInt().coerceIn(0, undo.maxTargetExtraWidth)
            } else 0
            undo.setPresentation(showLabel, targetBudget)
            undoSlot.layoutParams.width = if (showLabel) labelWidth + targetBudget else actionHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        titleBelowActions = widestWord > titleView.measuredWidth
        if (titleBelowActions) {
            titleColumn.setPaddingRelative(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
            titleColumn.measure(
                MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            setMeasuredDimension(measuredWidth, resolveSize(
                paddingTop + actionRowHeight + titleColumn.measuredHeight + paddingBottom,
                heightMeasureSpec
            ))
        }
    }

    private val actionRowHeight: Int
        get() = maxOf(close.measuredHeight, undoSlot?.measuredHeight ?: 0)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!titleBelowActions) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun placeAction(view: View, start: Int) {
            val x = if (rtl) width - paddingRight - start - view.measuredWidth else paddingLeft + start
            view.layout(x, paddingTop, x + view.measuredWidth, paddingTop + view.measuredHeight)
        }
        var end = width - paddingLeft - paddingRight
        for (index in childCount - 1 downTo 0) {
            val action = getChildAt(index)
            if (action === titleColumn || action === back || action.isGone) continue
            end -= action.measuredWidth
            placeAction(action, end)
        }
        back?.takeIf { it.visibility != GONE }?.let { placeAction(it, 0) }
        val titleTop = paddingTop + actionRowHeight
        titleColumn.layout(paddingLeft, titleTop, width - paddingRight, titleTop + titleColumn.measuredHeight)
    }

    private fun action(icon: Int, label: Int, onClick: () -> Unit) = FrameLayout(host).apply {
        layoutParams = LayoutParams(host.dp(AirUiTokens.Layout.IconTouchSize), host.dp(AirUiTokens.Layout.IconTouchSize))
        contentDescription = host.getString(label)
        tooltipText = contentDescription
        isFocusable = true
        addView(host.airIconView(icon, host.colorTextMuted).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(host.colorSurfaceLight)
            }
        }, FrameLayout.LayoutParams(host.dp(AirUiTokens.Layout.DialogCloseSize), host.dp(AirUiTokens.Layout.DialogCloseSize), Gravity.CENTER))
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener { onClick() }
    }

    fun updateNavigation(pageTitle: String?, onBack: (() -> Unit)?) {
        titleView.text = pageTitle ?: title
        if (onBack != null && back == null) {
            back = action(R.drawable.ic_air_arrow_back, R.string.ui_back, onBack)
            addView(back, 0)
        }
        back?.apply {
            visibility = if (onBack == null) GONE else VISIBLE
            setOnClickListener { onBack?.invoke() }
        }
    }

    fun updateHeaderAction(view: View?) {
        accessory?.let(::removeView)
        accessory = view?.let { action ->
            (action.parent as? ViewGroup)?.removeView(action)
            FrameLayout(host).apply {
                layoutParams = LayoutParams(host.dp(AirUiTokens.Layout.IconTouchSize), host.dp(AirUiTokens.Layout.IconTouchSize))
                addView(action, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }.also { addView(it, indexOfChild(undoSlot ?: close)) }
        }
        requestLayout()
    }

    fun updateUndoAction(action: SettingsUndoAction?) { undo?.updateAction(action) }

    fun animateUndo() { undo?.animateUndo() }

    fun finishUndoAnimation() { undo?.finishAnimation() }
}
