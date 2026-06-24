package com.andsi.airlyrics.ui.pages.settings

import com.andsi.airlyrics.R

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.i18n.localizedLocalLyricsSource
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.i18n.localizedLyricsSourceHint
import com.andsi.airlyrics.i18n.localizedLyricsSourceTitle
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun createLyricsSettingsPage(activity: MainUiHost): View  = with(activity) createLyricsSettingsPage@ {
    val container = pageContainer(activity, animateChanges = false)
    val selectedSource = LyricsSettingsStore.getLyricsSource(this)
    val autoSearch = LyricsSettingsStore.isAutoSearchOnlineEnabled(this)
    val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)

    container.addView(settingsBackHeader(getString(R.string.ui_lyrics)))

    container.addView(createCurrentLyricsCard(activity))

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_search_strategy)))
            addView(normalText(activity, getString(R.string.ui_lyrics_priority_hint)))

            val autoSearchButton = actionButton(activity, if (autoSearch) getString(R.string.ui_online_fallback_on) else getString(R.string.ui_online_fallback_off)) { }
            autoSearchButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSearch()
                autoSearchButton.text = if (enabled) getString(R.string.ui_online_fallback_on) else getString(R.string.ui_online_fallback_off)
                playLocalRefreshFeedback(activity, autoSearchButton, null, getString(R.string.ui_updated))
            }
            addView(autoSearchButton)

            val autoSaveButton = actionButton(activity, if (autoSave) getString(R.string.ui_auto_save_on) else getString(R.string.ui_auto_save_off)) { }
            autoSaveButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSave()
                autoSaveButton.text = if (enabled) getString(R.string.ui_auto_save_on) else getString(R.string.ui_auto_save_off)
                playLocalRefreshFeedback(activity, autoSaveButton, null, getString(R.string.ui_updated))
            }
            addView(autoSaveButton)
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_lyrics_source)))
            val sourceStatus = normalText(activity, getString(R.string.settings_current_value, localizedLyricsSourceTitle(LyricsSettingsStore.getLyricsSearchSource(activity))))
            val sourceHint = smallHint(activity, localizedLyricsSourceHint(LyricsSearchSource.fromKey(selectedSource)))
            val sourceFeedback = TextView(activity).apply {
                text = ""
                textSize = AirUiTokens.TextSize.Caption
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccentMint)
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
            }
            addView(sourceStatus)
            addView(sourceHint)
            addView(sourceFeedback)
            lateinit var sourceGrid: LinearLayout
            sourceGrid = liveOptionGrid(
                LyricsSettingsStore.sourceOptions.map { option ->
                    val source = LyricsSearchSource.fromKey(option.key)
                    KeyedOptionItem(
                        key = option.key,
                        title = localizedLyricsSourceTitle(source),
                        selected = option.key == selectedSource,
                        action = {
                            uiActions.selectLyricsSource(option.key)
                            sourceStatus.text = getString(R.string.settings_current_value, localizedLyricsSourceTitle(
                                source
                            ))
                            sourceHint.text = localizedLyricsSourceHint(source)
                            playLocalRefreshFeedback(activity, sourceGrid, sourceFeedback, getString(R.string.ui_saved))
                        }
                    )
                }
            )
            addView(sourceGrid)
        }
    )


    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_local_lyrics_folder)))
            addView(normalText(activity, getString(R.string.ui_save_folder) + LyricsStorage.getLyricsDirRawPath(activity)))
            addView(smallHint(activity, getString(R.string.ui_lyrics_folder_scope_hint)))
            addView(actionButton(activity, getString(R.string.ui_choose_lyrics_save_folder)) {
                uiActions.selectLyricsDirectory()
            })
            addView(actionButton(activity, getString(R.string.ui_copy_lyrics_save_folder)) {
                uiActions.copyLyricsDirectory()
            })
        }
    )

    container.addView(createRecentLyricsCard(activity))

    return scroll(activity, container, animateChildren = false)
}


