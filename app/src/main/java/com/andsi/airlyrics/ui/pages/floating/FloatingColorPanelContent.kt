package com.andsi.airlyrics.ui.pages.floating

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.AdaptiveLabelValueLayout
import com.andsi.airlyrics.ui.components.GridColumnPolicy
import com.andsi.airlyrics.ui.components.SettingsSliderRow
import com.andsi.airlyrics.ui.components.SettingsUndoTarget
import com.andsi.airlyrics.ui.components.beginFloatingPanelChange
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

@SuppressLint("ViewConstructor")
internal class FloatingColorPanelContent(
    private val host: MainUiHost,
    private val navigation: FloatingPanelNavigation,
    private val edits: FloatingStylePanelEdits,
    @param:StringRes private val colorLabel: Int,
    private val readColor: (FloatingLyricsStyle) -> Int,
    private val withColor: (FloatingLyricsStyle, Int) -> FloatingLyricsStyle,
    private val onColorChanged: (Int) -> Unit,
    additionalControls: View? = null,
    leadingControls: View? = null
) : LinearLayout(host) {
    private val pageStateKey = "panel.color"
    private var editingRgb = host.interactions.read(pageStateKey)?.getBoolean("rgb") ?: false
    private val overview = ColorPanelPage(host).apply { setTag(R.id.interaction_anchor, "floating.color.overview") }
    private val editor = ColorPanelPage(host).apply { setTag(R.id.interaction_anchor, "floating.color.editor") }
    private val ownerPanel = host.interactions.panel
    private val hexEditor = FloatingHexColorEditor(
        host, { editingRgb && host.interactions.panel == ownerPanel }, ::currentColor, { applyRgb(it, animateSample = true) }
    )
    private val editorSample = FloatingColorSample(host)
    private val editorHex = hexText(AirUiTokens.TextSize.Title)
    private val customSample = FloatingColorSample(host).apply {
        setTag(R.id.interaction_anchor, "floating.color.custom.sample")
    }
    private var sampleAnimator: ValueAnimator? = null
    private var sampleTargetColor: Int? = null
    private var draftRgb: Int? = null
    private val customHex = hexText(AirUiTokens.TextSize.Body)
    private val customRow: View
    private val presetSwatches = mutableListOf<Pair<Int, FloatingColorSwatch>>()
    private val savedSwatches = linkedMapOf<Int, FloatingColorSwatch>()
    private var pendingDeleteColor: Int? = null
    private val rgbRows = mutableListOf<SettingsSliderRow>()
    private var favorites = host.floatingColorSwatches()
    private val savedGrid = swatchGrid("floating.color.saved")
    private val savedSection: View
    private var saveState = newSaveState()
    private var favoritesJob: Job? = null
    private val saveButton = FloatingColorSaveButton(host) { saveCurrentColor() }

    private fun currentColor(): Int = readColor(host.floatingStyle())

    private fun addOverviewDivider() {
        overview.addView(View(host).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(host.colorStroke, 128))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, host.dp(AirUiTokens.Stroke.Hairline)).apply {
            topMargin = host.dp(AirUiTokens.Space.Xl)
            bottomMargin = host.dp(AirUiTokens.Space.Xl)
        })
    }

    private fun newSaveState(): FloatingColorSaveState {
        val rgb = AirColorUtils.opaqueRgb(currentColor())
        return FloatingColorSaveState(rgb, rgb in favorites)
    }

    private fun saveCurrentColor() {
        val session = saveState
        val request = session.begin() ?: return
        renderColor()
        host.runOnAppIo {
            val result = runCatching { host.saveFloatingColorSwatch(request.color) }
            host.runOnMainThread {
                if (saveState !== session || session.isClosed) return@runOnMainThread
                favorites = host.floatingColorSwatches()
                val rgb = AirColorUtils.opaqueRgb(currentColor())
                session.edit(rgb, rgb in favorites)
                if (!session.complete(request, result.isSuccess)) return@runOnMainThread
                if (!editingRgb) refreshSavedSwatches()
                renderColor()
                if (result.isFailure && host.interactions.panel == ownerPanel) {
                    host.showMessage(R.string.ui_save_failed)
                }
            }
        }
    }

    init {
        orientation = VERTICAL
        overview.setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
        leadingControls?.let { controls ->
            overview.addView(controls)
            addOverviewDivider()
        }
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
            addOverviewDivider()
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
            row.seekBar.setTag(R.id.interaction_anchor, "floating.color.rgb.$name")
            row.bindRange(
                initial, min = 0, max = 255, format = Int::toString,
                onPreview = { value ->
                    val color = replaceChannel(currentColor(), channel, value)
                    draftRgb = AirColorUtils.opaqueRgb(color)
                    edits.preview { withColor(it, color) }
                    renderColor()
                },
                onCommit = { value -> applyRgb(replaceChannel(currentColor(), channel, value)) },
                onCancel = {
                    draftRgb = null
                    edits.preview { it }
                    renderColor()
                }
            )
            rgbRows += row
            editor.addView(row)
        }
        editor.addView(saveButton)
        addView(overview)
        addView(editor)
        if (!editingRgb) refreshSavedSwatches()
        applyPage()
        renderColor()
        edits.bindControl(
            read = { AirColorUtils.opaqueRgb(readColor(it)) },
            target = { SettingsUndoTarget(host.getString(colorLabel), compactColorSummary(it), color = it) },
            finishMotion = ::finishColorMotion
        ) {
            val previousSample = sampleTargetColor
            val applyToChannels = rgbRows.map(SettingsSliderRow::captureRestore)
            val render: (Int, Boolean) -> Unit = { rgb, isUndo ->
                pendingDeleteColor = null
                draftRgb = null
                renderColor(previousSample, if (isUndo) ColorChange.UNDO else ColorChange.RESET)
                applyToChannels.forEachIndexed { channel, apply -> apply(channelValue(rgb, channel)) }
            }
            render
        }
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
        val column = content as? PaletteColumn ?: PaletteColumn(host).apply { addView(content) }
        addView(column, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Xl))
    }

    private fun colorSwatch(color: Int, label: String) = FloatingColorSwatch(host, color, label).apply {
        setOnClickListener { applyRgb(color, animateSample = true) }
    }

    private fun refreshSavedSwatches() {
        favorites = host.floatingColorSwatches()
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
                pendingDeleteColor = null
                swatch.isEnabled = false
                swatch.setRemovalArmed(false)
                val session = saveState
                // Mutations serialize with confirmed saves. Never wait on that disk lock on the UI.
                host.runOnAppIo {
                    host.removeFloatingColorSwatch(color)
                    host.runOnMainThread {
                        if (saveState !== session || session.isClosed) return@runOnMainThread
                        beginFloatingPanelChange()
                        refreshSavedSwatches()
                        renderColor()
                    }
                }
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

    private fun renderSwatchState(
        rgb: Int = AirColorUtils.opaqueRgb(currentColor()),
        returning: Boolean = false
    ) {
        presetSwatches.forEach { (color, view) -> view.showSelection(color == rgb, animate = true, returning = returning) }
        savedSwatches.forEach { (color, view) ->
            view.showSelection(color == rgb, animate = true, returning = returning)
            view.setRemovalArmed(color == pendingDeleteColor && color == rgb)
        }
    }

    private fun replaceChannel(color: Int, channel: Int, value: Int): Int {
        val shift = (2 - channel) * 8
        return (color and (0xFF shl shift).inv()) or (value shl shift)
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> Color.red(color)
        1 -> Color.green(color)
        else -> Color.blue(color)
    }

    private fun applyRgb(rgb: Int, animateSample: Boolean = false) {
        clearPendingDeletion()
        val hadDraft = draftRgb != null
        draftRgb = null
        val current = currentColor()
        val next = AirColorUtils.withAlpha(rgb, Color.alpha(current))
        val previousSample = if (next != current) {
            edits.edit {
                val displayed = sampleTargetColor.takeIf { animateSample }
                onColorChanged(next)
                displayed
            }
        } else {
            if (hadDraft) edits.preview { it }
            null
        }
        renderColor(previousSample)
    }

    private fun renderColor(
        previousSample: Int? = null,
        change: ColorChange = ColorChange.SELECTION
    ) {
        val rgb = draftRgb ?: AirColorUtils.opaqueRgb(currentColor())
        val code = compactColorSummary(rgb)
        editorHex.text = code
        editorHex.contentDescription = listOf(host.getString(R.string.ui_color_hex), code).joinToString(", ")
        customHex.text = code
        customRow.contentDescription = listOf(host.getString(R.string.ui_custom_color), code).joinToString(", ")
        val colorChanged = sampleTargetColor != rgb
        if (colorChanged || previousSample != null) renderSamples(rgb, previousSample, change)
        renderSwatchState(rgb, returning = change == ColorChange.UNDO)
        if (draftRgb != null) return
        if (colorChanged && change == ColorChange.SELECTION) rgbRows.forEachIndexed { channel, row ->
            row.showRangeValue(channelValue(rgb, channel))
        }
        saveState.edit(rgb, rgb in favorites)
        saveState.reconcile(rgb in favorites)
        saveButton.render(saveState.status, saveState.canSave)
    }

    private fun renderSamples(rgb: Int, previous: Int?, change: ColorChange) {
        finishSampleMotion()
        sampleTargetColor = rgb
        editorSample.setColor(rgb)
        customSample.setColor(rgb)
        val visibleSample = if (editingRgb) editorSample else customSample
        if (previous == null || previous == rgb || !visibleSample.canAnimateFloatingControl()) return

        visibleSample.showOutgoingColor(previous)
        val animation = ValueAnimator.ofFloat(0f, change.offset).apply {
            duration = AirUiTokens.Motion.ColorSampleSlideMs
            interpolator = floatingPanelEaseOut
            addUpdateListener { visibleSample.showOutgoingColor(previous, it.animatedValue as Float) }
            doOnEnd {
                if (sampleAnimator === it) {
                    sampleAnimator = null
                    visibleSample.showOutgoingColor(null)
                }
            }
        }
        sampleAnimator = animation
        animation.start()
    }

    private enum class ColorChange(val offset: Float) {
        SELECTION(1f), RESET(-1f), UNDO(-1f)
    }

    private fun finishSampleMotion() {
        val running = sampleAnimator ?: return
        sampleAnimator = null
        running.cancel()
        editorSample.showOutgoingColor(null)
        customSample.showOutgoingColor(null)
    }

    private fun finishColorMotion() {
        finishSampleMotion()
        presetSwatches.forEach { (_, view) -> view.finishAnimation() }
        savedSwatches.values.forEach { it.finishAnimation() }
        rgbRows.forEach { it.finishRangeAnimation() }
    }

    override fun onDetachedFromWindow() {
        finishColorMotion()
        saveState.close()
        favoritesJob?.cancel()
        favoritesJob = null
        clearPendingDeletion()
        super.onDetachedFromWindow()
    }

    private fun scrollParent(): ScrollView? =
        generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()

    private fun showEditor(editing: Boolean) {
        if (editingRgb == editing) return
        finishColorMotion()
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
        favorites = host.floatingColorSwatches()
        if (saveState.isClosed) saveState = newSaveState()
        favoritesJob?.cancel()
        favoritesJob = host.activity.lifecycleScope.launch {
            host.activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                host.floatingColorSwatchChanges.collect {
                    val confirmedColors = host.floatingColorSwatches()
                    val changed = favorites != confirmedColors
                    favorites = confirmedColors
                    if (!editingRgb) {
                        if (changed) beginFloatingPanelChange()
                        refreshSavedSwatches()
                    }
                    renderColor()
                }
            }
        }
        renderColor()
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

@SuppressLint("ViewConstructor")
private class FloatingColorSample(host: MainUiHost) : View(host) {
    private val strokeWidth = host.dp(AirUiTokens.Stroke.Hairline)
    private val fill = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setStroke(strokeWidth, host.colorStroke)
    }
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@FloatingColorSample.strokeWidth.toFloat()
    }
    private var radius = 0f
    private var outgoingColor: Int? = null
    private var edgeColor: Int? = null
    private var offsetFraction = 0f

    init {
        background = fill
    }

    fun setColor(color: Int) {
        fill.setColor(color)
    }

    fun showOutgoingColor(color: Int?, fraction: Float = 0f) {
        if (outgoingColor == color && offsetFraction == fraction) return
        if (outgoingColor != color) {
            outgoingColor = color
            edgeColor = color?.let(::wipeEdgeColor)
        }
        offsetFraction = fraction
        invalidate()
    }

    private fun wipeEdgeColor(previous: Int): Int? {
        val next = fill.color?.defaultColor ?: return null
        if (ColorUtils.calculateContrast(previous, next) >= MIN_WIPE_CONTRAST) return null
        val midpoint = ColorUtils.blendARGB(previous, next, .5f)
        val ink = if (ColorUtils.calculateContrast(Color.BLACK, midpoint) >=
            ColorUtils.calculateContrast(Color.WHITE, midpoint)) Color.BLACK else Color.WHITE
        return ColorUtils.setAlphaComponent(ink, WIPE_EDGE_ALPHA)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = (minOf(w, h) / 2f - strokeWidth).coerceAtLeast(0f)
        clip.reset()
        clip.addCircle(w / 2f, h / 2f, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = outgoingColor ?: return
        val cx = width / 2f + width * offsetFraction
        val cy = height / 2f
        canvas.withClip(clip) {
            drawCircle(cx, cy, radius, paint)
            val edge = edgeColor
            if (edge != null && offsetFraction != 0f) {
                edgePaint.color = edge
                val startAngle = if (offsetFraction > 0f) 90f else -90f
                drawArc(cx - radius, cy - radius, cx + radius, cy + radius, startAngle, 180f, false, edgePaint)
            }
        }
    }

    private companion object {
        const val MIN_WIPE_CONTRAST = 1.25
        const val WIPE_EDGE_ALPHA = 153
    }
}

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
