package com.andsi.airlyrics.app.host

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.settingGridImpl(vararg items: FloatingSettingTile): LinearLayout {
    val activity = this
    val tileItems = items.toList()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tileItems.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    addView(floatingTile(item).apply {
                        val params = LinearLayout.LayoutParams(0, dp(AirUiTokens.Layout.FloatingTileHeight), 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                            0,
                            if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainUiHost.floatingTileImpl(item: FloatingSettingTile): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Card).toFloat()
            setColor(colorCard)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.FloatingTileIconSize), dp(AirUiTokens.Layout.FloatingTileIconSize)).apply {
                setMargins(0, 0, 0, dp(AirUiTokens.Space.Xxl))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorAccent)
            }
            addView(ImageView(activity).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(AirUiTokens.Layout.StatusIconSize), dp(AirUiTokens.Layout.StatusIconSize), Gravity.CENTER)
            })
        })

        addView(TextView(activity).apply {
            text = item.title
            textSize = AirUiTokens.TextSize.Button + 1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })

        if (item.subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = item.subtitle
                textSize = AirUiTokens.TextSize.Caption
                setTextColor(colorTextMuted)
                maxLines = 1
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
                item.onSubtitleViewCreated?.invoke(this)
            })
        }

        enableSoftPressFeedback(AirUiTokens.Motion.FloatingTilePressScale)
        setOnClickListener { item.onClick(this) }
    }
}

internal fun MainUiHost.floatingFocusBubbleImpl(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: LinearLayout.() -> Unit
): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Radius.Md))
        elevation = dp(AirUiTokens.Space.Xxl).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
            setColor(colorBubble)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        layoutParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(AirUiTokens.Layout.FloatingPanelWidthInset)).coerceAtMost(dp(AirUiTokens.Layout.FloatingPanelMaxWidth)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            setMargins(dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md))
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(TextView(activity).apply {
                    text = title
                    textSize = AirUiTokens.TextSize.Title
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextStrong)
                })
                if (subtitle.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = subtitle
                        textSize = AirUiTokens.TextSize.BodySmall
                        setTextColor(colorTextMuted)
                        setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
                    })
                }
            })
            addView(TextView(activity).apply {
                text = "×"
                gravity = Gravity.CENTER
                textSize = AirUiTokens.TextSize.PageTitle - 4f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMuted)
                layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.DialogCloseSize), dp(AirUiTokens.Layout.DialogCloseSize)).apply {
                    setMargins(dp(AirUiTokens.Space.Xxl), 0, 0, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSurfaceLight)
                }
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener { onClose() }
            })
        })
        addView(spacer(activity, 8))
        content()
    }
}
