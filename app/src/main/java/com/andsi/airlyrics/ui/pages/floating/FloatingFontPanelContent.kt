package com.andsi.airlyrics.ui.pages.floating

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingFont
import com.andsi.airlyrics.core.model.FloatingFontCollection
import com.andsi.airlyrics.core.model.FloatingFontSource
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.i18n.localizedFloatingFontFamilyTitle
import com.andsi.airlyrics.i18n.localizedFloatingFontSize
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.SettingsUndoTarget
import com.andsi.airlyrics.ui.components.TonalSelectionMotion
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.beginFloatingPanelContentReplacement
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small injectable boundary keeps the real settings-entry flows testable without the network. */
internal interface FontPanelOperations {
    val changes: Flow<Long> get() = emptyFlow()
    fun fonts(): List<FloatingFont>
    fun collections(): List<FloatingFontCollection> = emptyList()
    fun removeCollection(id: String): Boolean = false
    fun selectedId(): String
    fun previewTypeface(id: String): Typeface
    fun select(id: String): Boolean
    fun remove(id: String): Boolean
    suspend fun download(id: String, onProgress: (Int) -> Unit)
}

/** List, specimen and downloads share the existing setting panel rather than opening another modal. */
@SuppressLint("ViewConstructor")
internal class FloatingFontPanelContent(
    private val host: MainUiHost,
    private val navigation: FloatingPanelNavigation,
    private val onChanged: () -> Unit,
    private val operations: FontPanelOperations = host.floatingFontOperations,
    private val edits: FloatingStylePanelEdits? = null
) : LinearLayout(host) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val confirmation = FontDeleteConfirmation()
    private val ownerPageRefs = host.floatingPageRefs
    private var focusedId = host.interactions.read(STATE_KEY)?.getString("font_id")
    private var language = host.interactions.read(STATE_KEY)?.getString("language") ?: "ja"
    private var closed = false
    private var deleting = false
    private var generation = 0
    private var downloadJob: Job? = null
    private var progressJob: Job? = null
    private var changesJob: Job? = null
    private var deleteButton: FontDeleteButton? = null
    private var applyButton: FontApplyButton? = null
    private var cancelButton: TextView? = null
    private var statusView: TextView? = null
    private var languagePreview: FloatingFontLanguagePreview? = null
    private val rowSelectionUpdates = mutableListOf<(String) -> Unit>()
    private val closeCallback: () -> Unit = { close() }

    init {
        orientation = VERTICAL
        setTag(R.id.interaction_anchor, "floating.font.manager")
        navigation.onClose = closeCallback
        renderPage(animate = false)
        edits?.bindControl(
            read = FloatingLyricsStyle::fontId,
            target = { id ->
                val name = operations.fonts().firstOrNull { it.id == id }?.let(::title) ?: id
                SettingsUndoTarget(host.getString(R.string.ui_font), name)
            }
        ) {
            { _, _ ->
                val selected = operations.selectedId()
                rowSelectionUpdates.forEach { it(selected) }
                currentFont()?.let(::renderAction)
            }
        }
    }

    internal fun showImportedFont(id: String) {
        if (closed) return
        showEntry(id)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The header delete action is outside this body; every body touch cancels its armed state.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelConfirmation()
        return super.dispatchTouchEvent(event)
    }

    private fun currentFont(): FloatingFont? = focusedId?.let { id ->
        operations.fonts().firstOrNull { it.id == id }
    }

    private fun currentCollection(): FloatingFontCollection? = focusedId?.let { id ->
        operations.collections().firstOrNull { it.id == id }
    }

    private fun title(font: FloatingFont): String =
        font.displayName ?: host.localizedFloatingFontFamilyTitle(font.family)

    private fun persistPage() {
        host.interactions.write(STATE_KEY) {
            if (focusedId == null) remove("font_id") else putString("font_id", focusedId)
            putString("language", language)
        }
    }

    internal fun showList() = showEntry(null)

    private fun showEntry(id: String?) {
        if (closed) return
        cancelDownload(announce = false)
        cancelConfirmation()
        focusedId = id
        persistPage()
        renderPage()
    }

    private fun renderPage(animate: Boolean = true) {
        if (closed) return
        cancelConfirmation()
        val font = currentFont()
        val collection = if (font == null) currentCollection() else null
        if (focusedId != null && font == null && collection == null) {
            focusedId = null
            persistPage()
        }
        if (animate) beginFloatingPanelContentReplacement()
        removeAllViews()
        applyButton = null
        cancelButton = null
        statusView = null
        languagePreview = null
        rowSelectionUpdates.clear()
        deleteButton?.dispose()
        deleteButton = null
        when {
            font != null -> renderDetail(font)
            collection != null -> renderCollection(collection)
            else -> renderList()
        }
        scrollParent()?.scrollTo(0, 0)
        if (animate && canAnimateFloatingControl()) {
            alpha = .85f
            animate().alpha(1f).setDuration(AirUiTokens.Motion.LayoutChangeMs)
                .setInterpolator(floatingPanelEaseOut).start()
        } else alpha = 1f
    }

    private fun renderList() {
        navigation.showPage()
        navigation.setHeaderAction(null)
        val selected = operations.selectedId()
        val fonts = operations.fonts()
        val collections = operations.collections().associateBy { it.id }
        val selectedFont = fonts.firstOrNull { it.id == selected }
        val seen = mutableSetOf<String>()
        fonts.forEach { font ->
            val collectionId = font.collectionId
            if (collectionId == null) {
                addFontRow(fontRow(font, selected == font.id))
            } else if (seen.add(collectionId)) {
                val collection = collections[collectionId] ?: return@forEach
                val selectedMember = selectedFont?.takeIf { it.collectionId == collectionId }
                val row = font.copy(
                    id = collection.id, displayName = collection.displayName, sizeBytes = collection.sizeBytes
                )
                addFontRow(fontRow(row, selectedMember != null, previewId = selectedMember?.id ?: font.id) { id ->
                    fonts.any { it.id == id && it.collectionId == collectionId }
                })
            }
        }
        addView(actionButton(host, host.getString(R.string.ui_import_font)) {
            if (deleting) return@actionButton
            cancelConfirmation()
            host.selectFloatingFontFile()
        }.apply {
            isEnabled = !deleting
            host.styleAirTonalButton(this)
            setTag(R.id.interaction_anchor, "floating.font.import")
        })
    }

    private fun addFontRow(view: View) {
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = host.dp(AirUiTokens.Space.Xl)
        })
    }

    private fun renderCollection(collection: FloatingFontCollection) {
        navigation.showPage(collection.displayName) { showList() }
        deleteButton = FontDeleteButton(host, ::onDeleteClick,
            R.string.ui_delete_font_collection, R.string.ui_confirm_delete_font_collection)
        navigation.setHeaderAction(deleteButton, hideReset = true)
        val selected = operations.selectedId()
        operations.fonts().filter { it.collectionId == collection.id }.forEach { font ->
            // Members are named here; their actual glyphs are loaded individually in the specimen.
            addFontRow(fontRow(font, selected == font.id, previewId = null))
        }
        statusView = TextView(host).apply {
            textSize = AirUiTokens.TextSize.BodySmall
            visibility = GONE
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            host.bindAppearance(this) { setTextColor(host.colorDanger) }
        }.also { addView(it) }
        deleteButton?.isEnabled = !deleting
    }

    private fun fontRow(
        font: FloatingFont,
        selected: Boolean,
        previewId: String? = font.id,
        represents: (String) -> Boolean = { it == font.id }
    ): View {
        val name = title(font)
        val face = previewId?.let(operations::previewTypeface) ?: Typeface.DEFAULT
        val sample = TextView(host).apply {
            text = "Aa"
            visibility = if (previewId == null) GONE else VISIBLE
            textSize = AirUiTokens.TextSize.Title
            typeface = face
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(host).apply {
            text = name
            textSize = AirUiTokens.TextSize.Button
            typeface = face
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setTag(R.id.interaction_anchor, "floating.font.name.${font.id}")
        }
        val sizeLabel = font.sizeBytes?.takeIf { it > 0L }?.let { host.localizedFloatingFontSize(it) }
        val size = sizeLabel?.let { formatted ->
            TextView(host).apply {
                text = formatted
                textSize = AirUiTokens.TextSize.Caption
                typeface = Typeface.DEFAULT
                fontFeatureSettings = "tnum"
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setTag(R.id.interaction_anchor, "floating.font.size.${font.id}")
            }
        }
        val unselectedIcon = host.airIconView(
            if (font.available) R.drawable.ic_air_chevron_right else R.drawable.ic_air_download,
            host.colorTextMuted,
        )
        val selectedIcon = host.airIconView(R.drawable.ic_air_check, host.colorTextMuted)
        val iconSlot = FrameLayout(host).apply {
            addView(unselectedIcon, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(selectedIcon, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        return LinearLayout(host).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            minimumHeight = host.dp(AirUiTokens.SettingsPanel.OptionMinHeight)
            setPaddingRelative(host.dp(AirUiTokens.Space.ButtonH), host.dp(AirUiTokens.Space.Xxl),
                host.dp(AirUiTokens.Space.ButtonH), host.dp(AirUiTokens.Space.Xxl))
            addView(sample, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = host.dp(AirUiTokens.SettingsPanel.ControlGap)
            })
            val text = if (size == null) label else FontRowTextLayout(host, label, size)
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(iconSlot, LayoutParams(host.dp(AirUiTokens.Layout.IconSize), host.dp(AirUiTokens.Layout.IconSize)).apply {
                marginStart = host.dp(AirUiTokens.Space.Xl)
            })
            isEnabled = !deleting
            isFocusable = true
            contentDescription = listOfNotNull(name, sizeLabel).joinToString(", ")
            ViewCompat.setStateDescription(this, if (selected) host.getString(R.string.ui_applied) else null)
            setTag(R.id.interaction_anchor, "floating.font.row.${font.id}")
            val selection = TonalSelectionMotion(host, this, selected) { style, amount ->
                val color = style.textColor
                sample.setTextColor(color)
                label.setTextColor(color)
                size?.setTextColor(ColorUtils.blendARGB(host.colorTextMuted, color, amount))
                val tint = ColorStateList.valueOf(color)
                unselectedIcon.imageTintList = tint
                selectedIcon.imageTintList = tint
                unselectedIcon.alpha = 1f - amount
                selectedIcon.alpha = amount
            }
            rowSelectionUpdates.add { id ->
                val checked = represents(id)
                if (isSelected != checked) {
                    selection.select(checked)
                    ViewCompat.setStateDescription(this, if (checked) host.getString(R.string.ui_applied) else null)
                }
            }
            enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
            setOnClickListener { if (!deleting) showEntry(font.id) }
        }
    }

    private fun renderDetail(font: FloatingFont) {
        navigation.showPage(title(font)) { showEntry(font.collectionId) }
        if (font.source != FloatingFontSource.SYSTEM && font.available) {
            deleteButton = FontDeleteButton(host, ::onDeleteClick)
        }
        navigation.setHeaderAction(deleteButton, hideReset = true)
        languagePreview = FloatingFontLanguagePreview(
            host, operations.previewTypeface(font.id), language,
        ) { code ->
            cancelConfirmation()
            if (language != code) {
                language = code
                persistPage()
            }
        }.also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
        statusView = TextView(host).apply {
            textSize = AirUiTokens.TextSize.BodySmall
            visibility = GONE
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            host.bindAppearance(this) { setTextColor(host.colorDanger) }
            setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Xl))
        }.also { addView(it) }
        applyButton = FontApplyButton(host) { applyOrDownload(font.id) }.also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        cancelButton = actionButton(host, host.getString(R.string.ui_cancel)) {
            cancelConfirmation()
            cancelDownload(announce = true)
        }.apply {
            host.styleAirTonalButton(this)
            visibility = GONE
            setTag(R.id.interaction_anchor, "floating.font.cancel")
        }.also { addView(it) }
        renderAction(font)
    }

    private fun renderAction(font: FloatingFont) {
        val downloading = downloadJob != null
        applyButton?.apply {
            text = when {
                downloading -> text
                operations.selectedId() == font.id -> host.getString(R.string.ui_applied)
                font.available -> host.getString(R.string.ui_apply)
                else -> host.getString(R.string.ui_font_download_apply)
            }
            isEnabled = !deleting && !downloading && operations.selectedId() != font.id
        }
        deleteButton?.isEnabled = !deleting && !downloading
        languagePreview?.setLanguageButtonsEnabled(!deleting)
        cancelButton?.visibility = if (downloading) VISIBLE else GONE
    }

    private fun selectFont(id: String): Boolean = runCatching {
        edits?.edit { operations.select(id) } ?: operations.select(id)
    }.getOrDefault(false)

    private fun applyOrDownload(id: String) {
        cancelConfirmation()
        if (closed || deleting || downloadJob != null || focusedId != id) return
        val font = currentFont() ?: return
        if (font.available) {
            if (selectFont(id)) {
                onChanged()
                showList()
            } else showFailure(R.string.ui_font_apply_failed)
            return
        }
        val token = ++generation
        val actual = AtomicInteger(0)
        val cadence = FontDownloadCadence()
        statusView?.visibility = GONE
        applyButton?.setProgress(0)
        applyButton?.text = host.getString(R.string.ui_font_downloading_progress, 0)
        // Lazy start makes the busy state observable before even an immediately completed fake download.
        downloadJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                operations.download(id) { progress -> actual.set(progress.coerceIn(0, 100)) }
                // A page replacement can precede the old view's eventual detach callback.
                if (!isActive || closed || token != generation || focusedId != id ||
                    !isAttachedToWindow || parent == null || host.floatingPageRefs !== ownerPageRefs ||
                    host.interactions.panel != com.andsi.airlyrics.ui.state.FloatingPanelId.FONT) return@launch
                if (selectFont(id)) {
                    stopProgress()
                    downloadJob = null
                    onChanged()
                    showList()
                } else showFailure(R.string.ui_font_apply_failed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && token == generation && focusedId == id) showFailure()
            } finally {
                if (token == generation) {
                    downloadJob = null
                    stopProgress()
                    if (!closed && focusedId == id) currentFont()?.let(::renderAction)
                }
            }
        }
        progressJob = scope.launch {
            while (isActive && downloadJob != null && token == generation && !closed && focusedId == id) {
                val displayed = cadence.advance(actual.get())
                applyButton?.setProgress(displayed)
                val label = host.getString(R.string.ui_font_downloading_progress, displayed)
                if (applyButton?.text != label) applyButton?.text = label
                delay(cadence.delayMs)
            }
        }
        downloadJob?.start()
        renderAction(font)
    }

    private fun showFailure(message: Int = R.string.ui_font_download_failed) {
        statusView?.setText(message)
        statusView?.visibility = VISIBLE
        applyButton?.setProgress(0)
    }

    private fun stopProgress() {
        progressJob?.cancel()
        progressJob = null
        applyButton?.setProgress(0)
    }

    private fun cancelDownload(announce: Boolean) {
        val wasDownloading = downloadJob != null
        ++generation
        downloadJob?.cancel()
        downloadJob = null
        stopProgress()
        if (announce && wasDownloading && !closed) host.showMessage(R.string.ui_font_download_cancelled)
        currentFont()?.let(::renderAction)
    }

    private fun cancelConfirmation() {
        confirmation.cancel()
        deleteButton?.setArmed(false)
    }

    private fun onDeleteClick() {
        if (closed || deleting || downloadJob != null) return
        val font = currentFont()
        val id = font?.id ?: currentCollection()?.id ?: return
        if (font != null && (font.source == FloatingFontSource.SYSTEM || !font.available)) return
        when (confirmation.click(SystemClock.elapsedRealtime())) {
            FontDeleteConfirmation.Click.ARMED -> deleteButton?.setArmed(true)
            FontDeleteConfirmation.Click.WAIT -> Unit
            FontDeleteConfirmation.Click.DELETE -> deleteTarget(id, font)
        }
    }

    private fun deleteTarget(id: String, font: FloatingFont?) {
        deleting = true
        deleteButton?.setArmed(false)
        if (font != null) renderAction(font) else deleteButton?.isEnabled = false
        scope.launch {
            // Finish a confirmed mutation and update the overlay even when the panel closes.
            withContext(NonCancellable) {
                val removed = withContext(Dispatchers.IO) {
                    runCatching {
                        if (font == null) operations.removeCollection(id) else operations.remove(id)
                    }.getOrDefault(false)
                }
                deleting = false
                if (removed) onChanged()
                if (closed) {
                    scope.cancel()
                } else {
                    if (removed && focusedId == id && font?.source != FloatingFontSource.DOWNLOAD) {
                        focusedId = font?.collectionId
                        persistPage()
                    }
                    renderPage(animate = removed)
                    if (!removed) {
                        if (focusedId == id) showFailure(R.string.ui_font_delete_failed)
                        else host.showMessage(R.string.ui_font_delete_failed)
                    }
                }
            }
        }
    }

    private fun scrollParent(): ScrollView? =
        generateSequence(parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        changesJob = scope.launch {
            operations.changes.collect {
                if (!closed && !deleting && downloadJob == null) renderPage(animate = false)
            }
        }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun close() {
        if (closed) return
        cancelConfirmation()
        cancelDownload(announce = false)
        closed = true
        languagePreview?.finishMotion()
        deleteButton?.dispose()
        changesJob?.cancel()
        if (navigation.onClose === closeCallback) navigation.onClose = null
        if (!deleting) scope.cancel()
    }

    private companion object {
        const val STATE_KEY = "panel.font"
    }
}

