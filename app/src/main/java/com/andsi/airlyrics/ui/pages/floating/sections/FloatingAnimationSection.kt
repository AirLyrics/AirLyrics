package com.andsi.airlyrics.ui.pages.floating.sections

import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsSwitchAnimationTitle
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
import com.andsi.airlyrics.ui.pages.floating.openPanel
import com.andsi.airlyrics.core.color.AirColorUtils

internal fun FloatingPageScope.addAnimationSection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_animation)))
    list.addView(
        settingGrid(
            trackedFloatingTile(
                title = getString(R.string.ui_switch_animation),
                subtitle = localizedLyricsSwitchAnimationTitle(switchAnimationMode()),
                iconRes = R.drawable.ic_air_motion,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_switch_animation), "") {
                        addView(liveOptionGrid(
                            LyricsSwitchAnimationMode.entries.map { mode ->
                                KeyedOptionItem(
                                    key = mode.key,
                                    title = localizedLyricsSwitchAnimationTitle(mode),
                                    selected = mode == switchAnimationMode(),
                                    action = {
                                        setLyricsSwitchAnimationMode(mode)
                                        applyLyricsAnimationSettingsChanged()
                                    }
                                )
                            }
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_enhanced_lrc),
                subtitle = wordLyricsSubtitle(),
                iconRes = R.drawable.ic_air_motion,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_enhanced_lrc), "") {
                        addView(liveOptionGrid(listOf(
                            KeyedOptionItem("karaoke_on", getString(R.string.ui_on), karaokeLyricsEnabled()) {
                                applyKaraokeLyricsChanged(true)
                            },
                            KeyedOptionItem("karaoke_off", getString(R.string.ui_off), !karaokeLyricsEnabled()) {
                                applyKaraokeLyricsChanged(false)
                            }
                        )))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_highlight_color),
                subtitle = AirColorUtils.colorSummary(style().karaokeHighlightColor),
                iconRes = R.drawable.ic_air_text_color,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_enhanced_color), "") {
                        addView(colorControl(getString(R.string.ui_highlight), style().karaokeHighlightColor) { color ->
                            applyFloatingKaraokeHighlightColor(color)
                            refreshFloatingPreview()
                        })
                    }
                }
            )
        )
    )
}
