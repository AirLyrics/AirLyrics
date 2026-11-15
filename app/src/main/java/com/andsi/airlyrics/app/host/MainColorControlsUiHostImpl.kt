package com.andsi.airlyrics.app.host

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.AdaptiveLabelValueLayout
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.GridColumnPolicy
import com.andsi.airlyrics.ui.components.SettingsSliderRow
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.util.Locale

internal fun MainUiHost.sliderRowImpl(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String,
    step: Int,
    onChangeFinished: ((Int) -> Unit)?,
    onChanged: (Int) -> Unit,
    compact: Boolean = false
): LinearLayout {
    val key = nextPanelControlKey("slider")
    val restoredValue = key?.let { interactions.read(it) }?.getInt("preview", value) ?: value
    val safeStep = step.coerceAtLeast(1)
    val safeValue = restoredValue.coerceIn(min, max).let {
        min + ((it - min + safeStep / 2) / safeStep) * safeStep
    }.coerceAtMost(max)
    // Preview and persistence remain separate, including during activity recreation.
    if (restoredValue != value && onChangeFinished != null) onChanged(safeValue)
    fun valueLabel(value: Int): String =
        String.format(resources.configuration.locales[0], "%d%s", value, suffix)
    val row = SettingsSliderRow(this, title, valueLabel(safeValue), compact)
    row.seekBar.apply {
        this.max = (max - min) / safeStep
        progress = (safeValue - min) / safeStep
        var latestValue = safeValue
        var isTracking = false
        var changedWhileTracking = false
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newValue = min + progress * safeStep
                latestValue = newValue
                row.updateValue(valueLabel(newValue))
                if (fromUser) {
                    key?.let { interactions.write(it) { putInt("preview", newValue) } }
                    onChanged(newValue)
                    if (isTracking) {
                        changedWhileTracking = true
                    } else {
                        onChangeFinished?.invoke(newValue)
                        key?.let(interactions::remove)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTracking = true
                changedWhileTracking = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTracking = false
                if (changedWhileTracking) {
                    onChangeFinished?.invoke(latestValue)
                    key?.let(interactions::remove)
                }
                changedWhileTracking = false
            }
        })
    }
    return row
}

