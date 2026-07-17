package com.andsi.airlyrics.app.host

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.sliderRowImpl(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String,
    onChanged: (Int) -> Unit
): LinearLayout {
    val activity = this
    val safeValue = value.coerceIn(min, max)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Xl), 0, dp(AirUiTokens.Space.Sm))

        val valueText = TextView(activity).apply {
            text = getString(R.string.field_value_with_suffix, title, safeValue, suffix)
            textSize = AirUiTokens.TextSize.Body
            setTextColor(colorText)
            setPadding(0, 0, 0, dp(AirUiTokens.Space.Lg))
        }
        addView(valueText)

        addView(SeekBar(activity).apply {
            this.max = max - min
            progress = safeValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val newValue = min + progress
                    valueText.text = getString(R.string.field_value_with_suffix, title, newValue, suffix)
                    if (fromUser) onChanged(newValue)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
    }
}

internal fun MainUiHost.colorControlImpl(
    title: String,
    color: Int,
    onChanged: (Int) -> Unit
): LinearLayout {
    val activity = this
    var red = Color.red(color)
    var green = Color.green(color)
    var blue = Color.blue(color)
    var alpha = Color.alpha(color)
    var rgbExpanded = false

    val standardColors = listOf(
        getString(R.string.ui_blue) to Color.rgb(66, 165, 245),
        getString(R.string.ui_purple) to Color.rgb(126, 87, 194),
        getString(R.string.ui_pink) to Color.rgb(236, 64, 122),
        getString(R.string.ui_cyan) to Color.rgb(38, 198, 218),
        getString(R.string.ui_green) to Color.rgb(102, 187, 106),
        getString(R.string.ui_orange) to Color.rgb(255, 167, 38),
        getString(R.string.ui_red) to Color.rgb(239, 83, 80),
        getString(R.string.ui_white) to Color.WHITE
    )

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Lg), 0, 0)

        val preview = TextView(activity).apply {
            textSize = AirUiTokens.TextSize.Body
            setTextColor(colorText)
            setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl))
        }
        addView(preview)

        val swatchViews = mutableListOf<Pair<Int?, TextView>>()
        val swatchGrid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(swatchGrid)

        val fineTuneButton = actionButton(activity, getString(R.string.ui_rgb_tune)) { }
        val rgbPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(AirUiTokens.Space.Lg), 0, 0)
        }
        addView(fineTuneButton)
        addView(rgbPanel)

        fun currentColor(): Int = Color.argb(alpha, red, green, blue)

        fun selectedStandardColor(): Int? {
            val current = currentColor()
            return standardColors.firstOrNull { (_, swatchColor) ->
                Color.red(current) == Color.red(swatchColor) &&
                    Color.green(current) == Color.green(swatchColor) &&
                    Color.blue(current) == Color.blue(swatchColor) &&
                    Color.alpha(current) == Color.alpha(swatchColor)
            }?.second
        }

        fun swatchBackground(swatchColor: Int, selected: Boolean): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = dp(AirUiTokens.Space.ButtonH).toFloat()
                setColor(swatchColor)
                setStroke(dp(if (selected) 3 else 1), if (selected) colorAccent else colorStroke)
            }
        }

        fun refreshSwatches() {
            val current = currentColor()
            val selectedPreset = selectedStandardColor()
            swatchViews.forEach { (presetColor, view) ->
                val selected = if (presetColor == null) {
                    selectedPreset == null
                } else {
                    selectedPreset != null &&
                        Color.red(selectedPreset) == Color.red(presetColor) &&
                        Color.green(selectedPreset) == Color.green(presetColor) &&
                        Color.blue(selectedPreset) == Color.blue(presetColor)
                }
                val displayColor = presetColor ?: current
                view.background = swatchBackground(displayColor, selected)
                view.setTextColor(if (AirColorUtils.isDarkColor(displayColor)) Color.WHITE else Color.rgb(28, 34, 46))
            }
        }

        fun refreshPreview(dispatch: Boolean) {
            val newColor = currentColor()
            preview.text = getString(R.string.floating_color_summary, title, AirColorUtils.colorSummary(newColor))
            preview.background = colorPreviewBackground(newColor)
            refreshSwatches()
            if (dispatch) onChanged(newColor)
        }

        fun colorSliderRow(
            sliderTitle: String,
            initialValue: Int,
            onValueChanged: (Int) -> Unit
        ): Pair<SeekBar, TextView> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(AirUiTokens.Space.Xl), 0, dp(AirUiTokens.Space.Sm))
            }
            val valueText = TextView(activity).apply {
                text = getString(R.string.floating_slider_value, sliderTitle, initialValue)
                textSize = AirUiTokens.TextSize.Body
                setTextColor(colorText)
                setPadding(0, 0, 0, dp(AirUiTokens.Space.Lg))
            }
            row.addView(valueText)
            val seekBar = SeekBar(activity).apply {
                max = 255
                progress = initialValue.coerceIn(0, 255)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueText.text = getString(R.string.floating_slider_value, sliderTitle, progress)
                        if (fromUser) {
                            onValueChanged(progress)
                            refreshPreview(dispatch = true)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            row.addView(seekBar)
            rgbPanel.addView(row)
            return seekBar to valueText
        }

        val redSlider = colorSliderRow("R", red) { red = it }
        val greenSlider = colorSliderRow("G", green) { green = it }
        val blueSlider = colorSliderRow("B", blue) { blue = it }
        val alphaSlider = colorSliderRow(getString(R.string.ui_opacity), alpha) { alpha = it }

        fun setSlider(pair: Pair<SeekBar, TextView>, titleText: String, value: Int) {
            pair.first.progress = value.coerceIn(0, 255)
            pair.second.text = getString(R.string.field_value, titleText, value)
        }

        fun syncSliders() {
            setSlider(redSlider, "R", red)
            setSlider(greenSlider, "G", green)
            setSlider(blueSlider, "B", blue)
            setSlider(alphaSlider, getString(R.string.ui_opacity), alpha)
        }

        fun makeSwatch(label: String, presetColor: Int?, onClick: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                textSize = AirUiTokens.TextSize.Caption
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(AirUiTokens.Space.Lg), 0, dp(AirUiTokens.Space.Lg), 0)
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    onClick()
                    playTinyPulse(this)
                }
                swatchViews.add(presetColor to this)
            }
        }

        val swatches = standardColors.map { (label, swatchColor) ->
            Pair(label, swatchColor as Int?)
        } + listOf(getString(R.string.ui_custom) to null)

        swatches.chunked(AirUiTokens.Layout.SwatchColumns).forEach { rowItems ->
            swatchGrid.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, (label, presetColor) ->
                    val button = makeSwatch(label, presetColor) {
                        if (presetColor == null) {
                            rgbExpanded = true
                            rgbPanel.visibility = View.VISIBLE
                            fineTuneButton.text = getString(R.string.ui_hide_rgb)
                        } else {
                            red = Color.red(presetColor)
                            green = Color.green(presetColor)
                            blue = Color.blue(presetColor)
                            syncSliders()
                            refreshPreview(dispatch = true)
                        }
                    }
                    addView(button.apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(AirUiTokens.Layout.ColorSwatchHeight), 1f).apply {
                            setMargins(
                                if (index == 0) 0 else dp(AirUiTokens.Space.Md),
                                dp(AirUiTokens.Space.Xl),
                                if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Md),
                                0
                            )
                        }
                    })
                }
                repeat(AirUiTokens.Layout.SwatchColumns - rowItems.size) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Md), 0, 0, 0)
                        }
                    })
                }
            })
        }

        fineTuneButton.setOnClickListener {
            rgbExpanded = !rgbExpanded
            rgbPanel.visibility = if (rgbExpanded) View.VISIBLE else View.GONE
            fineTuneButton.text = if (rgbExpanded) getString(R.string.ui_hide_rgb) else getString(R.string.ui_rgb_tune)
            playTinyPulse(fineTuneButton)
        }

        syncSliders()
        refreshPreview(dispatch = false)
    }
}

internal fun MainUiHost.colorPreviewBackgroundImpl(color: Int): GradientDrawable {
    return GradientDrawable().apply {
        cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
        setColor(AirColorUtils.withAlpha(color, 42))
        setStroke(dp(AirUiTokens.Stroke.Hairline), AirColorUtils.withAlpha(color, 190))
    }
}
