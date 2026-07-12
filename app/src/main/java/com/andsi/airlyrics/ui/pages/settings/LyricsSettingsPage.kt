package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsSourceHint
import com.andsi.airlyrics.i18n.localizedLyricsSourceTitle
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun createLyricsSettingsPage(activity: MainUiHost): View  = with(activity) createLyricsSettingsPage@ {
    val container = pageContainer(activity, animateChanges = false)
    val settings = lyricsSettingsState()

    container.addView(settingsBackHeader(getString(R.string.ui_lyrics)))

    container.addView(createCurrentLyricsCard(activity))

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_search_strategy)))
            addView(normalText(activity, getString(R.string.ui_lyrics_priority_hint)))

            val autoSearchButton = actionButton(activity, if (settings.autoSearchOnline) getString(R.string.ui_online_fallback_on) else getString(R.string.ui_online_fallback_off)) { }
            autoSearchButton.setOnClickListener {
                val enabled = uiActions.toggleLyricsAutoSearch()
                autoSearchButton.text = if (enabled) getString(R.string.ui_online_fallback_on) else getString(R.string.ui_online_fallback_off)
                playLocalRefreshFeedback(activity, autoSearchButton, null, getString(R.string.ui_updated))
            }
            addView(autoSearchButton)

            val autoSaveButton = actionButton(activity, if (settings.autoSaveLocal) getString(R.string.ui_auto_save_on) else getString(R.string.ui_auto_save_off)) { }
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
            val sourceStatus = normalText(activity, getString(R.string.settings_current_value, localizedLyricsSourceTitle(settings.selectedSource)))
            val sourceHint = smallHint(activity, localizedLyricsSourceHint(settings.selectedSource))
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
                settings.sourceOptions.map { source ->
                    KeyedOptionItem(
                        key = source.key,
                        title = localizedLyricsSourceTitle(source),
                        selected = source == settings.selectedSource,
                        action = {
                            uiActions.selectLyricsSource(source)
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

    container.addView(createRecentLyricsCard(activity))

    return scroll(activity, container, animateChildren = false)
}