private fun karaokeStatusRow(activity: MainUiHost, value: String): View = with(activity) karaokeStatusRow@ {
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

private fun createCurrentLyricsCard(activity: MainUiHost): View  = with(activity) createCurrentLyricsCard@ {
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

        if (localInfo != null) {
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

        if (localInfo != null && state.localWordByWord) {
            body.addView(actionButton(activity, getString(R.string.ui_remove_all_lyrics)) {
                confirmDeleteLyrics(
                    label = getString(R.string.ui_remove_all_lyrics_confirm),
                    mode = LyricsStorage.DeleteMode.ALL,
                    message = getString(R.string.ui_remove_all_lyrics_message)
                )
            })
        }

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

    fun populate(showRefreshFeedback: Boolean = false) {
        val loadGeneration = ++currentLyricsLoadGeneration
        val media = getCurrentMediaSnapshot()
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

private fun createRecentLyricsCard(activity: MainUiHost): View  = with(activity) createRecentLyricsCard@ {
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

    fun currentLocalLyricsItem(media: CurrentMediaInfo?): LyricsStorage.LocalLyricsItem? {
        media ?: return null
        val info = LyricsStorage.getLocalLyricsInfo(
            context = this,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        ) ?: return null
        val hasWordByWord = LyricsStorage.hasKaraokeLyrics(
            context = this,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        )
        return LyricsStorage.LocalLyricsItem(
            name = info.fileName,
            modifiedTimeMillis = info.updatedAt,
            sizeBytes = 0L,
            title = info.title,
            artist = info.artist,
            source = info.source,
            provider = info.provider,
            hasPlainLyrics = true,
            hasKaraokeLyrics = hasWordByWord
        )
    }

    lateinit var populateRecentLyrics: (Boolean) -> Unit

    fun renderLyricsList(
        currentItem: LyricsStorage.LocalLyricsItem?,
        recentLyrics: List<LyricsStorage.LocalLyricsItem>,
        media: CurrentMediaInfo?,
        showRefreshFeedback: Boolean
    ) {
        listBody.removeAllViews()

        if (currentItem != null) {
            listBody.addView(localLyricsRow(currentItem, onLyricsSaved = {
                closeHeaderHint()
                populateRecentLyrics(false)
                playLocalRefreshFeedback(activity, listBody, feedback, getString(R.string.ui_applied))
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

        if (recentLyrics.isEmpty()) {
            listBody.addView(normalText(activity, getString(R.string.ui_recent_lyrics_empty_hint)))
        } else {
            val currentName = currentItem?.name?.substringAfterLast('/')
            recentLyrics
                .filterNot { item -> currentName != null && item.name.substringAfterLast('/') == currentName }
                .forEach { item ->
                    listBody.addView(localLyricsRow(item, onLyricsSaved = {
                        closeHeaderHint()
                        populateRecentLyrics(false)
                        playLocalRefreshFeedback(activity, listBody, feedback, getString(R.string.ui_applied))
                    }))
                }
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

    populateRecentLyrics = { showRefreshFeedback ->
        val loadGeneration = ++recentLyricsLoadGeneration
        val media = getCurrentMediaSnapshot()?.takeUnless { it.isEmpty }
        if (showRefreshFeedback) {
            showInlineRefreshFeedback(feedback, getString(R.string.ui_refreshing))
        } else {
            listBody.removeAllViews()
            listBody.addView(normalText(activity, getString(R.string.ui_loading)))
        }

        runOnAppIo {
            val currentItem = currentLocalLyricsItem(media)
            val recentLyrics = LyricsStorage.listRecentLyrics(this, limit = 8)

            runOnMainThread {
                if (loadGeneration != recentLyricsLoadGeneration) return@runOnMainThread
                renderLyricsList(currentItem, recentLyrics, media, showRefreshFeedback)
            }
        }
    }

    populateRecentLyrics(false)

    return card(activity) {
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

                addView(TextView(activity).apply {
                    text = "↻"
                    textSize = AirUiTokens.TextSize.PageTitle
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                    gravity = Gravity.CENTER
                    setPadding(dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Sm), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Sm))
                    enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                    setOnClickListener {
                        closeHeaderHint()
                        animate().rotationBy(360f).setDuration(AirUiTokens.Motion.RefreshSpinMs).start()
                        populateRecentLyrics(true)
                    }
                })
            })

            addView(hintText)
        })
        addView(listBody)
    }
}


private fun showInlineRefreshFeedback(feedback: TextView?, message: String) {
    feedback?.apply {
        animate().cancel()
        text = message
        alpha = 1f
    }
}

private fun playLocalRefreshFeedback(activity: MainUiHost, target: View, feedback: TextView?, message: String) = with(activity) playLocalRefreshFeedback@ {
    feedback?.apply {
        text = message
        alpha = 0f
        animate().alpha(1f).setDuration(AirUiTokens.Motion.FeedbackInMs).withEndAction {
            postDelayed({ animate().alpha(0f).setDuration(AirUiTokens.Motion.FeedbackOutMs).withEndAction { text = "" }.start() }, AirUiTokens.Motion.FeedbackHoldMs)
        }.start()
    }

    target.alpha = 0.72f
    target.translationY = dp(AirUiTokens.Space.Xs).toFloat()
    target.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(AirUiTokens.Motion.ChildEnterMs)
        .start()
}
