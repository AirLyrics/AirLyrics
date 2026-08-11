package com.andsi.airlyrics.ui.pages.floating.sections

import android.graphics.Color
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.alphaToOpacityPercent
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
import com.andsi.airlyrics.ui.pages.floating.opacityPercentToAlpha
import com.andsi.airlyrics.ui.pages.floating.openPanel
import com.andsi.airlyrics.core.color.AirColorUtils

internal fun FloatingPageScope.addAppearanceSection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_appearance)))
    list.addView(
        settingGrid(
            trackedFloatingTile(
                title = getString(R.string.ui_skin_preset),
                subtitle = localizedPresetTitle(style().presetName),
                iconRes = R.drawable.ic_air_style,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_skin_preset), "") {
                        addView(liveOptionGrid(
                            floatingPresets().map { preset ->
                                KeyedOptionItem(
                                    key = preset.key,
                                    title = localizedPresetTitle(preset.key),
                                    selected = preset.key == style().presetName,
                                    action = {
                                        applyFloatingPreset(preset.key)
                                        refreshFloatingPreview()
                                    }
                                )
                            }
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_text_color),
                subtitle = AirColorUtils.colorSummary(style().textColor),
                iconRes = R.drawable.ic_air_text_color,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_text_color),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults ->
                                AirColorUtils.opaqueRgb(current.textColor) == AirColorUtils.opaqueRgb(defaults.textColor)
                            },
                            restoreDefaults = { current, defaults ->
                                current.copy(
                                    textColor = AirColorUtils.withAlpha(
                                        defaults.textColor,
                                        Color.alpha(current.textColor)
                                    )
                                )
                            }
                        )
                    ) {
                        addView(
                            colorControl(
                                title = getString(R.string.ui_text),
                                color = AirColorUtils.opaqueRgb(style().textColor),
                                includeOpacity = false
                            ) { color ->
                                applyFloatingTextColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            }
                        )
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_background_bubble),
                subtitle = if (style().backgroundEnabled) getString(R.string.ui_on) else getString(R.string.ui_off),
                iconRes = R.drawable.ic_air_chat_bubble,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_background_bubble),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults ->
                                current.backgroundEnabled == defaults.backgroundEnabled &&
                                    current.backgroundColor == defaults.backgroundColor &&
                                    current.backgroundAlpha == defaults.backgroundAlpha
                            },
                            restoreDefaults = { current, defaults ->
                                current.copy(
                                    backgroundEnabled = defaults.backgroundEnabled,
                                    backgroundColor = defaults.backgroundColor,
                                    backgroundAlpha = defaults.backgroundAlpha
                                )
                            }
                        )
                    ) {
                        val backgroundButton = actionButton(host, if (style().backgroundEnabled) getString(R.string.ui_background_on) else getString(R.string.ui_background_off)) { }
                        backgroundButton.setOnClickListener {
                            val enabled = !floatingStyle().backgroundEnabled
                            applyFloatingBackgroundEnabled(enabled)
                            backgroundButton.text = if (enabled) getString(R.string.ui_background_on) else getString(R.string.ui_background_off)
                            refreshFloatingPreview()
                        }
                        addView(backgroundButton)
                        addView(sliderRow(
                            title = getString(R.string.ui_opacity),
                            value = alphaToOpacityPercent(style().backgroundAlpha),
                            min = 0,
                            max = 100,
                            suffix = "%"
                        ) { value ->
                            applyFloatingBackgroundAlpha(opacityPercentToAlpha(value))
                            refreshFloatingPreview()
                        })
                        addView(colorControl(
                            title = getString(R.string.ui_background),
                            color = style().backgroundColor,
                            includeOpacity = false
                        ) { color ->
                            applyFloatingBackgroundColor(color, refreshPage = false)
                            refreshFloatingPreview()
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_font_size),
                subtitle = "${style().textSizeSp.toInt()} sp",
                iconRes = R.drawable.ic_air_format_size,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_font_size),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults -> current.textSizeSp == defaults.textSizeSp },
                            restoreDefaults = { current, defaults -> current.copy(textSizeSp = defaults.textSizeSp) }
                        )
                    ) {
                        addView(
                            sliderRow(
                                title = getString(R.string.ui_size),
                                value = style().textSizeSp.toInt(),
                                min = 14,
                                max = 56,
                                suffix = " sp",
                                onChangeFinished = { value ->
                                    applyFloatingTextSize(value.toFloat(), refreshPage = false)
                                    refreshFloatingPreview()
                                }
                            ) { value ->
                                previewFloatingTextSize(value.toFloat())
                            }
                        )
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_font),
                subtitle = fontFamilySubtitle(),
                iconRes = R.drawable.ic_air_font,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_font),
                        ""
                    ) {
                        val availableFonts = FloatingLyricsFontFamily.entries.filter { fontFamily ->
                            fontFamily != FloatingLyricsFontFamily.CUSTOM || hasFloatingCustomFont()
                        }
                        addView(liveOptionGrid(
                            availableFonts.map { fontFamily ->
                                KeyedOptionItem(
                                    key = fontFamily.key,
                                    title = localizedFontFamilyTitle(fontFamily),
                                    selected = fontFamily == style().fontFamily,
                                    action = {
                                        applyFloatingStyle(style().copy(fontFamily = fontFamily))
                                        refreshFloatingPreview()
                                    }
                                )
                            }
                        ))
                        addView(actionButton(
                            host,
                            getString(
                                if (hasFloatingCustomFont()) {
                                    R.string.ui_reimport_font
                                } else {
                                    R.string.ui_import_font
                                }
                            )
                        ) {
                            selectFloatingFontFile()
                        })
                        addView(normalText(host, getString(R.string.ui_font_formats_hint)))
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_font_weight),
                subtitle = fontWeightSubtitle(),
                iconRes = R.drawable.ic_air_font_weight,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_font_weight),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults -> current.fontWeight == defaults.fontWeight },
                            restoreDefaults = { current, defaults -> current.copy(fontWeight = defaults.fontWeight) }
                        )
                    ) {
                        addView(sliderRow(
                            title = getString(R.string.ui_font_weight),
                            value = FloatingLyricsFontWeight.toLevel(style().fontWeight),
                            min = FloatingLyricsFontWeight.LEVEL_MIN,
                            max = FloatingLyricsFontWeight.LEVEL_MAX,
                            suffix = "",
                            step = FloatingLyricsFontWeight.LEVEL_STEP,
                            onChangeFinished = { level ->
                                applyFloatingStyle(
                                    style().copy(fontWeight = FloatingLyricsFontWeight.fromLevel(level))
                                )
                                refreshFloatingPreview()
                            }
                        ) { level ->
                            previewFloatingFontWeight(FloatingLyricsFontWeight.fromLevel(level))
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_font_opacity),
                subtitle = fontOpacitySubtitle(),
                iconRes = R.drawable.ic_air_opacity,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_font_opacity),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults ->
                                Color.alpha(current.textColor) == Color.alpha(defaults.textColor)
                            },
                            restoreDefaults = { current, defaults ->
                                current.copy(
                                    textColor = AirColorUtils.withAlpha(
                                        current.textColor,
                                        Color.alpha(defaults.textColor)
                                    )
                                )
                            }
                        )
                    ) {
                        addView(sliderRow(
                            title = getString(R.string.ui_opacity),
                            value = alphaToOpacityPercent(Color.alpha(style().textColor)),
                            min = 0,
                            max = 100,
                            suffix = "%",
                            onChangeFinished = { percent ->
                                applyFloatingTextAlpha(
                                    opacityPercentToAlpha(percent),
                                    refreshPage = false
                                )
                                refreshFloatingPreview()
                            }
                        ) { percent ->
                            previewFloatingFontOpacity(percent)
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_shadow_stroke),
                subtitle = getString(R.string.ui_radius) + " ${style().shadowRadius.toInt()}",
                iconRes = R.drawable.ic_air_shadow,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_shadow_stroke),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults ->
                                current.shadowRadius == defaults.shadowRadius &&
                                    current.shadowColor == defaults.shadowColor
                            },
                            restoreDefaults = { current, defaults ->
                                current.copy(
                                    shadowRadius = defaults.shadowRadius,
                                    shadowColor = defaults.shadowColor
                                )
                            }
                        )
                    ) {
                        addView(sliderRow(getString(R.string.ui_shadow_radius), style().shadowRadius.toInt(), 0, 24, "") { value ->
                            applyFloatingShadowRadius(value.toFloat())
                            refreshFloatingPreview()
                        })
                        addView(colorControl(getString(R.string.ui_shadow), style().shadowColor) { color ->
                            applyFloatingShadowColor(color)
                            refreshFloatingPreview()
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_window_layout),
                subtitle = getString(R.string.ui_width) + " ${style().maxWidthPercent}%",
                iconRes = R.drawable.ic_air_pip,
                onClick = { tile ->
                    openPanel(
                        tile,
                        getString(R.string.ui_window_layout),
                        "",
                        reset = stylePanelReset(
                            isAtDefault = { current, defaults ->
                                current.maxWidthPercent == defaults.maxWidthPercent &&
                                    current.paddingHorizontalDp == defaults.paddingHorizontalDp &&
                                    current.paddingVerticalDp == defaults.paddingVerticalDp &&
                                    current.cornerRadiusDp == defaults.cornerRadiusDp
                            },
                            restoreDefaults = { current, defaults ->
                                current.copy(
                                    maxWidthPercent = defaults.maxWidthPercent,
                                    paddingHorizontalDp = defaults.paddingHorizontalDp,
                                    paddingVerticalDp = defaults.paddingVerticalDp,
                                    cornerRadiusDp = defaults.cornerRadiusDp
                                )
                            }
                        )
                    ) {
                        addView(sliderRow(getString(R.string.ui_max_width), style().maxWidthPercent, 45, 100, "%") { value ->
                            applyFloatingMaxWidthPercent(value)
                            refreshFloatingPreview()
                        })
                        addView(sliderRow(getString(R.string.ui_horizontal_padding), style().paddingHorizontalDp, 0, 36, "dp") { value ->
                            applyFloatingPaddingHorizontal(value)
                            refreshFloatingPreview()
                        })
                        addView(sliderRow(getString(R.string.ui_vertical_padding), style().paddingVerticalDp, 0, 28, "dp") { value ->
                            applyFloatingPaddingVertical(value)
                            refreshFloatingPreview()
                        })
                        addView(sliderRow(getString(R.string.ui_corner_radius), style().cornerRadiusDp, 0, 36, "dp") { value ->
                            applyFloatingCornerRadius(value)
                            refreshFloatingPreview()
                        })
                    }
                }
            )
        )
    )
}
