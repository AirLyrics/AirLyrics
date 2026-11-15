package com.andsi.airlyrics.ui.pages.floating

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.settings.store.FloatingColorSwatchStore
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.AdaptiveLabelValueLayout
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.GridColumnPolicy
import com.andsi.airlyrics.ui.components.SettingsSliderRow
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.beginFloatingPanelChange
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

/** Applied colors use their owner's callbacks; favorites have a separate shared store. */
@SuppressLint("ViewConstructor")
internal class FloatingColorPanelContent(
    private val host: MainUiHost,
    private val navigation: FloatingPanelNavigation,
    private val currentColor: () -> Int,
    private val onColorChanged: (Int) -> Unit,
    additionalControls: View? = null
) : LinearLayout(host) {
    private val pageStateKey = "panel.color"
    private var editingRgb = host.interactions.read(pageStateKey)?.getBoolean("rgb") ?: false
    private val overview = ColorPanelPage(host).apply { setTag(R.id.interaction_anchor, "floating.color.overview") }
    private val editor = ColorPanelPage(host).apply { setTag(R.id.interaction_anchor, "floating.color.editor") }
    private val ownerPanel = host.interactions.panel
    private val hexEditor = FloatingHexColorEditor(
        host, { editingRgb && host.interactions.panel == ownerPanel }, currentColor, { applyRgb(it, animateSample = true) }
    )
    private val editorSample = View(host)
    private val editorHex = hexText(AirUiTokens.TextSize.Title)
    private val customSample = View(host)
    private var sampleColor: Int = AirColorUtils.opaqueRgb(currentColor())
    private var sampleAnimator: ValueAnimator? = null
    private var sampleTargetColor: Int? = null
    private var renderedSavedState: Boolean? = null
    private val customHex = hexText(AirUiTokens.TextSize.Body)
    private val customRow: View
    private val presetSwatches = mutableListOf<Pair<Int, FloatingColorSwatch>>()
    private val savedSwatches = linkedMapOf<Int, FloatingColorSwatch>()
    private var pendingDeleteColor: Int? = null
    private val rgbRows = mutableListOf<SettingsSliderRow>()
    private var favorites = FloatingColorSwatchStore.colors(host)
    private val savedGrid = swatchGrid("floating.color.saved")
    private val savedSection: View
    private val saveButton = actionButton(host, host.getString(R.string.ui_save_color_swatch)) {
        FloatingColorSwatchStore.save(host, currentColor())
        // The overview is GONE here. Mutating it inside a window-wide Fade can draw its
        // children in the transition overlay, briefly flashing the palette over the RGB page.
        // Keep the editor stable; reconcile the saved views before revealing the overview.
        favorites = FloatingColorSwatchStore.colors(host)
        renderColor()
    }

    init {
        orientation = VERTICAL
        overview.setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
        val presets = listOf(
            R.string.ui_blue to Color.rgb(66, 165, 245),
            R.string.ui_purple to Color.rgb(126, 87, 194),
            R.string.ui_pink to Color.rgb(236, 64, 122),
            R.string.ui_cyan to Color.rgb(38, 198, 218),
            R.string.ui_green to Color.rgb(102, 187, 106),
            R.string.ui_orange to Color.rgb(255, 167, 38),
            R.string.ui_red to Color.rgb(239, 83, 80),
            R.string.ui_white to Color.WHITE
        )
        val presetsGrid = swatchGrid("floating.color.presets")
        presets.forEach { (label, color) ->
            val swatch = colorSwatch(color, host.getString(label))
            presetSwatches += color to swatch
            presetsGrid.addView(swatch)
        }
        overview.addView(centeredPalette(presetsGrid))

        val savedColumn = PaletteColumn(host).apply {
            addView(TextView(host).apply {
                setText(R.string.ui_saved_color_swatches)
                textSize = AirUiTokens.TextSize.BodySmall
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.colorTextMuted)
                setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, host.dp(AirUiTokens.Space.Sm))
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(savedGrid)
        }
        savedSection = centeredPalette(savedColumn)
        overview.addView(savedSection)

        val value = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            customSample.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(customSample, LayoutParams(host.dp(16), host.dp(16)))
            customHex.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(customHex, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = host.dp(AirUiTokens.Space.Xl)
                marginEnd = host.dp(AirUiTokens.Space.Xl)
            })
            addView(host.airIconView(R.drawable.ic_air_chevron_right, host.colorTextStrong),
                LayoutParams(host.dp(AirUiTokens.Layout.IconSize), host.dp(AirUiTokens.Layout.IconSize)))
        }
        customRow = AdaptiveLabelValueLayout(
            host,
            TextView(host).apply {
                setText(R.string.ui_custom_color)
                textSize = AirUiTokens.TextSize.Button
                setTextColor(host.colorTextStrong)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            value,
            horizontalGapPx = host.dp(AirUiTokens.Space.Xl),
            verticalGapPx = host.dp(AirUiTokens.Space.Sm),
            trailingGapPx = 0
        ).apply {
            minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
            setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, host.dp(AirUiTokens.Space.Xl))
            isFocusable = true
            setTag(R.id.interaction_anchor, "floating.color.custom")
            background = RippleDrawable(ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(host.colorAccentText, 32)), null,
                GradientDrawable().apply { setColor(Color.WHITE) })
            enableSoftPressFeedback()
            setOnClickListener { showEditor(true) }
        }
        overview.addView(customRow)
        additionalControls?.let { controls ->
            overview.addView(View(host).apply {
                setBackgroundColor(ColorUtils.setAlphaComponent(host.colorStroke, 128))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(LayoutParams.MATCH_PARENT, host.dp(AirUiTokens.Stroke.Hairline)).apply {
                topMargin = host.dp(AirUiTokens.Space.Xl)
                bottomMargin = host.dp(AirUiTokens.Space.Xl)
            })
            overview.addView(controls)
        }

        editor.addView(LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, host.dp(AirUiTokens.Space.ButtonH))
            editorSample.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            editorHex.apply {
                minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setTextColor(host.colorAccentText)
                setTag(R.id.interaction_anchor, "floating.color.hex")
                tooltipText = host.getString(R.string.ui_color_hex)
                isFocusable = true
                enableSoftPressFeedback()
                setOnClickListener { hexEditor.show() }
            }
            addView(editorSample, LayoutParams(host.dp(36), host.dp(36)))
            addView(editorHex, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = host.dp(AirUiTokens.SettingsPanel.ControlGap)
            })
        })
        listOf("R", "G", "B").forEachIndexed { channel, name ->
            val initial = channelValue(currentColor(), channel)
            val row = SettingsSliderRow(host, name, initial.toString(), compact = true)
            row.seekBar.apply {
                max = 255
                progress = initial
                setTag(R.id.interaction_anchor, "floating.color.rgb.$name")
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        row.updateValue(progress.toString())
                        if (!fromUser) return
                        val color = currentColor()
                        val channels = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
                        channels[channel] = progress
                        applyRgb(Color.rgb(channels[0], channels[1], channels[2]))
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            rgbRows += row
            editor.addView(row)
        }
        editor.addView(saveButton)
        addView(overview)
        addView(editor)
        if (!editingRgb) refreshSavedSwatches()
        applyPage()
        renderColor()
    }

    private fun hexText(size: Float) = TextView(host).apply {
        textSize = size
        setTextColor(host.colorTextStrong)
        textDirection = TEXT_DIRECTION_LTR
        fontFeatureSettings = "tnum"
    }

    private fun swatchGrid(anchor: String) = AdaptiveGridLayout(
        host, 4, 0, 0,
        columnPolicy = GridColumnPolicy.Width(
            AirUiTokens.Layout.IconTouchSize, scaleWithFont = false, preserveColumns = true
        )
    ).apply { setTag(R.id.interaction_anchor, anchor) }

    private fun centeredPalette(content: View): View = FrameLayout(host).apply {
        val column = if (content is PaletteColumn) content else PaletteColumn(host).apply { addView(content) }
        addView(column, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Xl))
    }

    private fun colorSwatch(color: Int, label: String) = FloatingColorSwatch(host, color, label).apply {
        setOnClickListener { applyRgb(color, animateSample = true) }
    }

    private fun refreshSavedSwatches() {
        favorites = FloatingColorSwatchStore.colors(host)
        savedSwatches.keys.filterNot { it in favorites }.forEach { color ->
            savedSwatches.remove(color)?.let { savedGrid.removeView(it) }
        }
        favorites.forEachIndexed { index, color ->
            val swatch = savedSwatches.getOrPut(color) {
                FloatingColorSwatch(host, color, compactColorSummary(color)).apply {
                    setOnClickListener { onSavedSwatchClick(color, this) }
                }
            }
            if (savedGrid.indexOfChild(swatch) != index) {
                if (swatch.parent === savedGrid) savedGrid.removeView(swatch)
                savedGrid.addView(swatch, index)
            }
        }
        savedSection.visibility = if (favorites.isEmpty()) GONE else VISIBLE
    }

    private fun onSavedSwatchClick(color: Int, swatch: FloatingColorSwatch) {
        if (!swatch.isEnabled) return
        when {
            pendingDeleteColor == color -> {
                beginFloatingPanelChange()
                pendingDeleteColor = null
                FloatingColorSwatchStore.remove(host, color)
                swatch.isEnabled = false
                refreshSavedSwatches()
                // Removing a favorite never changes the applied color or its alpha.
                renderColor()
            }
            color == AirColorUtils.opaqueRgb(currentColor()) -> {
                pendingDeleteColor = color
                renderSwatchState()
            }
            else -> applyRgb(color, animateSample = true)
        }
    }

    private fun clearPendingDeletion() {
        pendingDeleteColor = null
        savedSwatches.values.forEach { it.setRemovalArmed(false) }
    }

    private fun renderSwatchState() {
        val rgb = AirColorUtils.opaqueRgb(currentColor())
        presetSwatches.forEach { (color, view) -> view.isSelected = color == rgb }
        savedSwatches.forEach { (color, view) ->
            view.isSelected = color == rgb
            view.setRemovalArmed(color == pendingDeleteColor && color == rgb)
        }
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> Color.red(color)
        1 -> Color.green(color)
        else -> Color.blue(color)
    }

    private fun applyRgb(rgb: Int, animateSample: Boolean = false) {
        clearPendingDeletion()
        val current = currentColor()
        val next = AirColorUtils.withAlpha(rgb, Color.alpha(current))
        if (next != current) onColorChanged(next)
        renderColor(animateSample)
    }

    private fun colorSample(rgb: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(rgb)
        setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
    }

    private fun renderColor(animateSample: Boolean = false) {
        val rgb = AirColorUtils.opaqueRgb(currentColor())
        val code = compactColorSummary(rgb)
        editorHex.text = code
        editorHex.contentDescription = listOf(host.getString(R.string.ui_color_hex), code).joinToString(", ")
        customHex.text = code
        customRow.contentDescription = listOf(host.getString(R.string.ui_custom_color), code).joinToString(", ")
        if (sampleTargetColor != rgb) {
            sampleTargetColor = rgb
            sampleAnimator?.cancel()
            val visibleSample = if (editingRgb) editorSample else customSample
            if (animateSample && visibleSample.canAnimateFloatingControl() && sampleColor != rgb) {
                sampleAnimator = ValueAnimator.ofArgb(sampleColor, rgb).apply {
                    duration = AirUiTokens.Motion.LayoutChangeMs
                    interpolator = floatingPanelEaseOut
                    addUpdateListener { updateSamples(it.animatedValue as Int) }
                    start()
                }
            } else {
                updateSamples(rgb)
            }
        }
        renderSwatchState()
        rgbRows.forEachIndexed { channel, row -> row.seekBar.progress = channelValue(rgb, channel) }
        val saved = rgb in favorites
        if (renderedSavedState != saved) {
            renderedSavedState = saved
            saveButton.animate().cancel()
            saveButton.setText(if (saved) R.string.ui_saved else R.string.ui_save_color_swatch)
            saveButton.contentDescription = saveButton.text
            saveButton.isEnabled = !saved
            saveButton.alpha = if (saved) AirUiTokens.Button.DisabledAlpha else 1f
            host.styleAirTonalButton(saveButton, if (saved) AirTonalButtonTone.DEFAULT else AirTonalButtonTone.PRIMARY)
        }
    }

    private fun updateSamples(color: Int) {
        sampleColor = color
        listOf(editorSample, customSample).forEach { sample ->
            val shape = sample.background as? GradientDrawable
            if (shape == null) sample.background = colorSample(color) else shape.setColor(color)
        }
    }

    override fun onDetachedFromWindow() {
        clearPendingDeletion()
        sampleAnimator?.cancel()
        sampleAnimator = null
        updateSamples(AirColorUtils.opaqueRgb(currentColor()))
        super.onDetachedFromWindow()
    }

    private fun scrollParent(): ScrollView? =
        generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()

    private fun showEditor(editing: Boolean) {
        if (editingRgb == editing) return
        clearPendingDeletion()
        // Prepare hidden content before the transition captures it, so only the page enters.
        if (!editing) refreshSavedSwatches()
        beginFloatingPanelChange()
        val leavingKey = if (editingRgb) "rgbScroll" else "overviewScroll"
        host.interactions.write(pageStateKey) {
            putInt(leavingKey, scrollParent()?.scrollY ?: 0)
            putBoolean("rgb", editing)
        }
        editingRgb = editing
        applyPage()
        renderSwatchState()
        val enteringKey = if (editing) "rgbScroll" else "overviewScroll"
        val y = host.interactions.read(pageStateKey)?.getInt(enteringKey) ?: 0
        (if (editing) editor else overview).doOnLayout {
            if (editingRgb == editing) scrollParent()?.scrollTo(0, y)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (isAttachedToWindow && editingRgb) hexEditor.restoreIfNeeded() }
    }

    private fun applyPage() {
        overview.acceptsInput = !editingRgb
        editor.acceptsInput = editingRgb
        overview.visibility = if (editingRgb) GONE else VISIBLE
        editor.visibility = if (editingRgb) VISIBLE else GONE
        if (editingRgb) {
            navigation.showPage(host.getString(R.string.ui_rgb_editor_title)) { showEditor(false) }
        } else {
            navigation.showPage()
        }
    }
}

/** Disappearing transition ghosts cannot receive another tap or accessibility focus. */
@SuppressLint("ViewConstructor")
private class ColorPanelPage(host: MainUiHost) : LinearLayout(host) {
    var acceptsInput: Boolean = true
        set(value) {
            field = value
            descendantFocusability = if (value) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

    init { orientation = VERTICAL }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        acceptsInput && super.dispatchTouchEvent(event)
}

/** Color geometry does not grow with fonts; both sections keep the same four-column alignment. */
@SuppressLint("ViewConstructor")
private class PaletteColumn(private val host: MainUiHost) : LinearLayout(host) {
    init { orientation = VERTICAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = host.dp(280)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maxWidth
            else MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(maxWidth)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
