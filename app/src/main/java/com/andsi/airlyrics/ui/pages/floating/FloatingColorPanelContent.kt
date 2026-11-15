package com.andsi.airlyrics.ui.pages.floating

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
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
        host, { editingRgb && host.interactions.panel == ownerPanel }, currentColor, ::applyRgb
    )
    private val editorSample = View(host)
    private val editorHex = hexText(AirUiTokens.TextSize.Title)
    private val customSample = View(host)
    private val customHex = hexText(AirUiTokens.TextSize.Body)
    private val customRow: View
    private val presetSwatches = mutableListOf<Pair<Int, View>>()
    private val savedSwatches = mutableListOf<Pair<Int, View>>()
    private val rgbRows = mutableListOf<SettingsSliderRow>()
    private var favorites = FloatingColorSwatchStore.colors(host)
    private val savedGrid = swatchGrid("floating.color.saved")
    private val savedSection: View
    private val saveButton = actionButton(host, host.getString(R.string.ui_save_color_swatch)) {
        beginFloatingPanelChange()
        FloatingColorSwatchStore.save(host, currentColor())
        refreshSavedSwatches()
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
        refreshSavedSwatches()
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
        setOnClickListener { applyRgb(color) }
    }

    private fun refreshSavedSwatches() {
        val updated = FloatingColorSwatchStore.colors(host)
        savedSection.visibility = if (updated.isEmpty()) GONE else VISIBLE
        if (updated == favorites && savedGrid.childCount == updated.size) return
        favorites = updated
        savedGrid.removeAllViews()
        savedSwatches.clear()
        favorites.forEach { color ->
            val view = colorSwatch(color, compactColorSummary(color))
            savedSwatches += color to view
            savedGrid.addView(view)
        }
        savedSection.visibility = if (favorites.isEmpty()) GONE else VISIBLE
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> Color.red(color)
        1 -> Color.green(color)
        else -> Color.blue(color)
    }

    private fun applyRgb(rgb: Int) {
        onColorChanged(AirColorUtils.withAlpha(rgb, Color.alpha(currentColor())))
        renderColor()
    }

    private fun colorSample(rgb: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(rgb)
        setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
    }

    private fun renderColor() {
        val rgb = AirColorUtils.opaqueRgb(currentColor())
        val code = compactColorSummary(rgb)
        editorHex.text = code
        editorHex.contentDescription = listOf(host.getString(R.string.ui_color_hex), code).joinToString(", ")
        customHex.text = code
        customRow.contentDescription = listOf(host.getString(R.string.ui_custom_color), code).joinToString(", ")
        editorSample.background = colorSample(rgb)
        customSample.background = colorSample(rgb)
        (presetSwatches + savedSwatches).forEach { (swatchColor, view) -> view.isSelected = swatchColor == rgb }
        rgbRows.forEachIndexed { channel, row -> row.seekBar.progress = channelValue(rgb, channel) }
        val saved = rgb in favorites
        saveButton.setText(if (saved) R.string.ui_saved else R.string.ui_save_color_swatch)
        saveButton.contentDescription = saveButton.text
        saveButton.isEnabled = !saved
        saveButton.alpha = if (saved) AirUiTokens.Button.DisabledAlpha else 1f
        host.styleAirTonalButton(saveButton, if (saved) AirTonalButtonTone.DEFAULT else AirTonalButtonTone.PRIMARY)
    }

    private fun scrollParent(): ScrollView? =
        generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()

    private fun showEditor(editing: Boolean) {
        if (editingRgb == editing) return
        beginFloatingPanelChange()
        val leavingKey = if (editingRgb) "rgbScroll" else "overviewScroll"
        host.interactions.write(pageStateKey) {
            putInt(leavingKey, scrollParent()?.scrollY ?: 0)
            putBoolean("rgb", editing)
        }
        editingRgb = editing
        applyPage()
        val enteringKey = if (editing) "rgbScroll" else "overviewScroll"
        val y = host.interactions.read(pageStateKey)?.getInt(enteringKey) ?: 0
        (if (editing) editor else overview).doOnLayout { scrollParent()?.scrollTo(0, y) }
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

@SuppressLint("ViewConstructor")
private class FloatingColorSwatch(
    private val host: MainUiHost,
    private val color: Int,
    label: String
) : View(host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selectionAmount = 0f
    private var selectionAnimator: ValueAnimator? = null
    private val check = requireNotNull(AppCompatResources.getDrawable(host, R.drawable.ic_air_check)).mutate().apply {
        setTint(if (ColorUtils.calculateContrast(Color.BLACK, color) >=
            ColorUtils.calculateContrast(Color.WHITE, color)) Color.BLACK else Color.WHITE)
    }

    init {
        minimumWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        contentDescription = label
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        accessibilityDelegate = object : AccessibilityDelegate() {
            @Suppress("DEPRECATION")
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        }
    }

    override fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        super.setSelected(selected)
        selectionAnimator?.cancel()
        val target = if (selected) 1f else 0f
        if (!isAttachedToWindow || !isShown || !ValueAnimator.areAnimatorsEnabled() || (isFocused && !isInTouchMode)) {
            selectionAmount = target
            invalidate()
            return
        }
        selectionAnimator = ValueAnimator.ofFloat(selectionAmount, target).apply {
            duration = AirUiTokens.Motion.PulseDownMs
            interpolator = floatingPanelEaseOut
            addUpdateListener { selectionAmount = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        selectionAmount = if (isSelected) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, host.dp(18).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(AirUiTokens.Stroke.Hairline).toFloat()
        paint.color = ColorUtils.blendARGB(host.colorStroke, host.colorTextStrong, 0.5f)
        canvas.drawCircle(cx, cy, host.dp(18).toFloat(), paint)
        val ringAlpha = if (isFocused) 1f else selectionAmount
        if (ringAlpha > 0f) {
            paint.color = ColorUtils.setAlphaComponent(host.colorAccentText, (255 * ringAlpha).toInt())
            canvas.drawCircle(cx, cy, host.dp(22).toFloat(), paint)
        }
        if (selectionAmount > 0f) {
            check.alpha = (255 * selectionAmount).toInt()
            val half = host.dp(11)
            check.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            check.draw(canvas)
        }
    }
}
