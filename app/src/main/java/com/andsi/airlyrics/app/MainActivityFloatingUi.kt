package com.andsi.airlyrics.app

import android.graphics.Color
import android.app.Dialog
import android.widget.FrameLayout
import android.widget.ScrollView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingStatusPreviewCard
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
import com.andsi.airlyrics.i18n.tr

internal fun MainActivity.optionGrid(items: List<OptionItem>): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(2).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    addView(optionButton(item).apply {
                        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(6),
                            dp(10),
                            if (index == rowItems.lastIndex) 0 else dp(6),
                            0
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(6), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainActivity.liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val buttons = mutableListOf<Pair<KeyedOptionItem, TextView>>()

        fun refreshSelection(selectedKey: String) {
            buttons.forEach { (item, button) ->
                applyOptionButtonState(button, item.title, item.key == selectedKey)
            }
        }

        items.chunked(2).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    val button = TextView(activity).apply {
                        gravity = Gravity.CENTER
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        applyOptionButtonState(this, item.title, item.selected)
                        enableSoftPressFeedback(0.96f)
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
                            if (index == 0) 0 else dp(6),
                            dp(10),
                            if (index == rowItems.lastIndex) 0 else dp(6),
                            0
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(6), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainActivity.optionButton(item: OptionItem): TextView {
    val activity = this
    return TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(11), dp(12), dp(11))
        applyOptionButtonState(this, item.title, item.selected)
        enableSoftPressFeedback(0.96f)
        setOnClickListener {
            item.action()
            playTinyPulse(this)
        }
    }
}

internal fun MainActivity.applyOptionButtonState(button: TextView, title: String, selected: Boolean) {
    val activity = this
    button.text = if (selected) "✓ ${localizeText(title)}" else localizeText(title)
    button.setTextColor(if (selected) Color.WHITE else colorText)
    button.background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(if (selected) colorAccent else colorSurfaceLight)
        setStroke(dp(1), if (selected) colorAccentLight else colorStroke)
    }
}

internal fun MainActivity.sliderRow(
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
        setPadding(0, dp(8), 0, dp(4))

        val valueText = TextView(activity).apply {
            text = "${localizeText(title)}: $safeValue$suffix"
            textSize = 14f
            setTextColor(colorText)
            setPadding(0, 0, 0, dp(6))
        }
        addView(valueText)

        addView(SeekBar(activity).apply {
            this.max = max - min
            progress = safeValue - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val newValue = min + progress
                    valueText.text = "${localizeText(title)}: $newValue$suffix"
                    if (fromUser) onChanged(newValue)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
    }
}

internal fun MainActivity.colorControl(
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
        "蓝" to Color.rgb(66, 165, 245),
        "紫" to Color.rgb(126, 87, 194),
        "粉" to Color.rgb(236, 64, 122),
        "青" to Color.rgb(38, 198, 218),
        "绿" to Color.rgb(102, 187, 106),
        "橙" to Color.rgb(255, 167, 38),
        "红" to Color.rgb(239, 83, 80),
        "白" to Color.WHITE
    )

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)

        val preview = TextView(activity).apply {
            textSize = 14f
            setTextColor(colorText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        addView(preview)

        val swatchViews = mutableListOf<Pair<Int?, TextView>>()
        val swatchGrid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(swatchGrid)

        val fineTuneButton = actionButton(activity, "展开 RGB 细调") { }
        val rgbPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
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
                cornerRadius = dp(16).toFloat()
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
            preview.text = "${localizeText(title)}: ${localizeText(FloatingLyricsStyleStore.colorSummary(newColor))}"
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
                setPadding(0, dp(8), 0, dp(4))
            }
            val valueText = TextView(activity).apply {
                text = "${localizeText(sliderTitle)}: $initialValue"
                textSize = 14f
                setTextColor(colorText)
                setPadding(0, 0, 0, dp(6))
            }
            row.addView(valueText)
            val seekBar = SeekBar(activity).apply {
                max = 255
                progress = initialValue.coerceIn(0, 255)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueText.text = "${localizeText(sliderTitle)}: $progress"
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
        val alphaSlider = colorSliderRow(tr("不透明度", "Opacity").toString(), alpha) { alpha = it }

        fun setSlider(pair: Pair<SeekBar, TextView>, titleText: String, value: Int) {
            pair.first.progress = value.coerceIn(0, 255)
            pair.second.text = "${localizeText(titleText)}: $value"
        }

        fun syncSliders() {
            setSlider(redSlider, "R", red)
            setSlider(greenSlider, "G", green)
            setSlider(blueSlider, "B", blue)
            setSlider(alphaSlider, tr("不透明度", "Opacity").toString(), alpha)
        }

        fun makeSwatch(label: String, presetColor: Int?, onClick: () -> Unit): TextView {
            return TextView(activity).apply {
                text = localizeText(label)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, dp(6), 0)
                enableSoftPressFeedback(0.9f)
                setOnClickListener {
                    onClick()
                    playTinyPulse(this)
                }
                swatchViews.add(presetColor to this)
            }
        }

        val swatches = standardColors.map { (label, swatchColor) ->
            Pair(label, swatchColor as Int?)
        } + listOf("自定义" to null)

        swatches.chunked(3).forEach { rowItems ->
            swatchGrid.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, (label, presetColor) ->
                    val button = makeSwatch(label, presetColor) {
                        if (presetColor == null) {
                            rgbExpanded = true
                            rgbPanel.visibility = View.VISIBLE
                            fineTuneButton.text = tr("收起 RGB 细调", "Hide RGB")
                        } else {
                            red = Color.red(presetColor)
                            green = Color.green(presetColor)
                            blue = Color.blue(presetColor)
                            syncSliders()
                            refreshPreview(dispatch = true)
                        }
                    }
                    addView(button.apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                            setMargins(
                                if (index == 0) 0 else dp(5),
                                dp(8),
                                if (index == rowItems.lastIndex) 0 else dp(5),
                                0
                            )
                        }
                    })
                }
                repeat(3 - rowItems.size) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(5), 0, 0, 0)
                        }
                    })
                }
            })
        }

        fineTuneButton.setOnClickListener {
            rgbExpanded = !rgbExpanded
            rgbPanel.visibility = if (rgbExpanded) View.VISIBLE else View.GONE
            fineTuneButton.text = if (rgbExpanded) tr("收起 RGB 细调", "Hide RGB") else tr("展开 RGB 细调", "RGB tune")
            playTinyPulse(fineTuneButton)
        }

        syncSliders()
        refreshPreview(dispatch = false)
    }
}

