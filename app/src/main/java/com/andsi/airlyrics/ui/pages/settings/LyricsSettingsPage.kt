package com.andsi.airlyrics.ui.pages.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceCompactTitle
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceTitle
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.model.LyricsSettingsUiState
import com.andsi.airlyrics.ui.model.MainUiHost
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
        lateinit var sourceGrid: LyricsSourceOrderRow
        lateinit var refreshSourceOptions: (Boolean) -> Unit
        val sourceButtons = settings.plainLyricsSourceOptions.associateWith { source ->
            LyricsSourceOptionButton(
                host = activity,
                source = source,
                compactTitle = localizedPlainLyricsSourceCompactTitle(source)
            ).apply {
                setOnClickListener {
                    val updatedSources = toggleOrderedPlainLyricsSource(
                        selectedSources = selectedSources,
                        source = source
                    )
                    selectedSources = updatedSources
                    uiActions.setPlainLyricsSources(selectedSources)
                    refreshSourceOptions(true)
                    playTinyPulse(this)
                }
            }
        }
        sourceGrid = LyricsSourceOrderRow(activity)
        refreshSourceOptions = { animateOrder ->
            sourceButtons.forEach { (source, button) ->
                val selectedIndex = selectedSources.indexOf(source)
                button.render(
                    selectedPriority = (selectedIndex + 1).takeIf { selectedIndex >= 0 },
                    fullTitle = localizedPlainLyricsSourceTitle(source)
                )
            }
            val displayedSources = orderedLyricsSourceOptions(
                selectedSources = selectedSources,
                sourceOptions = settings.plainLyricsSourceOptions
            )
            sourceGrid.setSourceOrder(
                orderedButtons = displayedSources.map(sourceButtons::getValue),
                animate = animateOrder
            )
        }

        addView(sourceGrid)
        refreshSourceOptions(false)
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
