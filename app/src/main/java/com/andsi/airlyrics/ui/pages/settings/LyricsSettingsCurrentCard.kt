package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.andsi.airlyrics.ui.components.TextDisplayPolicy
import com.andsi.airlyrics.ui.components.expandableText
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.settingRow
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.state.showPersistentInfo
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.CurrentLyricsUiState
import com.andsi.airlyrics.ui.model.LyricsDeleteMode
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens


internal fun createCurrentLyricsCard(activity: MainUiHost): RefreshableSettingsCard = with(activity) {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val feedback = TextView(this).apply {
        text = ""
        textSize = AirUiTokens.TextSize.Caption
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentMint)
        gravity = Gravity.CENTER_VERTICAL
    }

    fun render(state: CurrentLyricsUiState) {
        body.removeAllViews()
        val media = state.media

        if (media == null) {
            body.addView(normalText(activity, getString(R.string.ui_no_active_media_found)))
            body.addView(smallHint(activity, getString(R.string.ui_manage_lyrics_select_player_hint)))
            return
        }

        val wordByWordSummaryRes = when {
            state.hasLocalWordByWordLyrics && state.wordByWordLyricsEnabled -> R.string.ui_available_local_word_by_word_lyrics
            state.hasLocalWordByWordLyrics -> R.string.ui_imported_off
            else -> R.string.ui_not_imported
        }

        body.addView(expandableText(normalText(activity, media.displayText)))
        body.addView(
            settingRow(
                activity,
                getString(R.string.ui_plain_lyrics_source),
                state.localSourceText ?: getString(R.string.ui_no_plain_lrc)
            )
        )
        body.addView(settingRow(activity, getString(R.string.ui_plain_lyrics), state.plainLyricsTitle ?: getString(R.string.ui_not_bound), valuePolicy = TextDisplayPolicy.ExpandableSummary))
        body.addView(wordByWordStatusRow(activity, wordByWordSummaryRes))
        body.addView(settingRow(activity, getString(R.string.ui_current_offset), localizedOffsetDescription(state.offsetMs)))
        if (state.offsetMs != 0L) {
            body.addView(smallHint(activity, getString(R.string.ui_offset_per_song_hint)))
        }

        body.addView(actionButton(activity, getString(R.string.ui_import_lyrics_for_current_song)) {
            uiActions.importLyricsForCurrentMedia()
        })

        fun confirmDeleteLyrics(label: String, mode: LyricsDeleteMode, message: String? = null) {
            activity.confirmOperation(
                action = ConfirmationAction.DeleteCurrent(state.operationTarget ?: return, mode),
                title = label,
                message = message?.let { media.displayText + "\n\n" + it } ?: media.displayText,
                positiveText = getString(R.string.ui_remove)
            )
        }

        if (state.hasPlainLyrics && !state.hasLocalWordByWordLyrics) {
            val plainLabel = getString(if (state.plainLyricsDownloaded) R.string.ui_remove_downloaded_lrc else R.string.ui_remove_plain_lrc)
            body.addView(actionButton(activity, plainLabel) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_plain_lrc_confirm),
                    mode = LyricsDeleteMode.PLAIN
                )
            })
        }

        if (state.hasLocalWordByWordLyrics) {
            body.addView(actionButton(activity, getString(R.string.ui_remove_word_by_word_lyrics)) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_word_by_word_lyrics_confirm),
                    mode = LyricsDeleteMode.WORD_BY_WORD,
                    message = getString(R.string.ui_remove_word_by_word_lyrics_message)
                )
            })
        }

        if (state.canRemoveAllLyrics && state.hasLocalWordByWordLyrics) {
            body.addView(actionButton(activity, getString(R.string.ui_remove_all_lyrics)) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_all_lyrics_confirm),
                    mode = LyricsDeleteMode.ALL
                )
            })
        }

        if (!state.hasLocalWordByWordLyrics) {
            body.addView(actionButton(activity, getString(R.string.ui_search_online_again)) {
                activity.confirmOperation(
                    action = ConfirmationAction.Search(state.operationTarget ?: return@actionButton),
                    title = getString(R.string.ui_search_online_again_confirm),
                    message = getString(R.string.ui_search_online_replace_cache_msg),
                    positiveText = getString(R.string.ui_search)
                )
            })
        }
    }

    fun populate(
        showRefreshFeedback: Boolean = false,
        preserveContent: Boolean = false
    ) {
        if (showRefreshFeedback) {
            showInlineRefreshFeedback(feedback, getString(R.string.ui_refreshing))
        } else if (!preserveContent) {
            body.removeAllViews()
            body.addView(normalText(activity, getString(R.string.ui_loading)))
        }

        body.setTag(R.id.interaction_loading, true)
        loadCurrentLyrics(force = showRefreshFeedback) { state ->
            body.setTag(R.id.interaction_loading, false)
            render(state)
            if (showRefreshFeedback) {
                playLocalRefreshFeedback(activity, target = body, feedback = feedback, message = getString(R.string.ui_refreshed))
            }
        }
    }

    populate()

    val cardView = card(activity) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText(activity, getString(R.string.ui_current_song_lyrics)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl), 0)
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
                    animate().rotationBy(360f).setDuration(AirUiTokens.Motion.RefreshSpinMs).start()
                    populate(showRefreshFeedback = true)
                }
            })
        })
        addView(body)
    }
    return RefreshableSettingsCard(
        view = cardView,
        refreshContent = { populate(preserveContent = true) }
    )
}

private fun wordByWordStatusRow(activity: MainUiHost, @StringRes valueRes: Int): View = with(activity) {
    val infoButton = airIconView(
        iconRes = R.drawable.ic_air_info,
        tint = colorTextMuted,
        contentDescription = getString(R.string.ui_local_word_by_word_lyrics_title)
    ).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        layoutParams = ViewGroup.LayoutParams(
            dp(AirUiTokens.Layout.CompactIconButtonSize),
            dp(AirUiTokens.Layout.CompactIconButtonSize)
        )
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            activity.showPersistentInfo(
                titleRes = R.string.ui_local_word_by_word_lyrics_title,
                textRes = R.string.ui_word_by_word_lyrics_local_only
            )
        }
    }

    return settingRow(
        activity = activity,
        name = getString(R.string.ui_word_by_word_lyrics),
        value = getString(valueRes),
        trailingView = infoButton
    )
}