internal fun MainActivity.colorPreviewBackground(color: Int): GradientDrawable {
    val activity = this
    return GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(withAlpha(color, 42))
        setStroke(dp(1), withAlpha(color, 190))
    }
}

internal fun MainActivity.isDarkColor(color: Int): Boolean {
    val activity = this
    val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
    return luminance < 150
}

internal fun MainActivity.withAlpha(color: Int, alpha: Int): Int {
    val activity = this
    return Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}

internal fun MainActivity.mediaSourceCard(controller: MediaController, selected: Boolean): View {
    val activity = this
    return card(this) {
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()
            .ifBlank { "未知歌曲" }
        val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "未知艺术家"
        val appName = getAppName(controller.packageName)
        val state = getPlaybackStateText(controller.playbackState?.state)

        addView(label(activity, if (selected) "已连接" else "可选择", if (selected) colorAccentLight else colorTextMuted).apply {
            tag = "media_source_status:${controller.packageName}"
        })
        addView(bigText(activity, appName))
        addView(normalText(activity, "$title - $artist"))
        addView(smallHint(activity, state))
        enableSoftPressFeedback(0.985f)
        setOnClickListener {
            uiActions.selectMediaSource(controller.packageName, this)
        }
    }
}

internal fun MainActivity.settingGrid(vararg items: FloatingSettingTile): LinearLayout {
    val activity = this
    val tileItems = items.toList()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tileItems.chunked(2).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    addView(floatingTile(item).apply {
                        val params = LinearLayout.LayoutParams(0, dp(112), 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(6),
                            0,
                            if (index == rowItems.lastIndex) 0 else dp(6),
                            dp(12)
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(6), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainActivity.floatingTile(item: FloatingSettingTile): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        tag = "floating_tile:${item.title}"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(colorCard)
            setStroke(dp(1), colorStroke)
        }

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                setMargins(0, 0, 0, dp(10))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorAccent)
            }
            addView(ImageView(activity).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
        })

        addView(TextView(activity).apply {
            text = localizeText(item.title)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })

        if (item.subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                tag = "floating_tile_subtitle:${item.title}"
                text = localizeText(item.subtitle)
                textSize = 12f
                setTextColor(colorTextMuted)
                maxLines = 1
                setPadding(0, dp(4), 0, 0)
            })
        }

        enableSoftPressFeedback(0.975f)
        setOnClickListener { item.onClick(this) }
    }
}

internal fun MainActivity.floatingFocusBubble(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: LinearLayout.() -> Unit
): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(18))
        elevation = dp(10).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(colorBubble)
            setStroke(dp(1), colorAccentSoft)
        }
        layoutParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(72)).coerceAtMost(dp(360)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            setMargins(dp(18), dp(18), dp(18), dp(18))
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(TextView(activity).apply {
                    text = localizeText(title)
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextStrong)
                })
                if (subtitle.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = localizeText(subtitle)
                        textSize = 13f
                        setTextColor(colorTextMuted)
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            })
            addView(TextView(activity).apply {
                text = "×"
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextMuted)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    setMargins(dp(10), 0, 0, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSurfaceLight)
                }
                enableSoftPressFeedback(0.9f)
                setOnClickListener { onClose() }
            })
        })
        addView(spacer(activity, 8))
        content()
    }
}

internal fun MainActivity.showFloatingSettingPanel(
    title: String,
    subtitle: String,
    content: LinearLayout.() -> Unit
) {
    val activity = this
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(20))
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(28).toFloat(), dp(28).toFloat(),
                dp(28).toFloat(), dp(28).toFloat(),
                0f, 0f,
                0f, 0f
            )
            setColor(colorSurface)
            setStroke(dp(1), colorStroke)
        }

        addView(TextView(activity).apply {
            text = localizeText(title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = localizeText(subtitle)
            textSize = 13f
            setTextColor(colorTextMuted)
            setPadding(0, dp(4), 0, dp(8))
        })
        content()
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    dialog.setContentView(ScrollView(this).apply { addView(panel) })
    dialog.setOnShowListener {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.08f)
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    dialog.show()
}

