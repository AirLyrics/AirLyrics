package com.andsi.airlyrics.ui.pages.floating.sections

import android.graphics.Color
import com.andsi.airlyrics.ui.state.FloatingPanelId
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.FloatingLyricsTextSize
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.FloatingPanelNavigation
import com.andsi.airlyrics.ui.pages.floating.FloatingColorPanelContent
import com.andsi.airlyrics.ui.pages.floating.FloatingFontPanelContent
import com.andsi.airlyrics.ui.pages.floating.alphaToOpacityPercent
import com.andsi.airlyrics.ui.pages.floating.compactColorSummary
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
import com.andsi.airlyrics.ui.pages.floating.opacityPercentToAlpha
import com.andsi.airlyrics.ui.pages.floating.openPanel
import com.andsi.airlyrics.ui.pages.floating.openStylePanel
import com.andsi.airlyrics.core.color.AirColorUtils

internal fun FloatingPageScope.addAppearanceSection(list: LinearLayout) = with(host) {
    list.addView(floatingSectionTitle(getString(R.string.ui_appearance)))
    list.addView(
        settingGrid(
            trackedFloatingTile(
                id = FloatingPanelId.PRESET,
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
                id = FloatingPanelId.TEXT_COLOR,
                title = getString(R.string.ui_text_color),
                subtitle = compactColorSummary(style().textColor),
                iconRes = R.drawable.ic_air_text_color,
                onClick = { tile ->
                    val navigation = FloatingPanelNavigation()
                    openStylePanel(
                        tile,
                        getString(R.string.ui_text_color),
                        navigation = navigation,
                        restoreDefaults = { current, defaults ->
                            current.copy(
                                textColor = AirColorUtils.withAlpha(
                                    defaults.textColor,
                                    Color.alpha(current.textColor)
                                )
                            )
                        }
                    ) { edits ->
                        addView(FloatingColorPanelContent(
                            host = host,
                            navigation = navigation,
                            edits = edits,
                            colorLabel = R.string.ui_text_color,
                            readColor = FloatingLyricsStyle::textColor,
                            withColor = { style, color -> style.copy(textColor = color) },
                            onColorChanged = { color ->
                                applyFloatingTextColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            }
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.BACKGROUND,
                title = getString(R.string.ui_background_bubble),
                subtitle = getString(if (style().backgroundEnabled) R.string.ui_on else R.string.ui_off),
                iconRes = R.drawable.ic_air_chat_bubble,
                onClick = { tile ->
                    val navigation = FloatingPanelNavigation()
                    openStylePanel(
                        tile,
                        getString(R.string.ui_background_bubble),
                        navigation = navigation,
                        restoreDefaults = { current, defaults ->
                            current.copy(
                                backgroundEnabled = defaults.backgroundEnabled,
                                backgroundColor = defaults.backgroundColor,
                                backgroundAlpha = defaults.backgroundAlpha,
                                backgroundBlurPercent = defaults.backgroundBlurPercent
                            )
                        }
                    ) { edits ->
                        val controls = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(edits.toggle(
                                getString(R.string.ui_background),
                                FloatingLyricsStyle::backgroundEnabled,
                                ::applyFloatingBackgroundEnabled
                            ))
                            addView(edits.slider(
                                title = getString(R.string.ui_opacity),
                                read = { alphaToOpacityPercent(it.backgroundAlpha) },
                                min = 0, max = 100, suffix = "%",
                                write = { applyFloatingBackgroundAlpha(it.backgroundAlpha) }
                            ) { style, value -> style.copy(backgroundAlpha = opacityPercentToAlpha(value)) })
                            addView(edits.slider(
                                title = getString(R.string.ui_blur_amount),
                                read = FloatingLyricsStyle::backgroundBlurPercent,
                                min = 0, max = 100, suffix = "%",
                                write = { applyFloatingBackgroundBlurPercent(it.backgroundBlurPercent) }
                            ) { style, value -> style.copy(backgroundBlurPercent = value) })
                        }
                        addView(FloatingColorPanelContent(
                            host = host,
                            navigation = navigation,
                            edits = edits,
                            colorLabel = R.string.ui_background_bubble,
                            readColor = FloatingLyricsStyle::backgroundColor,
                            withColor = { style, color -> style.copy(backgroundColor = color) },
                            onColorChanged = { color ->
                                applyFloatingBackgroundColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            },
                            leadingControls = controls
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.TEXT_SIZE,
                title = getString(R.string.ui_font_size),
                subtitle = "${style().textSizeSp.toInt()} sp",
                iconRes = R.drawable.ic_air_format_size,
                onClick = { tile ->
                    openStylePanel(
                        tile,
                        getString(R.string.ui_font_size),
                        restoreDefaults = { current, defaults -> current.copy(textSizeSp = defaults.textSizeSp) }
                    ) { edits ->
                        addView(edits.slider(
                            title = getString(R.string.ui_size),
                            read = { it.textSizeSp.toInt() },
                            min = FloatingLyricsTextSize.MIN_SP,
                            max = FloatingLyricsTextSize.MAX_SP,
                            suffix = " sp",
                            write = { applyFloatingTextSize(it.textSizeSp, refreshPage = false) }
                        ) { style, value -> style.copy(textSizeSp = value.toFloat()) })
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.FONT,
                title = getString(R.string.ui_font),
                subtitle = fontFamilySubtitle(),
                iconRes = R.drawable.ic_air_font,
                onClick = { tile ->
                    val navigation = FloatingPanelNavigation()
                    openStylePanel(
                        tile,
                        getString(R.string.ui_font),
                        navigation = navigation,
                        restoreDefaults = { current, _ ->
                            current.copy(
                                fontFamily = FloatingLyricsFontFamily.SYSTEM_DEFAULT,
                                customFontId = null
                            )
                        }
                    ) { edits ->
                        val manager = FloatingFontPanelContent(
                            host, navigation,
                            onChanged = {
                                refreshFloatingPreview()
                                notifyFloatingStyleChanged()
                            },
                            edits = edits
                        )
                        pageRefs.refreshFonts = manager::showImportedFont
                        addView(manager)
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.FONT_WEIGHT,
                title = getString(R.string.ui_font_weight),
                subtitle = fontWeightSubtitle(),
                iconRes = R.drawable.ic_air_font_weight,
                onClick = { tile ->
                    openStylePanel(
                        tile,
                        getString(R.string.ui_font_weight),
                        restoreDefaults = { current, defaults -> current.copy(fontWeight = defaults.fontWeight) }
                    ) { edits ->
                        addView(edits.slider(
                            title = getString(R.string.ui_font_weight),
                            read = { FloatingLyricsFontWeight.toLevel(it.fontWeight) },
                            min = FloatingLyricsFontWeight.LEVEL_MIN,
                            max = FloatingLyricsFontWeight.LEVEL_MAX,
                            suffix = "",
                            step = FloatingLyricsFontWeight.LEVEL_STEP
                        ) { style, level -> style.copy(fontWeight = FloatingLyricsFontWeight.fromLevel(level)) })
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.TEXT_OPACITY,
                title = getString(R.string.ui_font_opacity),
                subtitle = fontOpacitySubtitle(),
                iconRes = R.drawable.ic_air_opacity,
                onClick = { tile ->
                    openStylePanel(
                        tile,
                        getString(R.string.ui_font_opacity),
                        restoreDefaults = { current, defaults ->
                            current.copy(
                                textColor = AirColorUtils.withAlpha(
                                    current.textColor,
                                    Color.alpha(defaults.textColor)
                                )
                            )
                        }
                    ) { edits ->
                        addView(edits.slider(
                            title = getString(R.string.ui_opacity),
                            read = { alphaToOpacityPercent(Color.alpha(it.textColor)) },
                            min = 0, max = 100, suffix = "%",
                            write = { applyFloatingTextAlpha(Color.alpha(it.textColor), refreshPage = false) }
                        ) { style, percent ->
                            style.copy(textColor = AirColorUtils.withAlpha(style.textColor, opacityPercentToAlpha(percent)))
                        })
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.SHADOW,
                title = getString(R.string.ui_shadow_stroke),
                subtitle = getString(R.string.ui_radius) + " ${style().shadowRadius.toInt()}",
                iconRes = R.drawable.ic_air_shadow,
                onClick = { tile ->
                    val navigation = FloatingPanelNavigation()
                    openStylePanel(
                        tile,
                        getString(R.string.ui_shadow_stroke),
                        navigation = navigation,
                        restoreDefaults = { current, defaults ->
                            current.copy(
                                shadowRadius = defaults.shadowRadius,
                                shadowColor = defaults.shadowColor
                            )
                        }
                    ) { edits ->
                        val controls = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(edits.slider(
                                title = getString(R.string.ui_shadow_radius),
                                read = { it.shadowRadius.toInt() },
                                min = 0, max = 24, suffix = "", compact = true,
                                write = { applyFloatingShadowRadius(it.shadowRadius) }
                            ) { style, value -> style.copy(shadowRadius = value.toFloat()) })
                            addView(edits.slider(
                                title = getString(R.string.ui_opacity),
                                read = { alphaToOpacityPercent(Color.alpha(it.shadowColor)) },
                                min = 0, max = 100, suffix = "%", compact = true,
                                write = { applyFloatingShadowColor(it.shadowColor) }
                            ) { style, value ->
                                style.copy(shadowColor = AirColorUtils.withAlpha(style.shadowColor, opacityPercentToAlpha(value)))
                            })
                        }
                        addView(FloatingColorPanelContent(
                            host = host,
                            navigation = navigation,
                            edits = edits,
                            colorLabel = R.string.ui_shadow_stroke,
                            readColor = FloatingLyricsStyle::shadowColor,
                            withColor = { style, color -> style.copy(shadowColor = color) },
                            onColorChanged = { color ->
                                applyFloatingShadowColor(color)
                                refreshFloatingPreview()
                            },
                            additionalControls = controls
                        ))
                    }
                }
            ),
            trackedFloatingTile(
                id = FloatingPanelId.WINDOW_LAYOUT,
                title = getString(R.string.ui_window_layout),
                subtitle = getString(R.string.ui_width) + " ${style().maxWidthPercent}%",
                iconRes = R.drawable.ic_air_pip,
                onClick = { tile ->
                    openStylePanel(
                        tile,
                        getString(R.string.ui_window_layout),
                        restoreDefaults = { current, defaults ->
                            current.copy(
                                maxWidthPercent = defaults.maxWidthPercent,
                                paddingHorizontalDp = defaults.paddingHorizontalDp,
                                paddingVerticalDp = defaults.paddingVerticalDp,
                                cornerRadiusDp = defaults.cornerRadiusDp
                            )
                        }
                    ) { edits ->
                        addView(edits.slider(
                            getString(R.string.ui_max_width), FloatingLyricsStyle::maxWidthPercent,
                            min = 45, max = 100, suffix = "%",
                            write = { applyFloatingMaxWidthPercent(it.maxWidthPercent) }
                        ) { style, value -> style.copy(maxWidthPercent = value) })
                        addView(edits.slider(
                            getString(R.string.ui_horizontal_padding), FloatingLyricsStyle::paddingHorizontalDp,
                            min = 0, max = 36, suffix = "dp",
                            write = { applyFloatingPaddingHorizontal(it.paddingHorizontalDp) }
                        ) { style, value -> style.copy(paddingHorizontalDp = value) })
                        addView(edits.slider(
                            getString(R.string.ui_vertical_padding), FloatingLyricsStyle::paddingVerticalDp,
                            min = 0, max = 28, suffix = "dp",
                            write = { applyFloatingPaddingVertical(it.paddingVerticalDp) }
                        ) { style, value -> style.copy(paddingVerticalDp = value) })
                        addView(edits.slider(
                            getString(R.string.ui_corner_radius), FloatingLyricsStyle::cornerRadiusDp,
                            min = 0, max = 36, suffix = "dp",
                            write = { applyFloatingCornerRadius(it.cornerRadiusDp) }
                        ) { style, value -> style.copy(cornerRadiusDp = value) })
                    }
                }
            )
        )
    )
}