/** Keeps sizes at the trailing edge, with a second line when the name needs more room. */
@SuppressLint("ViewConstructor")
private class FontRowTextLayout(
    host: MainUiHost,
    private val name: TextView,
    private val size: TextView
) : LinearLayout(host) {
    private val gap = host.dp(AirUiTokens.Space.Xl)
    private val stackedGap = host.dp(AirUiTokens.Space.Xxs)
    private var stacked: Boolean? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(size, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        name.measure(unconstrained, unconstrained)
        size.measure(unconstrained, unconstrained)
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val stack = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            name.measuredWidth + gap + size.measuredWidth > available
        if (stacked != stack) {
            stacked = stack
            orientation = if (stack) VERTICAL else HORIZONTAL
            name.layoutParams = LayoutParams(
                if (stack) LayoutParams.MATCH_PARENT else 0, LayoutParams.WRAP_CONTENT,
                if (stack) 0f else 1f
            )
            size.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (stack) 0 else gap
                topMargin = if (stack) stackedGap else 0
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

@SuppressLint("ViewConstructor")
private class FontApplyButton(
    private val host: MainUiHost,
    onClick: () -> Unit
) : androidx.appcompat.widget.AppCompatTextView(host) {
    private var progress = 0
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val outline = Path()

    init {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
        isFocusable = true
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(host.dp(AirUiTokens.Space.ButtonH), host.dp(AirUiTokens.Space.ControlV),
            host.dp(AirUiTokens.Space.ButtonH), host.dp(AirUiTokens.Space.ControlV))
        host.styleAirTonalButton(this, AirTonalButtonTone.PRIMARY)
        enableSoftPressFeedback()
        setOnClickListener { onClick() }
        setTag(R.id.interaction_anchor, "floating.font.apply")
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun setProgress(value: Int) {
        val next = value.coerceIn(0, 99)
        if (progress == next) return
        progress = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (progress > 0) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            outline.reset()
            outline.addRoundRect(bounds, host.dp(AirUiTokens.Radius.Md).toFloat(),
                host.dp(AirUiTokens.Radius.Md).toFloat(), Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(outline)
            progressPaint.color = ColorUtils.setAlphaComponent(host.colorOnAccent, 42)
            canvas.drawRect(0f, 0f, width * progress / 100f, height.toFloat(), progressPaint)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }
}

@SuppressLint("ViewConstructor")
private class FontDeleteButton(
    private val host: MainUiHost,
    onClick: () -> Unit,
    private val deleteLabel: Int = R.string.ui_delete,
    private val confirmLabel: Int = R.string.ui_font_confirm_delete
) : FrameLayout(host) {
    private val trash = host.airIconView(R.drawable.ic_air_delete, host.colorDanger)
    private val check = host.airIconView(R.drawable.ic_air_check, host.colorDanger)
    private val fill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var amount = 0f
    private var animator: ValueAnimator? = null

    init {
        isFocusable = true
        contentDescription = host.getString(deleteLabel)
        tooltipText = contentDescription
        setTag(R.id.interaction_anchor, "floating.font.delete")
        val size = host.dp(AirUiTokens.Layout.DialogCloseSize)
        addView(View(host).apply { background = fill }, LayoutParams(size, size, Gravity.CENTER))
        addView(trash, LayoutParams(host.dp(AirUiTokens.Layout.IconSize), host.dp(AirUiTokens.Layout.IconSize), Gravity.CENTER))
        addView(check, LayoutParams(host.dp(AirUiTokens.Layout.IconSize), host.dp(AirUiTokens.Layout.IconSize), Gravity.CENTER))
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener { onClick() }
        host.bindAppearance(this) { render() }
    }

    fun setArmed(armed: Boolean) {
        val target = if (armed) 1f else 0f
        contentDescription = host.getString(if (armed) confirmLabel else deleteLabel)
        tooltipText = contentDescription
        animator?.cancel()
        if (canAnimateFloatingControl() && amount != target) {
            animator = ValueAnimator.ofFloat(amount, target).apply {
                duration = AirUiTokens.Motion.LayoutChangeMs
                interpolator = floatingPanelEaseOut
                addUpdateListener { amount = it.animatedValue as Float; render() }
                start()
            }
        } else {
            amount = target
            render()
        }
    }

    private fun render() {
        // Soften the theme's danger red toward the surface in light mode and ink in dark mode.
        val lightSurface = ColorUtils.calculateLuminance(host.colorSurfaceLight) > .5
        val tint = ColorUtils.blendARGB(
            host.colorDanger,
            if (lightSurface) host.colorSurfaceLight else host.colorTextStrong,
            if (lightSurface) .3f else .12f
        )
        trash.imageTintList = ColorStateList.valueOf(tint)
        check.imageTintList = ColorStateList.valueOf(tint)
        trash.alpha = 1f - amount
        check.alpha = amount
        check.scaleX = .94f + .06f * amount
        check.scaleY = .94f + .06f * amount
        fill.setColor(ColorUtils.setAlphaComponent(tint, (24 * amount).toInt()))
    }

    fun dispose() { animator?.cancel(); animator = null }
}
