package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

/** Actions retain their touch targets; narrow titles can wrap below the action row. */
@SuppressLint("ViewConstructor")
internal class SettingsPanelHeader(
    private val host: MainUiHost,
    private val title: String,
    subtitle: String,
    onReset: (() -> Unit)?,
    onClose: () -> Unit
) : LinearLayout(host) {
    private var reset: FrameLayout? = null
    private var back: FrameLayout? = null
    private var accessory: FrameLayout? = null
    private var resetState: Pair<Boolean, Boolean>? = null
    private lateinit var titleView: TextView
    private val titleColumn: LinearLayout
    private var titleBelowActions = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
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
        if (onReset != null) {
            reset = action(R.drawable.ic_air_reset, R.string.ui_reset, onReset)
            addView(reset)
            updateResetAction(enabled = false, isUndo = false)
        }
        addView(action(R.drawable.ic_air_close, R.string.ui_close, onClose))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        titleColumn.setPaddingRelative(0, host.dp(AirUiTokens.Space.Sm), host.dp(AirUiTokens.Space.Xl), 0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val wordWidth = titleView.text.splitToSequence(' ', '\n')
            .maxOfOrNull { titleView.paint.measureText(it) } ?: 0f
        titleBelowActions = wordWidth > titleView.measuredWidth
        if (titleBelowActions) {
            titleColumn.setPaddingRelative(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
            titleColumn.measure(
                MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val actionHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
            setMeasuredDimension(measuredWidth, resolveSize(
                paddingTop + actionHeight + titleColumn.measuredHeight + paddingBottom,
                heightMeasureSpec
            ))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!titleBelowActions) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun placeAction(view: FrameLayout, start: Int) {
            val x = if (rtl) width - paddingRight - start - view.measuredWidth else paddingLeft + start
            view.layout(x, paddingTop, x + view.measuredWidth, paddingTop + view.measuredHeight)
        }
        val availableWidth = width - paddingLeft - paddingRight
        val close = getChildAt(childCount - 1) as FrameLayout
        var end = availableWidth - close.measuredWidth
        placeAction(close, end)
        reset?.takeIf { it.visibility != GONE }?.let {
            end -= it.measuredWidth
            placeAction(it, end)
        }
        accessory?.let {
            end -= it.measuredWidth
            placeAction(it, end)
        }
        back?.takeIf { it.visibility != GONE }?.let { placeAction(it, 0) }
        val titleTop = paddingTop + host.dp(AirUiTokens.Layout.IconTouchSize)
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

    fun updateHeaderAction(view: View?, hideReset: Boolean) {
        accessory?.let(::removeView)
        accessory = view?.let { action ->
            (action.parent as? ViewGroup)?.removeView(action)
            FrameLayout(host).apply {
                layoutParams = LayoutParams(host.dp(AirUiTokens.Layout.IconTouchSize), host.dp(AirUiTokens.Layout.IconTouchSize))
                addView(action, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }.also { addView(it, childCount - 1) }
        }
        reset?.visibility = if (hideReset) GONE else VISIBLE
        requestLayout()
    }

    fun updateResetAction(enabled: Boolean, isUndo: Boolean) {
        val state = enabled to isUndo
        if (resetState == state) return
        resetState = state
        reset?.apply {
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
            isEnabled = enabled
            val targetAlpha = if (enabled) 1f else 0.34f
            if (canAnimateFloatingControl()) {
                animate().alpha(targetAlpha)
                    .setStartDelay(0)
                    .setDuration(AirUiTokens.Motion.PressUpMs)
                    .setInterpolator(floatingPanelEaseOut)
                    .start()
            } else alpha = targetAlpha
            contentDescription = host.getString(if (isUndo) R.string.ui_undo else R.string.ui_reset)
            tooltipText = contentDescription
            (getChildAt(0) as ImageView).apply {
                setImageResource(if (isUndo) R.drawable.ic_air_undo else R.drawable.ic_air_reset)
                imageTintList = ColorStateList.valueOf(if (isUndo) host.colorAccentMint else host.colorAccent)
            }
        }
    }
}
