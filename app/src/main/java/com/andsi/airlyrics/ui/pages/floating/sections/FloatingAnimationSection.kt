package com.andsi.airlyrics.ui.pages.floating.sections

import com.andsi.airlyrics.ui.state.FloatingPanelId
import android.widget.LinearLayout
import android.view.View
import com.andsi.airlyrics.ui.components.panelSectionLabel
import com.andsi.airlyrics.ui.components.beginFloatingPanelChange
import com.andsi.airlyrics.ui.components.panelSwitch
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsSwitchAnimationTitle
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.FloatingPanelNavigation
import com.andsi.airlyrics.ui.pages.floating.FloatingColorPanelContent
import com.andsi.airlyrics.core.color.AirColorUtils
import android.graphics.Color
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
import com.andsi.airlyrics.ui.pages.floating.openPanel

internal fun FloatingPageScope.addAnimationSection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_animation)))
    list.addView(
        settingGrid(
            trackedFloatingTile(
                id = FloatingPanelId.ANIMATION,
                title = getString(R.string.ui_switch_animation),
                subtitle = localizedLyricsSwitchAnimationTitle(switchAnimationMode()),
                iconRes = R.drawable.ic_air_lyrics,
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
                id = FloatingPanelId.WORD_BY_WORD,
                title = getString(R.string.ui_word_by_word_lyrics),
                subtitle = wordByWordLyricsSubtitle(),
                iconRes = R.drawable.ic_air_motion,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_word_by_word_lyrics), "") {
                        addView(panelSwitch(host, getString(R.string.ui_word_by_word_lyrics), wordByWordLyricsEnabled()) { enabled ->
                            applyWordByWordLyricsChanged(enabled)
                        })
                        addView(panelSectionLabel(host, getString(R.string.ui_word_animation)))
                        addView(liveOptionGrid(WordByWordAnimationMode.entries.map { mode ->
                            KeyedOptionItem(mode.key,
                                getString(if (mode == WordByWordAnimationMode.CLASSIC) R.string.ui_animation_classic else R.string.ui_animation_spicy),
                                wordByWordAnimationMode() == mode) {
                                setWordByWordAnimationMode(mode)
                                applyLyricsDisplaySettingsChanged()
                            }
                        }))
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.HIGHLIGHT_COLOR,
                title = getString(R.string.ui_highlight_effects),
                subtitle = highlightEffectsSubtitle(),
                iconRes = R.drawable.ic_air_highlight,
                onClick = { tile ->
                    val navigation = FloatingPanelNavigation()
                    openPanel(
                        tile,
                        getString(R.string.ui_highlight_effects),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults ->
                                AirColorUtils.opaqueRgb(current.wordByWordHighlightColor) ==
                                    AirColorUtils.opaqueRgb(defaults.wordByWordHighlightColor) &&
                                    current.glow == defaults.glow
                            },
                            restoreDefaults = { current, defaults ->
                                current.copy(
                                    wordByWordHighlightColor = AirColorUtils.withAlpha(
                                        defaults.wordByWordHighlightColor, Color.alpha(current.wordByWordHighlightColor)
                                    ),
                                    glow = defaults.glow
                                )
                            }
                        ),
                        navigation = navigation
                    ) {
                        setPadding(paddingLeft, paddingTop, paddingRight, dp(12))
                        val glowControls = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            visibility = if (style().glow.enabled) View.VISIBLE else View.GONE
                            addView(sliderRow(
                                title = getString(R.string.ui_glow_strength_short),
                                value = style().glow.strengthPercent,
                                min = 0, max = 200, suffix = "%", step = 10, compact = true,
                                onChangeFinished = { value ->
                                    applyFloatingStyle(style().copy(glow = style().glow.copy(strengthPercent = value)))
                                    previewFloatingGlow(style().glow)
                                    refreshFloatingSettingTiles()
                                }
                            ) { value -> previewFloatingGlow(style().glow.copy(strengthPercent = value)) })
                            addView(sliderRow(
                                title = getString(R.string.ui_glow_spread_short),
                                value = style().glow.spreadPercent,
                                min = 50, max = 200, suffix = "%", step = 10, compact = true,
                                onChangeFinished = { value ->
                                    applyFloatingStyle(style().copy(glow = style().glow.copy(spreadPercent = value)))
                                    previewFloatingGlow(style().glow)
                                    refreshFloatingSettingTiles()
                                }
                            ) { value -> previewFloatingGlow(style().glow.copy(spreadPercent = value)) })
                        }
                        val effects = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(panelSwitch(host, getString(R.string.ui_glow), style().glow.enabled) { enabled ->
                                beginFloatingPanelChange()
                                applyFloatingStyle(style().copy(glow = style().glow.copy(enabled = enabled)))
                                glowControls.visibility = if (enabled) View.VISIBLE else View.GONE
                                refreshFloatingPreview()
                            })
                            addView(glowControls)
                        }
                        addView(FloatingColorPanelContent(
                            host = host,
                            navigation = navigation,
                            currentColor = { style().wordByWordHighlightColor },
                            onColorChanged = { color ->
                                applyFloatingWordByWordHighlightColor(color)
                                refreshFloatingPreview()
                            },
                            additionalControls = effects
                        ))
                    }
                }
            )
        )
    )
}
