package com.andsi.airlyrics.ui.pages.floating.sections

import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsContentModeTitle
import com.andsi.airlyrics.i18n.localizedLyricsLineModeTitle
import com.andsi.airlyrics.i18n.localizedLyricsSwitchAnimationTitle
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.horizontalButtons
import com.andsi.airlyrics.ui.components.settingRow
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
import com.andsi.airlyrics.ui.pages.floating.openPanel
import com.andsi.airlyrics.core.color.AirColorUtils

internal fun FloatingPageScope.addBehaviorSection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_behavior)))
    list.addView(
        settingGrid(
            trackedFloatingTile(
                title = getString(R.string.ui_display_control),
                subtitle = floatingDisplaySummary(),
                iconRes = R.drawable.ic_air_visibility,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_display_control), "") {
                        addView(horizontalButtons(host,
                            getString(R.string.ui_show) to {
                                uiActions.showFloatingLyrics()
                                refreshFloatingPreview()
                            },
                            getString(R.string.ui_hide) to {
                                uiActions.hideFloatingLyrics()
                                refreshFloatingPreview()
                            }
                        ))

                        val lockButton = actionButton(host, floatingLockButtonText()) { }
                        pageRefs.lockButton = lockButton
                        lockButton.setOnClickListener {
                            uiActions.toggleLock()
                            lockButton.text = floatingLockButtonText()
                            refreshFloatingPreview()
                        }
                        addView(lockButton)

                        val clickThroughButton = actionButton(host, floatingClickThroughButtonText()) { }
                        pageRefs.clickThroughButton = clickThroughButton
                        clickThroughButton.setOnClickListener {
                            uiActions.toggleClickThrough()
                            clickThroughButton.text = floatingClickThroughButtonText()
                            refreshFloatingPreview()
                        }
                        addView(clickThroughButton)
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_auto_hide_when_paused),
                subtitle = autoHideWhenPausedSubtitle(),
                iconRes = R.drawable.ic_air_visibility_off,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_auto_hide_when_paused),
                        ""
                    ) {
                        val autoHideButton = actionButton(host, autoHideWhenPausedButtonText()) { }
                        autoHideButton.setOnClickListener {
                            uiActions.toggleAutoHideWhenPaused()
                            autoHideButton.text = autoHideWhenPausedButtonText()
                            refreshFloatingSettingTiles()
                        }
                        addView(autoHideButton)
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_display_scope),
                subtitle = displayScopeSummary(),
                iconRes = R.drawable.ic_air_visibility,
                enabled = displayScopeSupported(),
                onClick = { tile ->
                    activeDisplayScopePanelRefresh = openPanel(
                        tile,
                        getString(R.string.ui_display_scope),
                        ""
                    ) {
                        val usageAccessGranted = hasUsageStatsAccess()
                        addView(
                            settingRow(
                                host,
                                getString(R.string.ui_choose_apps),
                                resources.getQuantityString(
                                    R.plurals.ui_selected_apps_count,
                                    displayScopeSelectedCount(),
                                    displayScopeSelectedCount()
                                )
                            )
                        )
                        addView(
                            settingRow(
                                host,
                                getString(R.string.ui_usage_access),
                                getString(if (usageAccessGranted) R.string.ui_on else R.string.ui_off)
                            )
                        )

                        if (!usageAccessGranted) {
                            addView(smallHint(host, getString(R.string.ui_display_scope_usage_hint)))
                            addView(actionButton(host, getString(R.string.ui_grant_usage_access)) {
                                uiActions.openUsageAccessSettings()
                            })
                        }

                        if (usageAccessGranted) {
                            addView(actionButton(host, getString(R.string.ui_choose_apps)) {
                                uiActions.chooseDisplayScopeApps()
                            })
                        }

                        val canToggle = displayScopeEnabled() ||
                            (usageAccessGranted && displayScopeSelectedCount() > 0)
                        addView(
                            actionButton(
                                host,
                                getString(
                                    when {
                                        displayScopeEnabled() -> R.string.ui_disable_display_scope
                                        displayScopeSelectedCount() == 0 -> R.string.ui_select_apps_first
                                        else -> R.string.ui_enable_display_scope
                                    }
                                )
                            ) {
                                uiActions.toggleDisplayScope()
                            }.apply {
                                isEnabled = canToggle
                                alpha = if (canToggle) 1f else 0.48f
                            }
                        )
                    }
                }
            )
        )
    )
    addSetupSummaryButton(list)
}

private fun FloatingPageScope.addSetupSummaryButton(list: LinearLayout) = with(host) {
    val summaryButton = actionButton(host, getString(R.string.ui_view_current_setup)) { }
    summaryButton.setOnClickListener {
        openPanel(summaryButton, getString(R.string.ui_current_setup), "") {
            addView(settingRow(host, getString(R.string.ui_skin), localizedPresetTitle(style().presetName)))
            addView(settingRow(host, getString(R.string.ui_font_size), "${style().textSizeSp.toInt()}sp"))
            addView(settingRow(host, getString(R.string.ui_font), fontFamilySubtitle()))
            addView(settingRow(host, getString(R.string.ui_font_weight), fontWeightSubtitle()))
            addView(settingRow(host, getString(R.string.ui_font_opacity), fontOpacitySubtitle()))
            addView(settingRow(host, getString(R.string.ui_text), AirColorUtils.colorSummary(style().textColor)))
            addView(settingRow(host, getString(R.string.ui_highlight), AirColorUtils.colorSummary(style().wordByWordHighlightColor)))
            addView(settingRow(host, getString(R.string.ui_background), onOff(style().backgroundEnabled)))
            addView(settingRow(host, getString(R.string.ui_width), "${style().maxWidthPercent}%"))
            addView(settingRow(host, getString(R.string.ui_content), localizedLyricsContentModeTitle(contentDisplayMode())))
            addView(settingRow(host, getString(R.string.ui_range), localizedLyricsLineModeTitle(lineDisplayMode())))
            addView(settingRow(host, getString(R.string.ui_alignment), localizedGravityTitle(style().gravity)))
            addView(settingRow(host, getString(R.string.ui_animation), localizedLyricsSwitchAnimationTitle(switchAnimationMode())))
            addView(settingRow(host, getString(R.string.ui_word_by_word_lyrics), getString(if (wordByWordLyricsEnabled()) R.string.ui_preferred else R.string.ui_off)))
            addView(settingRow(host, getString(R.string.ui_lyrics_offset), uiActions.currentLyricsOffsetSummary()))
            addView(settingRow(host, getString(R.string.ui_locked), onOff(uiState.locked)))
            addView(settingRow(host, getString(R.string.ui_click_through), onOff(uiState.clickThrough)))
            addView(settingRow(host, getString(R.string.ui_auto_hide_when_paused), autoHideWhenPausedSubtitle()))
            addView(settingRow(host, getString(R.string.ui_display_scope), host.displayScopeSummary()))
        }
    }
    list.addView(summaryButton)
}

private fun FloatingPageScope.autoHideWhenPausedSubtitle(): String {
    return onOff(host.autoHideWhenPausedEnabled())
}

private fun FloatingPageScope.autoHideWhenPausedButtonText(): String {
    return host.getString(
        if (host.autoHideWhenPausedEnabled()) {
            R.string.ui_auto_hide_when_paused_on
        } else {
            R.string.ui_auto_hide_when_paused_off
        }
    )
}
