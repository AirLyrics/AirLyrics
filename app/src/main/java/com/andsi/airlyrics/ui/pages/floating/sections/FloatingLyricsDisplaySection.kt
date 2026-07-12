package com.andsi.airlyrics.ui.pages.floating.sections

import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsContentModeTitle
import com.andsi.airlyrics.i18n.localizedLyricsLineModeTitle
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.horizontalButtons
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.FloatingPageTokens
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
import com.andsi.airlyrics.ui.pages.floating.openPanel
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun FloatingPageScope.addLyricsDisplaySection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_lyrics_display)))
    list.addView(
        settingGrid(
            trackedFloatingTile(
                title = getString(R.string.ui_content),
                subtitle = localizedLyricsContentModeTitle(contentDisplayMode()),
                iconRes = R.drawable.ic_air_lyrics,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_content), "") {
                        addView(liveOptionGrid(
                            LyricsContentDisplayMode.entries.map { mode ->
                                KeyedOptionItem(
                                    key = mode.key,
                                    title = localizedLyricsContentModeTitle(mode),
                                    selected = mode == contentDisplayMode(),
                                    action = {
                                        LyricsSettingsStore.setContentDisplayMode(host, mode)
                                        applyLyricsDisplaySettingsChanged()
                                    }
                                )
                            }
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_line_range),
                subtitle = localizedLyricsLineModeTitle(lineDisplayMode()),
                iconRes = R.drawable.ic_air_line_spacing,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_line_range), "") {
                        addView(liveOptionGrid(
                            LyricsLineDisplayMode.entries.map { mode ->
                                KeyedOptionItem(
                                    key = mode.key,
                                    title = localizedLyricsLineModeTitle(mode),
                                    selected = mode == lineDisplayMode(),
                                    action = {
                                        LyricsSettingsStore.setLineDisplayMode(host, mode)
                                        applyLyricsDisplaySettingsChanged()
                                    }
                                )
                            }
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_text_alignment),
                subtitle = localizedGravityTitle(style().gravity),
                iconRes = R.drawable.ic_air_align_center,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_text_alignment), "") {
                        addView(liveOptionGrid(listOf(
                            KeyedOptionItem("left", getString(R.string.ui_left), style().gravity == (Gravity.START or Gravity.CENTER_VERTICAL)) {
                                applyFloatingGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                                refreshFloatingPreview()
                            },
                            KeyedOptionItem("center", getString(R.string.ui_center), style().gravity == Gravity.CENTER) {
                                applyFloatingGravity(Gravity.CENTER)
                                refreshFloatingPreview()
                            },
                            KeyedOptionItem("right", getString(R.string.ui_right), style().gravity == (Gravity.END or Gravity.CENTER_VERTICAL)) {
                                applyFloatingGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                                refreshFloatingPreview()
                            }
                        )))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_lyrics_offset),
                subtitle = uiActions.currentLyricsOffsetSummary(),
                iconRes = R.drawable.ic_air_motion,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_lyrics_offset), "") {
                        val statusText = normalText(host, uiActions.currentLyricsOffsetSummary()).apply {
                            textSize = FloatingPageTokens.OFFSET_STATUS_TEXT_SP
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(colorTextStrong)
                            setPadding(0, dp(FloatingPageTokens.OFFSET_STATUS_PADDING_TOP_DP), 0, dp(FloatingPageTokens.OFFSET_STATUS_PADDING_BOTTOM_DP))
                        }
                        addView(statusText)
                        addView(horizontalButtons(host,
                            getString(R.string.ui_delay_1s) to { applyLyricsOffsetDelta(-1_000L, statusText) },
                            getString(R.string.ui_advance_1s) to { applyLyricsOffsetDelta(1_000L, statusText) }
                        ))
                        addView(horizontalButtons(host,
                            getString(R.string.ui_delay_0_1s) to { applyLyricsOffsetDelta(-100L, statusText) },
                            getString(R.string.ui_advance_0_1s) to { applyLyricsOffsetDelta(100L, statusText) }
                        ))
                        addView(actionButton(host, getString(R.string.ui_reset_current_song_offset)) {
                            resetLyricsOffset(statusText)
                        })
                    }
                }
            )
        )
    )
}
