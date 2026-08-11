package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceHint
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceTitle
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiHost
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
            addView(bigText(activity, getString(R.string.ui_plain_lyrics_source)))
            val sourceStatus = normalText(
                activity,
                getString(
                    R.string.settings_current_value,
                    localizedPlainLyricsSourceTitle(settings.selectedPlainLyricsSource)
                )
            )
            val sourceHint = smallHint(
                activity,
                localizedPlainLyricsSourceHint(settings.selectedPlainLyricsSource)
            )
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
                settings.plainLyricsSourceOptions.map { plainLyricsSearchSource ->
                    KeyedOptionItem(
                        key = plainLyricsSearchSource.key,
                        title = localizedPlainLyricsSourceTitle(plainLyricsSearchSource),
                        selected = plainLyricsSearchSource == settings.selectedPlainLyricsSource,
                        action = {
                            uiActions.selectPlainLyricsSource(plainLyricsSearchSource)
                            sourceStatus.text = getString(R.string.settings_current_value, localizedPlainLyricsSourceTitle(
                                plainLyricsSearchSource
                            ))
                            sourceHint.text = localizedPlainLyricsSourceHint(plainLyricsSearchSource)
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
