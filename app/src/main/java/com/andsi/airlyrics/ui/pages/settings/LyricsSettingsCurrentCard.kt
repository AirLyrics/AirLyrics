package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLocalLyricsSource
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.settingRow
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun createCurrentLyricsCard(activity: MainUiHost): View = with(activity) {
    data class CurrentLyricsUiState(
        val media: CurrentMediaInfo?,
        val localInfo: LyricsStorage.LocalLyricsInfo?,
        val localWordByWord: Boolean,
        val offsetMs: Long
    )

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
        val localInfo = state.localInfo

        if (media == null) {
            body.addView(normalText(activity, getString(R.string.ui_no_active_media_found)))
            body.addView(smallHint(activity, getString(R.string.ui_manage_lyrics_select_player_hint)))
            return
        }

        val karaokeEnabled = LyricsSettingsStore.isKaraokeLyricsEnabled(activity)
        val karaokeSummary = when {
            state.localWordByWord && karaokeEnabled -> getString(R.string.ui_available_local_enhanced_lrc)
            state.localWordByWord -> getString(R.string.ui_imported_off)
            else -> getString(R.string.ui_not_imported)
        }

        body.addView(normalText(activity, media.displayText))
        body.addView(settingRow(activity, getString(R.string.ui_lyrics_source), localInfo?.let { localizedLocalLyricsSource(it) } ?: getString(R.string.ui_no_plain_lrc)))
        body.addView(settingRow(activity, getString(R.string.ui_plain_lyrics), localInfo?.friendlyTitle ?: getString(R.string.ui_not_bound)))
        body.addView(karaokeStatusRow(activity, karaokeSummary))
        body.addView(settingRow(activity, getString(R.string.ui_current_offset), localizedOffsetDescription(state.offsetMs)))
        if (state.offsetMs != 0L) {
            body.addView(smallHint(activity, getString(R.string.ui_offset_per_song_hint)))
        }

        body.addView(actionButton(activity, getString(R.string.ui_import_lyrics_for_current_song)) {
            uiActions.importLyricsForCurrentMedia()
        })

        fun confirmDeleteLyrics(label: String, mode: LyricsStorage.DeleteMode, message: String) {
            activity.showAirConfirmDialog(
                title = label,
                message = media.displayText + "\n\n" + message,
                positiveText = getString(R.string.ui_remove)
            ) {
                uiActions.deleteLyricsForCurrentMedia(media, mode)
            }
        }

        if (localInfo != null && !state.localWordByWord) {
            val plainLabel = if (localInfo.source == LyricsStorage.SOURCE_DOWNLOADED) getString(R.string.ui_remove_downloaded_lrc) else getString(R.string.ui_remove_plain_lrc)
            body.addView(actionButton(activity, plainLabel) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_plain_lrc_confirm),
                    mode = LyricsStorage.DeleteMode.PLAIN,
                    message = getString(R.string.ui_remove_plain_lrc_message)
                )
            })
        }

        if (state.localWordByWord) {
            body.addView(actionButton(activity, getString(R.string.ui_remove_enhanced_lrc)) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_enhanced_lrc_confirm),
                    mode = LyricsStorage.DeleteMode.KARAOKE,
                    message = getString(R.string.ui_remove_enhanced_lrc_message)
                )
            })
        }

        if (localInfo != null && state.localWordByWord && localInfo.source != LyricsStorage.SOURCE_KARAOKE_FALLBACK) {
            body.addView(actionButton(activity, getString(R.string.ui_remove_all_lyrics)) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_all_lyrics_confirm),
                    mode = LyricsStorage.DeleteMode.ALL,
                    message = getString(R.string.ui_remove_all_lyrics_message)
                )
            })
        }

        if (!state.localWordByWord) {
            body.addView(actionButton(activity, getString(R.string.ui_search_online_again)) {
                activity.showAirConfirmDialog(
                    title = getString(R.string.ui_search_online_again_confirm),
                    message = getString(R.string.ui_search_online_replace_cache_msg),
                    positiveText = getString(R.string.ui_search)
                ) {
                    uiActions.reloadFloatingLyricsFromOnline()
                }
            })
        }
    }

    fun populate(showRefreshFeedback: Boolean = false) {
        val loadGeneration = ++currentLyricsLoadGeneration
        val media = getCurrentMediaInfo()
        val offsetMs = media?.let { LyricsOffsetStore.getOffsetMs(activity, it) } ?: 0L
        if (showRefreshFeedback) {
            showInlineRefreshFeedback(feedback, getString(R.string.ui_refreshing))
        } else {
            body.removeAllViews()
            body.addView(normalText(activity, getString(R.string.ui_loading)))
        }

        runOnAppIo {
            val localInfo = media?.let {
                LyricsStorage.getLocalLyricsInfo(
                    context = this,
                    title = it.title,
                    artist = it.artist,
                    duration = it.durationMs
                )
            }
            val localWordByWord = media?.let {
                LyricsStorage.hasKaraokeLyrics(
                    context = activity,
                    title = it.title,
                    artist = it.artist,
                    duration = it.durationMs
                )
            } == true
            val state = CurrentLyricsUiState(media, localInfo, localWordByWord, offsetMs)

            runOnMainThread {
                if (loadGeneration != currentLyricsLoadGeneration) return@runOnMainThread
                render(state)
                if (showRefreshFeedback) {
                    playLocalRefreshFeedback(activity, target = body, feedback = feedback, message = getString(R.string.ui_refreshed))
                }
            }
        }
    }

    populate()

    return card(activity) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText(activity, getString(R.string.ui_current_song_lyrics)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl), 0)
            })
            addView(TextView(activity).apply {
                text = "↻"
                textSize = AirUiTokens.TextSize.PageTitle
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                gravity = Gravity.CENTER
                setPadding(dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Sm), dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Sm))
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    animate().rotationBy(360f).setDuration(AirUiTokens.Motion.RefreshSpinMs).start()
                    populate(showRefreshFeedback = true)
                }
            })
        })
        addView(body)
    }
}

private fun karaokeStatusRow(activity: MainUiHost, value: String): View = with(activity) {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Sm))

        addView(TextView(activity).apply {
            text = getString(R.string.ui_enhanced_lrc)
            textSize = AirUiTokens.TextSize.Button
            setTextColor(colorTextStrong)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(TextView(activity).apply {
            text = value
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(colorTextMuted)
            gravity = Gravity.CENTER_VERTICAL
        })

        addView(TextView(activity).apply {
            text = "!"
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colorTextMuted)
            setPadding(0, 0, 0, dp(AirUiTokens.Stroke.Hairline))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSurfaceLight)
                setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
            }
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.StatusIconSize), dp(AirUiTokens.Layout.StatusIconSize)).apply {
                setMargins(dp(AirUiTokens.Space.Xl), 0, 0, 0)
            }
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setOnClickListener {
                activity.showAirInfoDialog(
                    title = getString(R.string.ui_local_enhanced_lrc_title),
                    message = getString(R.string.ui_enhanced_lrc_local_only_message)
                )
            }
        })
    }
}