internal fun MainUiHost.colorControlImpl(
    title: String,
    color: Int,
    includeOpacity: Boolean,
    onChanged: (Int) -> Unit
): LinearLayout {
    val activity = this
    var red = Color.red(color)
    var green = Color.green(color)
    var blue = Color.blue(color)
    var alpha = if (includeOpacity) Color.alpha(color) else 255
    val controlKey = nextPanelControlKey("color")
    var rgbExpanded = controlKey?.let { interactions.read(it)?.getBoolean("expanded") } ?: false

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
        setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
        val valueText = TextView(activity).apply {
            textSize = AirUiTokens.TextSize.BodySmall
            fontFeatureSettings = "tnum"
            setTextColor(colorTextMuted)
        }
        val sample = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(AirUiTokens.SettingsPanel.ColorSampleSize),
                dp(AirUiTokens.SettingsPanel.ColorSampleSize)
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val preview = AdaptiveLabelValueLayout(
            activity,
            TextView(activity).apply {
                text = title
                textSize = AirUiTokens.TextSize.Button
                setTextColor(colorTextStrong)
            },
            valueText,
            trailingView = sample,
            horizontalGapPx = dp(AirUiTokens.SettingsPanel.ControlGap),
            verticalGapPx = dp(AirUiTokens.Space.Lg),
            trailingGapPx = dp(AirUiTokens.Space.Xl),
            trailingAtTop = true
        )
        addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val swatchViews = mutableListOf<Pair<Int?, TextView>>()
        val swatchGrid = AdaptiveGridLayout(
            activity, AirUiTokens.Layout.SwatchColumns,
            dp(AirUiTokens.SettingsPanel.ControlGap), dp(AirUiTokens.SettingsPanel.ControlGap),
            columnPolicy = GridColumnPolicy.Width(AirUiTokens.Layout.SwatchMinWidth)
        )
        addView(spacer(activity, AirUiTokens.SettingsPanel.ControlGap))
        addView(swatchGrid)
        addView(spacer(activity, AirUiTokens.Space.Sm))

        val fineTuneButton = actionButton(activity, getString(if (rgbExpanded) R.string.ui_hide_rgb else R.string.ui_rgb_tune)) { }.apply {
            activity.styleAirTonalButton(this, AirTonalButtonTone.DEFAULT)
        }
        val rgbPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (rgbExpanded) View.VISIBLE else View.GONE
            setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
        }
        addView(fineTuneButton)
        addView(rgbPanel)

        fun currentColor(): Int = Color.argb(alpha, red, green, blue)

        fun selectedStandardColor(): Int? = standardColors.firstOrNull { (_, swatchColor) ->
            AirColorUtils.opaqueRgb(swatchColor) == AirColorUtils.opaqueRgb(currentColor())
        }?.second

        fun colorSample(swatchColor: Int, selected: Boolean): android.graphics.drawable.Drawable {
            val displayed = ColorUtils.compositeColors(swatchColor, colorSurfaceLight)
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(displayed)
                setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
            }
            val drawable = if (selected) {
                val check = requireNotNull(AppCompatResources.getDrawable(activity, R.drawable.ic_air_check)).mutate()
                check.setTint(if (AirColorUtils.isDarkColor(displayed)) Color.WHITE else Color.BLACK)
                LayerDrawable(arrayOf(circle, check)).apply {
                    val inset = dp(AirUiTokens.Space.Sm)
                    setLayerInset(1, inset, inset, inset, inset)
                }
            } else circle
            drawable.setBounds(0, 0, dp(AirUiTokens.SettingsPanel.ColorSampleSize), dp(AirUiTokens.SettingsPanel.ColorSampleSize))
            return drawable
        }

        fun refreshSwatches() {
            val selectedPreset = selectedStandardColor()
            swatchViews.forEach { (presetColor, view) ->
                val selected = if (presetColor == null) selectedPreset == null else presetColor == selectedPreset
                activity.styleAirTonalButton(view, if (selected) AirTonalButtonTone.SELECTED else AirTonalButtonTone.DEFAULT)
                view.isSelected = selected
                view.setCompoundDrawablesRelative(null, colorSample(presetColor ?: currentColor(), selected), null, null)
                view.compoundDrawablePadding = dp(AirUiTokens.Space.Xl)
            }
        }

        fun refreshPreview(dispatch: Boolean) {
            val newColor = currentColor()
            valueText.text = if (includeOpacity) {
                String.format(Locale.ROOT, "#%06X · A%d", newColor and 0x00FFFFFF, Color.alpha(newColor))
            } else {
                String.format(Locale.ROOT, "#%06X", newColor and 0x00FFFFFF)
            }
            valueText.contentDescription = AirColorUtils.colorSummary(newColor)
            sample.background = colorSample(newColor, false)
            refreshSwatches()
            if (dispatch) onChanged(newColor)
        }

        fun colorSliderRow(
            sliderTitle: String,
            initialValue: Int,
            onValueChanged: (Int) -> Unit
        ): SettingsSliderRow {
            val row = SettingsSliderRow(activity, sliderTitle, initialValue.toString())
            row.seekBar.apply {
                max = 255
                progress = initialValue.coerceIn(0, 255)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        row.updateValue(progress.toString())
                        if (fromUser) {
                            onValueChanged(progress)
                            refreshPreview(dispatch = true)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            rgbPanel.addView(row)
            return row
        }

        val redSlider = colorSliderRow("R", red) { red = it }
        val greenSlider = colorSliderRow("G", green) { green = it }
        val blueSlider = colorSliderRow("B", blue) { blue = it }
        val alphaSlider = if (includeOpacity) colorSliderRow(getString(R.string.ui_opacity), alpha) { alpha = it } else null

        fun syncSliders() {
            redSlider.seekBar.progress = red
            greenSlider.seekBar.progress = green
            blueSlider.seekBar.progress = blue
            alphaSlider?.seekBar?.progress = alpha
        }

        val swatches = standardColors.map { (label, swatchColor) ->
            label to (swatchColor as Int?)
        } + (getString(R.string.ui_custom) to null)

        swatches.forEach { (label, presetColor) ->
            val swatch = TextView(activity).apply {
                text = label
                textSize = AirUiTokens.TextSize.BodySmall
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
                isFocusable = true
                setPadding(dp(AirUiTokens.Space.Sm), dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Sm), dp(AirUiTokens.Space.Xl))
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    if (presetColor == null) {
                        rgbExpanded = true
                        controlKey?.let { interactions.write(it) { putBoolean("expanded", true) } }
                        rgbPanel.visibility = View.VISIBLE
                        fineTuneButton.setText(R.string.ui_hide_rgb)
                    } else {
                        red = Color.red(presetColor)
                        green = Color.green(presetColor)
                        blue = Color.blue(presetColor)
                        syncSliders()
                        refreshPreview(dispatch = true)
                    }
                    playTinyPulse(this)
                }
            }
            swatchViews.add(presetColor to swatch)
            swatchGrid.addView(swatch)
        }

        fineTuneButton.setOnClickListener {
            rgbExpanded = !rgbExpanded
            controlKey?.let { interactions.write(it) { putBoolean("expanded", rgbExpanded) } }
            rgbPanel.visibility = if (rgbExpanded) View.VISIBLE else View.GONE
            fineTuneButton.setText(if (rgbExpanded) R.string.ui_hide_rgb else R.string.ui_rgb_tune)
            playTinyPulse(fineTuneButton)
        }
        syncSliders()
        refreshPreview(dispatch = false)
    }
}
