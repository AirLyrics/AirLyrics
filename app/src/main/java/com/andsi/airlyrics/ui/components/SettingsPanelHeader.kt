package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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

/** Fixed-size actions never compete with translations or move to another row. */
@SuppressLint("ViewConstructor")
internal class SettingsPanelHeader(
    private val host: MainUiHost,
    title: String,
    subtitle: String,
    onReset: (() -> Unit)?,
    onClose: () -> Unit
) : LinearLayout(host) {
    private var reset: FrameLayout? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        addView(LinearLayout(host).apply {
            orientation = VERTICAL
            setPaddingRelative(0, host.dp(AirUiTokens.Space.Sm), host.dp(AirUiTokens.Space.Xl), 0)
            addView(TextView(host).apply {
                text = title
                textSize = AirUiTokens.TextSize.Title
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.colorTextStrong)
                applyTextPolicy(TextDisplayPolicy.CompactTitle)
                setOnClickListener {
                    if (isTextTruncated()) host.showFullText(null, text.toString())
                }
            })
            if (subtitle.isNotBlank()) addView(TextView(host).apply {
                text = subtitle
                textSize = AirUiTokens.TextSize.BodySmall
                setTextColor(host.colorTextMuted)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (onReset != null) {
            reset = action(R.drawable.ic_air_reset, R.string.ui_reset, onReset)
            addView(reset)
            updateResetAction(enabled = false, isUndo = false)
        }
        addView(action(R.drawable.ic_air_close, R.string.ui_close, onClose))
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

    fun updateResetAction(enabled: Boolean, isUndo: Boolean) {
        reset?.apply {
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.34f
            contentDescription = host.getString(if (isUndo) R.string.ui_undo else R.string.ui_reset)
            tooltipText = contentDescription
            (getChildAt(0) as ImageView).apply {
                setImageResource(if (isUndo) R.drawable.ic_air_undo else R.drawable.ic_air_reset)
                imageTintList = ColorStateList.valueOf(if (isUndo) host.colorAccentMint else host.colorAccent)
            }
        }
    }
}
