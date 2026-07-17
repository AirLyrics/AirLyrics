package com.andsi.airlyrics.ui.pages.floating.sections

import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.pages.floating.floatingSectionTitle
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
                    openPanel(tile, getString(R.string.ui_text_color), "") {
                        addView(colorControl(getString(R.string.ui_text), style().textColor) { color ->
                            applyFloatingTextColor(color, refreshPage = false)
                            refreshFloatingPreview()
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_background_bubble),
                subtitle = if (style().backgroundEnabled) getString(R.string.ui_on) else getString(R.string.ui_off),
                iconRes = R.drawable.ic_air_chat_bubble,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_background_bubble), "") {
                        val backgroundButton = actionButton(host, if (style().backgroundEnabled) getString(R.string.ui_background_on) else getString(R.string.ui_background_off)) { }
                        backgroundButton.setOnClickListener {
                            val enabled = !floatingStyle().backgroundEnabled
                            applyFloatingBackgroundEnabled(enabled)
                            backgroundButton.text = if (enabled) getString(R.string.ui_background_on) else getString(R.string.ui_background_off)
                            refreshFloatingPreview()
                        }
                        addView(backgroundButton)
                        val currentStyle = style()
                        val backgroundColor = AirColorUtils.withAlpha(
                            currentStyle.backgroundColor,
                            currentStyle.backgroundAlpha
                        )
                        addView(colorControl(getString(R.string.ui_background), backgroundColor) { color ->
                            applyFloatingBackgroundColor(color, refreshPage = false)
                            refreshFloatingPreview()
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_font_size),
                subtitle = "${style().textSizeSp.toInt()}sp",
                iconRes = R.drawable.ic_air_format_size,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_font_size), "") {
                        addView(sliderRow(getString(R.string.ui_size), style().textSizeSp.toInt(), 14, 56, "sp") { value ->
                            applyFloatingTextSize(value.toFloat(), refreshPage = false)
                            refreshFloatingPreview()
                        })
                    }
                }
            ),
            trackedFloatingTile(
                title = getString(R.string.ui_shadow_stroke),
                subtitle = getString(R.string.ui_radius) + " ${style().shadowRadius.toInt()}",
                iconRes = R.drawable.ic_air_shadow,
                onClick = { tile ->
                    openPanel(tile, getString(R.string.ui_shadow_stroke), "") {
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
                    openPanel(tile, getString(R.string.ui_window_layout), "") {
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
