package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost

import android.graphics.Color
import android.app.Dialog
import android.widget.FrameLayout
import android.widget.ScrollView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.label
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun MainUiHost.optionGridImpl(items: List<OptionItem>): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    addView(optionButton(item).apply {
                        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                            dp(AirUiTokens.Space.Xxl),
                            if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                            0
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainUiHost.liveOptionGridImpl(items: List<KeyedOptionItem>): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val buttons = mutableListOf<Pair<KeyedOptionItem, TextView>>()

        fun refreshSelection(selectedKey: String) {
            buttons.forEach { (item, button) ->
                applyOptionButtonState(button, item.title, item.key == selectedKey)
            }
        }

        items.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    val button = TextView(activity).apply {
                        gravity = Gravity.CENTER
                        textSize = AirUiTokens.TextSize.Body
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV))
                        applyOptionButtonState(this, item.title, item.selected)
                        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
                        setOnClickListener {
                            item.action()
                            refreshSelection(item.key)
                            playTinyPulse(this)
                        }
                    }
                    buttons.add(item to button)
                    addView(button.apply {
                        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                            dp(AirUiTokens.Space.Xxl),
                            if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                            0
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainUiHost.optionButtonImpl(item: OptionItem): TextView {
    return TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Body
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV))
        applyOptionButtonState(this, item.title, item.selected)
        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
        setOnClickListener {
            item.action()
            playTinyPulse(this)
        }
    }
}

internal fun MainUiHost.applyOptionButtonStateImpl(button: TextView, title: String, selected: Boolean) {
    button.text = if (selected) "✓ $title" else title
    button.setTextColor(if (selected) Color.WHITE else colorText)
    button.background = GradientDrawable().apply {
        cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
        setColor(if (selected) colorAccent else colorSurfaceLight)
        setStroke(dp(AirUiTokens.Stroke.Hairline), if (selected) colorAccentLight else colorStroke)
    }
}

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
                view.setTextColor(if (isDarkColor(displayColor)) Color.WHITE else Color.rgb(28, 34, 46))
            }
        }

        fun refreshPreview(dispatch: Boolean) {
            val newColor = currentColor()
            preview.text = getString(R.string.floating_color_summary, title, FloatingLyricsStyleStore.colorSummary(newColor))
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
        setColor(withAlpha(color, 42))
        setStroke(dp(AirUiTokens.Stroke.Hairline), withAlpha(color, 190))
    }
}

internal fun isDarkColorImpl(color: Int): Boolean {
    val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
    return luminance < 150
}

internal fun withAlphaImpl(color: Int, alpha: Int): Int {
    return Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}

internal fun MainUiHost.mediaSourceCardImpl(controller: MediaController, selected: Boolean): View {
    val activity = this
    return card(activity) {
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()
            .ifBlank { getString(R.string.ui_unknown_song) }
        val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: getString(R.string.ui_unknown_artist)
        val appName = getAppName(controller.packageName)
        val state = getPlaybackStateText(controller.playbackState?.state)

        addView(label(activity, if (selected) getString(R.string.ui_connected) else getString(R.string.ui_available), if (selected) colorAccentLight else colorTextMuted).apply {
            tag = "media_source_status:${controller.packageName}"
        })
        addView(bigText(activity, appName))
        addView(normalText(activity, "$title - $artist"))
        addView(smallHint(activity, state))
        enableSoftPressFeedback(AirUiTokens.Motion.FloatingCardPressScale)
        setOnClickListener {
            uiActions.selectMediaSource(controller.packageName, this)
        }
    }
}

internal fun MainUiHost.settingGridImpl(vararg items: FloatingSettingTile): LinearLayout {
    val activity = this
    val tileItems = items.toList()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tileItems.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    addView(floatingTile(item).apply {
                        val params = LinearLayout.LayoutParams(0, dp(AirUiTokens.Layout.FloatingTileHeight), 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                            0,
                            if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainUiHost.floatingTileImpl(item: FloatingSettingTile): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Card).toFloat()
            setColor(colorCard)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.FloatingTileIconSize), dp(AirUiTokens.Layout.FloatingTileIconSize)).apply {
                setMargins(0, 0, 0, dp(AirUiTokens.Space.Xxl))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorAccent)
            }
            addView(ImageView(activity).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(AirUiTokens.Layout.StatusIconSize), dp(AirUiTokens.Layout.StatusIconSize), Gravity.CENTER)
            })
        })

        addView(TextView(activity).apply {
            text = item.title
            textSize = AirUiTokens.TextSize.Button + 1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })

        if (item.subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = item.subtitle
                textSize = AirUiTokens.TextSize.Caption
                setTextColor(colorTextMuted)
                maxLines = 1
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
                item.onSubtitleViewCreated?.invoke(this)
            })
        }

        enableSoftPressFeedback(AirUiTokens.Motion.FloatingTilePressScale)
        setOnClickListener { item.onClick(this) }
    }
}

internal fun MainUiHost.floatingFocusBubbleImpl(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: LinearLayout.() -> Unit
): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Radius.Md))
        elevation = dp(AirUiTokens.Space.Xxl).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
            setColor(colorBubble)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        layoutParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(AirUiTokens.Layout.FloatingPanelWidthInset)).coerceAtMost(dp(AirUiTokens.Layout.FloatingPanelMaxWidth)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            setMargins(dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md))
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(TextView(activity).apply {
                    text = title
                    textSize = AirUiTokens.TextSize.Title
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextStrong)
                })
                if (subtitle.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = subtitle
                        textSize = AirUiTokens.TextSize.BodySmall
                        setTextColor(colorTextMuted)
                        setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
                    })
                }
            })
            addView(TextView(activity).apply {
                text = "×"
                gravity = Gravity.CENTER
                textSize = AirUiTokens.TextSize.PageTitle - 4f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMuted)
                layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.DialogCloseSize), dp(AirUiTokens.Layout.DialogCloseSize)).apply {
                    setMargins(dp(AirUiTokens.Space.Xxl), 0, 0, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSurfaceLight)
                }
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener { onClose() }
            })
        })
        addView(spacer(activity, 8))
        content()
    }
}

internal fun MainUiHost.showFloatingSettingPanelImpl(
    title: String,
    subtitle: String,
    content: LinearLayout.() -> Unit
) {
    val activity = this
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.PageH))
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(AirUiTokens.Radius.Dialog).toFloat(), dp(AirUiTokens.Radius.Dialog).toFloat(),
                dp(AirUiTokens.Radius.Dialog).toFloat(), dp(AirUiTokens.Radius.Dialog).toFloat(),
                0f, 0f,
                0f, 0f
            )
            setColor(colorSurface)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }

        addView(TextView(activity).apply {
            text = title
            textSize = AirUiTokens.TextSize.Title
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = subtitle
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, dp(AirUiTokens.Space.Xl))
        })
        content()
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    dialog.setContentView(ScrollView(this).apply { addView(panel) })
    dialog.setOnShowListener {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(AirUiTokens.Layout.SheetDimAmount)
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    dialog.show()
}
