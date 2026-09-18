package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceOrder
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourcePriorityTitle
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceTitle
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.model.LyricsSettingsUiState
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun createLyricsSettingsPage(activity: MainUiHost): View  = with(activity) createLyricsSettingsPage@ {
    val container = pageContainer(activity, animateChanges = false)
    val settings = lyricsSettingsState()

    container.addView(settingsBackHeader(getString(R.string.ui_lyrics)))

    val currentLyricsCard = createCurrentLyricsCard(activity)
    container.addView(currentLyricsCard.view)

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_search_strategy)))
            addView(normalText(activity, getString(R.string.ui_lyrics_priority_hint)))

            val autoSearchButton = actionButton(activity, getString(if (settings.autoSearchOnline) R.string.ui_online_fallback_on else R.string.ui_online_fallback_off)) { }
            autoSearchButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSearch()
                autoSearchButton.setText(if (enabled) R.string.ui_online_fallback_on else R.string.ui_online_fallback_off)
                playLocalRefreshFeedback(activity, autoSearchButton, null, getString(R.string.ui_updated))
            }
            addView(autoSearchButton)

            val autoSaveButton = actionButton(activity, getString(if (settings.autoSaveLocal) R.string.ui_auto_save_on else R.string.ui_auto_save_off)) { }
            autoSaveButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSave()
                autoSaveButton.setText(if (enabled) R.string.ui_auto_save_on else R.string.ui_auto_save_off)
                playLocalRefreshFeedback(activity, autoSaveButton, null, getString(R.string.ui_updated))
            }
            addView(autoSaveButton)
        }
    )

    container.addView(createLyricsSourceOrderCard(activity, settings))

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_local_lyrics_folder)))
            addView(normalText(activity, getString(R.string.ui_save_folder) + settings.lyricsDirectoryPath))
            addView(smallHint(activity, getString(R.string.ui_lyrics_folder_scope_hint)))
            addView(actionButton(activity, getString(R.string.ui_choose_lyrics_save_folder)) {
                uiActions.selectLyricsDirectory()
            })
            addView(actionButton(activity, getString(R.string.ui_copy_lyrics_save_folder)) {
                uiActions.copyLyricsDirectory()
            })
        }
    )

    val recentLyricsCard = createRecentLyricsCard(activity)
    container.addView(recentLyricsCard.view)

    container.addView(
        card(activity) {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(bigText(activity, getString(R.string.ui_delete_all_saved_lyrics)).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })
                addView(dangerActionButton(activity, getString(R.string.ui_delete)) {
                    activity.showAirConfirmDialog(
                        title = getString(R.string.ui_delete_all_saved_lyrics_confirm),
                        message = getString(R.string.ui_delete_all_saved_lyrics_message),
                        positiveText = getString(R.string.ui_delete)
                    ) {
                        uiActions.deleteAllSavedLyrics()
                    }
                }.apply {
                    (layoutParams as LinearLayout.LayoutParams).setMargins(
                        dp(AirUiTokens.Space.Xxl),
                        0,
                        0,
                        0
                    )
                })
            })
        }
    )

    lyricsSettingsContentRefresh = {
        currentLyricsCard.refreshContent()
        recentLyricsCard.refreshContent()
    }

    return scroll(activity, container, animateChildren = false)
}

internal fun createLyricsSourceOrderCard(
    activity: MainUiHost,
    settings: LyricsSettingsUiState
): View = with(activity) {
    card(activity) {
        var selectedSources = settings.selectedPlainLyricsSources.distinct()

        addView(bigText(activity, getString(R.string.ui_plain_lyrics_source)))
        addView(smallHint(activity, getString(R.string.ui_lyrics_source_order_hint)))

        val sourceStatus = normalText(
            activity,
            getString(
                R.string.ui_lyrics_source_order_value,
                localizedPlainLyricsSourceOrder(selectedSources)
            )
        )
        val sourceFeedback = TextView(activity).apply {
            text = ""
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentMint)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        lateinit var sourceGrid: LinearLayout
        lateinit var refreshSourceOptions: () -> Unit
        val sourceButtons = settings.plainLyricsSourceOptions.associateWith { source ->
            optionButton(
                OptionItem(
                    title = localizedPlainLyricsSourceTitle(source),
                    selected = source in selectedSources,
                    action = {
                        val updatedSources = toggleOrderedPlainLyricsSource(
                            selectedSources = selectedSources,
                            source = source
                        )
                        if (updatedSources == selectedSources) {
                            playLocalRefreshFeedback(
                                activity,
                                sourceGrid,
                                sourceFeedback,
                                getString(R.string.ui_keep_one_lyrics_source)
                            )
                        } else {
                            selectedSources = updatedSources
                            uiActions.setPlainLyricsSources(selectedSources)
                            sourceStatus.text = getString(
                                R.string.ui_lyrics_source_order_value,
                                localizedPlainLyricsSourceOrder(selectedSources)
                            )
                            refreshSourceOptions()
                            playLocalRefreshFeedback(
                                activity,
                                sourceGrid,
                                sourceFeedback,
                                getString(R.string.ui_saved)
                            )
                        }
                    }
                )
            ).apply {
                isFocusable = true
            }
        }
        sourceGrid = optionButtonGrid(sourceButtons.values.toList())
        refreshSourceOptions = {
            sourceButtons.forEach { (source, button) ->
                val selectedIndex = selectedSources.indexOf(source)
                val selected = selectedIndex >= 0
                applyOptionButtonState(
                    button = button,
                    title = if (selected) {
                        localizedPlainLyricsSourcePriorityTitle(
                            source,
                            priority = selectedIndex + 1
                        )
                    } else {
                        localizedPlainLyricsSourceTitle(source)
                    },
                    selected = selected
                )
                button.isSelected = selected
                ViewCompat.setStateDescription(
                    button,
                    getString(
                        if (selected) {
                            R.string.ui_lyrics_source_selected_state
                        } else {
                            R.string.ui_lyrics_source_not_selected_state
                        }
                    )
                )
            }
        }

        addView(sourceStatus)
        addView(sourceFeedback)
        addView(sourceGrid)
        refreshSourceOptions()
    }
}

internal fun toggleOrderedPlainLyricsSource(
    selectedSources: List<PlainLyricsSearchSource>,
    source: PlainLyricsSearchSource
): List<PlainLyricsSearchSource> {
    val normalizedSources = selectedSources.distinct()
    return if (source in normalizedSources) {
        normalizedSources - source
    } else {
        normalizedSources + source
    }
}
