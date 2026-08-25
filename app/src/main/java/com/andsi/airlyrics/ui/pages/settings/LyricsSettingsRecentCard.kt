package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.async.LatestUiTaskRunner
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.model.CurrentMediaUiInfo
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.LocalLyricsUiChange
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

private val recentLyricsLoadRunner = LatestUiTaskRunner()

internal fun createRecentLyricsCard(activity: MainUiHost): RefreshableSettingsCard = with(activity) {
    val listBody = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val feedback = TextView(this).apply {
        text = ""
        textSize = AirUiTokens.TextSize.Caption
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentMint)
        gravity = Gravity.CENTER_VERTICAL
    }
    var closeHeaderHint: () -> Unit = {}

    lateinit var populateRecentLyrics: (Boolean, Boolean) -> Unit

    fun renderLyricsList(
        currentItem: LocalLyricsUiItem?,
        recentLyrics: List<LocalLyricsUiItem>,
        media: CurrentMediaUiInfo?,
        showRefreshFeedback: Boolean
    ) {
        listBody.removeAllViews()

        if (currentItem != null) {
            listBody.addView(localLyricsRow(currentItem, onLyricsChanged = { change ->
                closeHeaderHint()
                populateRecentLyrics(false, false)
                if (change == LocalLyricsUiChange.SAVED) {
                    playLocalRefreshFeedback(activity, listBody, feedback, getString(R.string.ui_applied))
                }
            }, badgeText = getString(R.string.ui_now_playing)))
        } else {
            listBody.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(AirUiTokens.Space.Xxl), 0, 0)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
                    setColor(colorSurfaceLight)
                    setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
                }
                addView(TextView(activity).apply {
                    text = getString(R.string.ui_now_playing)
                    textSize = AirUiTokens.TextSize.Tiny
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                    setPadding(0, 0, 0, dp(AirUiTokens.Space.Sm))
                })
                addView(TextView(activity).apply {
                    text = media?.displayText ?: getString(R.string.ui_no_active_media_found_short)
                    textSize = AirUiTokens.TextSize.Body
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextStrong)
                })
                addView(TextView(activity).apply {
                    text = getString(R.string.ui_no_plain_lrc_bound_to_song)
                    textSize = AirUiTokens.TextSize.Caption
                    setTextColor(colorTextMuted)
                    setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
                })
            })
        }

        val otherRecentLyrics = recentLyrics.filterNot { item ->
            if (currentItem == null) {
                false
            } else if (currentItem.indexKey.isNotBlank() && item.indexKey.isNotBlank()) {
                currentItem.indexKey == item.indexKey
            } else {
                currentItem.name == item.name
            }
        }

        if (currentItem == null && otherRecentLyrics.isEmpty()) {
            listBody.addView(normalText(activity, getString(R.string.ui_recent_lyrics_empty_hint)))
        } else {
            otherRecentLyrics.forEach { item ->
                listBody.addView(localLyricsRow(item, onLyricsChanged = { change ->
                    closeHeaderHint()
                    populateRecentLyrics(false, false)
                    if (change == LocalLyricsUiChange.SAVED) {
                        playLocalRefreshFeedback(activity, listBody, feedback, getString(R.string.ui_applied))
                    }
                }))
            }
        }

        if (currentItem != null || recentLyrics.isNotEmpty()) {
            listBody.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
                setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
                isClickable = true
                isFocusable = true
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    closeHeaderHint()
                    uiActions.openSettingsSubPage(SettingsSubPage.SAVED_LYRICS)
                }

                addView(TextView(activity).apply {
                    text = getString(R.string.ui_view_all)
                    textSize = AirUiTokens.TextSize.Body
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                })
                addView(airIconView(
                    iconRes = R.drawable.ic_air_chevron_right,
                    tint = colorAccent
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dp(AirUiTokens.Layout.IconSize),
                        dp(AirUiTokens.Layout.IconSize)
                    ).apply {
                        setMargins(dp(AirUiTokens.Space.Sm), 0, 0, 0)
                    }
                })
            })
        }

        if (showRefreshFeedback) {
            val message = if (recentLyrics.isNotEmpty()) {
                getString(R.string.ui_refreshed) + " " + recentLyrics.size + " " + getString(R.string.ui_songs)
            } else {
                getString(R.string.ui_refreshed)
            }
            playLocalRefreshFeedback(activity, listBody, feedback, message)
        }
    }

    populateRecentLyrics = { showRefreshFeedback, preserveContent ->
        if (showRefreshFeedback) {
            showInlineRefreshFeedback(feedback, getString(R.string.ui_refreshing))
        } else if (!preserveContent) {
            listBody.removeAllViews()
            listBody.addView(normalText(activity, getString(R.string.ui_loading)))
        }

        recentLyricsLoadRunner.submit(
            runtime = activity,
            load = { recentLyricsState(limit = 8) }
        ) { state ->
            renderLyricsList(state.currentItem, state.recentLyrics, state.media, showRefreshFeedback)
        }
    }

    populateRecentLyrics(false, false)

    val cardView = card(activity) {
        val hintText = TextView(activity).apply {
            text = getString(R.string.ui_tap_to_preview_or_edit)
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMuted)
            alpha = 0f
            isVisible = false
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        }
        closeHeaderHint = {
            if (hintText.isVisible || hintText.alpha > 0f) {
                hintText.animate().cancel()
                hintText.alpha = 0f
                hintText.isVisible = false
            }
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                    addView(bigText(activity, getString(R.string.ui_recent_local_lyrics)).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    addView(airIconView(
                        iconRes = R.drawable.ic_air_info,
                        tint = colorTextMuted,
                        contentDescription = getString(R.string.ui_recent_local_lyrics)
                    ).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(colorSurfaceLight)
                            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            dp(AirUiTokens.Layout.CompactIconButtonSize),
                            dp(AirUiTokens.Layout.CompactIconButtonSize)
                        ).apply {
                            setMargins(dp(AirUiTokens.Space.Xl), 0, dp(AirUiTokens.Space.Xl), 0)
                        }
                        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                        setOnClickListener {
                            if (hintText.isVisible) {
                                hintText.animate().alpha(0f).setDuration(AirUiTokens.Motion.HintOutMs).withEndAction {
                                    hintText.isVisible = false
                                }.start()
                            } else {
                                hintText.isVisible = true
                                hintText.alpha = 0f
                                hintText.animate().alpha(1f).setDuration(AirUiTokens.Motion.FeedbackInMs).start()
                            }
                        }
                    })
                })

                addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, dp(AirUiTokens.Space.Lg), 0)
                })

                addView(airIconView(
                    iconRes = R.drawable.ic_air_refresh,
                    tint = colorAccent,
                    contentDescription = getString(R.string.ui_refresh_media_status)
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dp(AirUiTokens.Layout.IconTouchSize),
                        dp(AirUiTokens.Layout.IconTouchSize)
                    )
                    enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                    setOnClickListener {
                        closeHeaderHint()
                        animate().rotationBy(360f).setDuration(AirUiTokens.Motion.RefreshSpinMs).start()
                        populateRecentLyrics(true, true)
                    }
                })
            })

            addView(hintText)
        })
        addView(listBody)
    }
    return RefreshableSettingsCard(
        view = cardView,
        refreshContent = { populateRecentLyrics(false, true) }
    )
}
