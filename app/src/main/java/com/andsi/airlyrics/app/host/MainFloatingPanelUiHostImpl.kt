package com.andsi.airlyrics.app.host

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.model.FloatingFocusBubbleHandle
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainUiHost.settingGridImpl(vararg items: FloatingSettingTile): LinearLayout {
    val activity = this
    val tileItems = items.toList()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tileItems.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                // The row derives its height from the tallest tile, then remeasures these evenly.
                rowItems.forEachIndexed { index, item ->
                    addView(floatingTile(item).apply {
                        val params = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            1f
                        )
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
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            1f
                        ).apply {
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
        minimumHeight = dp(AirUiTokens.Layout.FloatingTileMinHeight)
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
            addView(airIconView(item.iconRes, colorIconOnAccent).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconSize),
                    dp(AirUiTokens.Layout.IconSize),
                    Gravity.CENTER
                )
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
    onReset: (() -> Unit)?,
    onClose: () -> Unit,
    content: LinearLayout.() -> Unit
): FloatingFocusBubbleHandle {
    val activity = this
    lateinit var contentContainer: LinearLayout
    var resetButton: TextView? = null
    var resetActionInitialized = false
    var resetActionEnabled = false
    var resetActionIsUndo = false
    var resetActionAnimating = false
    var resetActionAnimationGeneration = 0
    val bubble = LinearLayout(this).apply {
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
            if (onReset != null) {
                addView(TextView(activity).apply {
                    resetButton = this
                    setText(R.string.ui_reset)
                    textSize = AirUiTokens.TextSize.BodySmall
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(colorAccent)
                    setPadding(
                        dp(AirUiTokens.Space.ButtonH),
                        0,
                        dp(AirUiTokens.Space.ButtonH),
                        0
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        dp(AirUiTokens.Layout.FloatingResetActionWidth),
                        dp(AirUiTokens.Layout.DialogCloseSize)
                    ).apply {
                        setMargins(dp(AirUiTokens.Space.Xxl), 0, 0, 0)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
                        setColor(colorSurfaceLight)
                    }
                    enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                    setOnClickListener { onReset() }
                })
            }
            addView(airIconView(
                iconRes = R.drawable.ic_air_close,
                tint = colorTextMuted,
                contentDescription = getString(R.string.ui_close)
            ).apply {
                layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.DialogCloseSize), dp(AirUiTokens.Layout.DialogCloseSize)).apply {
                    setMargins(dp(AirUiTokens.Space.PageH), 0, 0, 0)
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
        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            content()
        }
        addView(contentContainer)
    }

    return FloatingFocusBubbleHandle(
        view = bubble,
        rebuildContent = {
            contentContainer.removeAllViews()
            contentContainer.content()
        },
        updateResetAction = { enabled, isUndo ->
            resetButton?.apply {
                val enabledChanged = resetActionEnabled != enabled
                resetActionEnabled = enabled
                if (!resetActionInitialized) {
                    resetActionInitialized = true
                    resetActionIsUndo = isUndo
                    setText(if (isUndo) R.string.ui_undo else R.string.ui_reset)
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.34f
                    setTextColor(if (isUndo) colorAccentMint else colorAccent)
                } else if (resetActionIsUndo != isUndo) {
                    resetActionIsUndo = isUndo
                    resetActionAnimating = true
                    val generation = ++resetActionAnimationGeneration
                    animate().cancel()
                    isEnabled = false
                    animate()
                        .alpha(0f)
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .setDuration(AirUiTokens.Layout.FastFadeMs)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (generation != resetActionAnimationGeneration) return@withEndAction
                            setText(if (resetActionIsUndo) R.string.ui_undo else R.string.ui_reset)
                            setTextColor(if (resetActionIsUndo) colorAccentMint else colorAccent)
                            scaleX = 0.9f
                            scaleY = 0.9f
                            animate()
                                .alpha(if (resetActionEnabled) 1f else 0.34f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(AirUiTokens.Layout.RestoreFadeMs)
                                .setInterpolator(OvershootInterpolator(AirUiTokens.Motion.OvershootSoft))
                                .withEndAction {
                                    if (generation == resetActionAnimationGeneration) {
                                        resetActionAnimating = false
                                        isEnabled = resetActionEnabled
                                    }
                                }
                                .start()
                        }
                        .start()
                } else if (!resetActionAnimating && enabledChanged) {
                    isEnabled = enabled
                    animate()
                        .alpha(if (enabled) 1f else 0.34f)
                        .setDuration(AirUiTokens.Layout.FastFadeMs)
                        .start()
                }
            }
        }
    )
}
