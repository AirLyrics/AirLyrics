package com.andsi.airlyrics.app.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.interaction.EditableLrcDocument
import com.andsi.airlyrics.app.interaction.EditorSession
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.displayText
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.airTonalButtonStyle
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.theme.colorBackground
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** The plain LRC editor keeps its original text in the session until the sheet's Save action. */
@Suppress("DEPRECATION")
internal fun MainActivityUiHost.showLocalLyricsEditorSheet(session: EditorSession): EditorDialogBinding {
    val document = EditableLrcDocument.from(session.text)
    var closeLineEditor: () -> Boolean = { false }
    val dialog = object : BottomSheetDialog(this, R.style.ThemeOverlay_AirLyrics_LyricsSources) {
        override fun cancel() {
            if (!closeLineEditor()) super.cancel()
        }
    }
    val sheet = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        background = GradientDrawable().apply {
            setColor(colorCard)
            cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
        }
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(20))
    }
    sheet.addView(content, FrameLayout.LayoutParams(-1, -2))

    content.addView(View(this).apply {
        background = roundedFill(colorStroke, dp(2))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(16)
    })

    val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    header.addView(TextView(this).apply {
        text = session.item.displayTitle
        textSize = AirUiTokens.TextSize.PageTitle
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextStrong)
        maxLines = 2
    }, LinearLayout.LayoutParams(0, -2, 1f))
    val buttons = mutableListOf<View>()
    if (session.item.canDelete) {
        val delete = airIconView(
            R.drawable.ic_air_delete,
            colorDanger,
            getString(R.string.ui_delete_saved_lyrics_action, session.item.displayTitle)
        ).apply {
            setOnClickListener {
                confirmOperation(
                    action = ConfirmationAction.DeleteEditor(session.id),
                    title = getString(R.string.ui_delete_saved_lyrics_confirm, session.item.displayTitle),
                    message = getString(R.string.ui_delete_all_saved_lyrics_message),
                    positiveText = getString(R.string.ui_delete)
                )
            }
        }
        buttons += delete
        header.addView(delete, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
    }
    content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    val list = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@showLocalLyricsEditorSheet)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipToPadding = false
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
    }
    val overlay = FrameLayout(this).apply {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
    }
    lateinit var lineEditor: FloatingLineEditor
    val rows = LrcRowsAdapter(this, document) { row, anchor, insert ->
        lineEditor.open(row, anchor, insert)
    }
    list.adapter = rows
    val maxListHeight = (resources.displayMetrics.heightPixels * 0.84f).toInt() - dp(220)
    var imeInset = 0
    fun listHeight(): Int {
        val preferred = (rows.itemCount * dp(64)).coerceAtLeast(dp(86))
            .coerceAtMost(maxListHeight.coerceAtLeast(dp(86)))
        val available = resources.displayMetrics.heightPixels - imeInset - dp(220)
        return preferred.coerceAtMost(available.coerceAtLeast(dp(76)))
    }
    fun updateListHeight() {
        val height = listHeight()
        if (list.layoutParams.height != height) {
            list.layoutParams = list.layoutParams.apply { this.height = height }
        }
    }
    content.addView(list, LinearLayout.LayoutParams(-1, listHeight()))

    session.access?.let { access ->
        content.addView(TextView(this).apply {
            text = access.attribution.displayText()
            textSize = AirUiTokens.TextSize.Tiny
            setTextColor(colorTextMuted)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = getString(
                R.string.ui_spicy_expiry,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(access.expiresAtMs))
            )
            textSize = AirUiTokens.TextSize.Caption
            setTextColor(colorTextMuted)
        })
    }

    val footer = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    val cancel = sheetButton(getString(R.string.ui_cancel), AirTonalButtonTone.DEFAULT) {
        editorSession.cancel()
        dialog.dismiss()
    }
    val save = sheetButton(getString(R.string.ui_save_changes), AirTonalButtonTone.SELECTED) {
        editorSession.save()
    }
    buttons += cancel
    buttons += save
    footer.addView(cancel)
    footer.addView(save, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    content.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    sheet.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    lineEditor = FloatingLineEditor(this, sheet, overlay, document, rows) {
        val text = document.serialize()
        editorSession.edit(text, 0, 0, list.computeVerticalScrollOffset())
        updateListHeight()
    }
    closeLineEditor = lineEditor::close
    list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy == 0) return
            val current = editorSession.state.value ?: return
            editorSession.edit(
                current.text,
                current.selectionStart,
                current.selectionEnd,
                recyclerView.computeVerticalScrollOffset()
            )
        }
    })
    sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> lineEditor.reposition() }
    ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
        imeInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        updateListHeight()
        insets
    }

    dialog.setContentView(sheet)
    dialog.setOnShowListener {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.apply {
            maxWidth = dp(640)
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            backgroundTintList = null
            background = null
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sheet.translationY = dp(28).toFloat()
        sheet.alpha = 0.9f
        if (ValueAnimator.areAnimatorsEnabled()) {
            sheet.animate().translationY(0f).alpha(1f).setDuration(330)
                .setInterpolator(OvershootInterpolator(0.55f)).start()
        } else {
            sheet.translationY = 0f
            sheet.alpha = 1f
        }
        list.post { list.scrollBy(0, session.scrollY) }
        ViewCompat.requestApplyInsets(sheet)
    }
    dialog.setOnDismissListener {
        lineEditor.dispose()
        interactionUi.forget(dialog)
        if (!interactionUi.releasing && !activity.isChangingConfigurations && !activity.isDestroyed &&
            editorSession.state.value?.let { it.id == session.id && it.finished == null } == true
        ) editorSession.cancel()
    }
    interactionUi.register(dialog, dialog::dismiss)
    dialog.show()
    return EditorDialogBinding(session.id, dialog) { busy ->
        rows.enabled = !busy
        buttons.forEach { it.isEnabled = !busy; it.alpha = if (busy) 0.55f else 1f }
        dialog.setCancelable(!busy)
        dialog.behavior.isDraggable = !busy
    }
}

private fun roundedFill(color: Int, radiusPx: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx.toFloat()
}

private fun MainActivityUiHost.sheetButton(
    label: String,
    tone: AirTonalButtonTone,
    onClick: () -> Unit
): TextView = TextView(this).apply {
    text = label
    textSize = AirUiTokens.TextSize.Button
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    minHeight = dp(46)
    setPadding(dp(16), dp(10), dp(16), dp(10))
    styleAirTonalButton(this, tone)
    enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
    setOnClickListener { onClick() }
}

private class LrcRowsAdapter(
    private val host: MainActivityUiHost,
    private val document: EditableLrcDocument,
    private val onAction: (EditableLrcDocument.Row, View, Boolean) -> Unit
) : RecyclerView.Adapter<LrcRowsAdapter.RowHolder>() {
    private val visibleRows = document.rows.filter(EditableLrcDocument.Row::isVisible).toMutableList()
    private var list: RecyclerView? = null
    private var expanded: EditableLrcDocument.Row? = null
    var enabled = true

    override fun getItemCount(): Int = visibleRows.size
    override fun getItemViewType(position: Int): Int = if (visibleRows[position].isTimed) 1 else 0
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { list = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { list = null }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7))
        }
        if (viewType == 0) {
            val raw = TextView(host).apply {
                textSize = AirUiTokens.TextSize.Caption
                setTextColor(host.colorTextMuted)
            }
            container.addView(raw, LinearLayout.LayoutParams(-1, -2))
            return RowHolder(container, raw, null, null, null)
        }
        val line = LinearLayout(host).apply {
            gravity = Gravity.TOP
            minimumHeight = host.dp(38)
        }
        val time = TextView(host).apply {
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.MONOSPACE
            setTextColor(host.colorTextMuted)
            includeFontPadding = false
        }
        val lyric = TextView(host).apply {
            textSize = AirUiTokens.TextSize.Body
            setTextColor(host.colorTextStrong)
            setLineSpacing(host.dp(3).toFloat(), 1f)
            includeFontPadding = false
        }
        line.addView(time, LinearLayout.LayoutParams(host.dp(92), -2))
        line.addView(lyric, LinearLayout.LayoutParams(0, -2, 1f))
        time.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        lyric.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        container.addView(line, LinearLayout.LayoutParams(-1, -2))

        val reveal = ActionReveal(host)
        val actions = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        val edit = actionButton(host, R.string.ui_edit_line) {
            if (enabled) visibleRows.getOrNull(holderPosition(container))?.let { onAction(it, container, false) }
        }
        val add = actionButton(host, R.string.ui_add_next_line, green = true) {
            if (enabled) visibleRows.getOrNull(holderPosition(container))?.let { onAction(it, container, true) }
        }
        actions.addView(edit, LinearLayout.LayoutParams(0, host.dp(42), 1f))
        actions.addView(add, LinearLayout.LayoutParams(0, host.dp(42), 1f).apply { marginStart = host.dp(8) })
        reveal.addView(actions, FrameLayout.LayoutParams(-1, host.dp(42)).apply {
            topMargin = host.dp(8)
            bottomMargin = host.dp(8)
        })
        container.addView(reveal, LinearLayout.LayoutParams(-1, -2))
        container.isClickable = true
        container.isFocusable = true
        container.enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        container.setOnClickListener {
            if (!enabled) return@setOnClickListener
            val selected = visibleRows.getOrNull(holderPosition(container)) ?: return@setOnClickListener
            val old = expanded
            expanded = if (old === selected) null else selected
            old?.let(::refreshExpansion)
            if (expanded !== old) expanded?.let(::refreshExpansion)
        }
        return RowHolder(container, lyric, time, reveal, actions)
    }

    private fun holderPosition(view: View): Int =
        (list?.findContainingViewHolder(view)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION)

    private fun refreshExpansion(row: EditableLrcDocument.Row) {
        val index = visibleRows.indexOf(row)
        if (index < 0) return
        (list?.findViewHolderForAdapterPosition(index) as? RowHolder)?.animateExpansion(expanded === row)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(visibleRows[position], expanded === visibleRows[position])
    }

    override fun onViewRecycled(holder: RowHolder) { holder.stopAnimation() }

    fun update(row: EditableLrcDocument.Row) {
        visibleRows.indexOf(row).takeIf { it >= 0 }?.let(::notifyItemChanged)
    }

    fun insertAfter(anchor: EditableLrcDocument.Row, timestamp: String, lyric: String) {
        val documentIndex = document.rows.indexOf(anchor)
        val visibleIndex = visibleRows.indexOf(anchor)
        if (documentIndex < 0 || visibleIndex < 0) return
        val added = document.insertAfter(documentIndex, timestamp, lyric)
        visibleRows.add(visibleIndex + 1, added)
        notifyItemInserted(visibleIndex + 1)
    }

    class RowHolder(
        container: LinearLayout,
        private val lyricView: TextView,
        private val timeView: TextView?,
        private val reveal: ActionReveal?,
        private val actions: View?
    ) : RecyclerView.ViewHolder(container) {
        private var animator: ValueAnimator? = null
        fun bind(row: EditableLrcDocument.Row, expanded: Boolean) {
            stopAnimation()
            lyricView.text = row.lyric
            timeView?.text = row.timestamp
            itemView.contentDescription = if (row.isTimed) "${row.timestamp}, ${row.lyric}" else row.raw
            reveal?.showProgress(if (expanded) 1f else 0f)
        }

        fun animateExpansion(open: Boolean) {
            val panel = reveal ?: return
            stopAnimation()
            val target = if (open) 1f else 0f
            if (!ValueAnimator.areAnimatorsEnabled() || !itemView.isAttachedToWindow) {
                panel.showProgress(target, actions)
                return
            }
            val animation = ValueAnimator.ofFloat(panel.progress, target).apply {
                duration = if (open) 310L else 240L
                interpolator = OvershootInterpolator(if (open) 0.62f else 0.28f)
                addUpdateListener { panel.showProgress(it.animatedValue as Float, actions) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) {
                            animator = null
                            panel.showProgress(target, actions)
                        }
                    }
                })
            }
            animator = animation
            animation.start()
        }

        fun stopAnimation() {
            animator?.removeAllListeners()
            animator?.cancel()
            animator = null
        }
    }
}

private fun actionButton(host: MainActivityUiHost, label: Int, green: Boolean = false, onClick: () -> Unit): TextView = TextView(host).apply {
    text = host.getString(label)
    textSize = AirUiTokens.TextSize.BodySmall
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    host.styleAirTonalButton(this, AirTonalButtonTone.DEFAULT, AirUiTokens.Radius.Sm)
    if (green) {
        val base = host.airTonalButtonStyle(AirTonalButtonTone.DEFAULT)
        val sage = Color.rgb(143, 184, 129)
        val dark = host.isDarkTheme()
        val solid = host.buttonStyle() == ButtonStyle.SOLID
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(ColorUtils.blendARGB(base.fillColor, sage, if (solid) 0.42f else 0.22f))
            setStroke(host.dp(base.strokeWidthDp), ColorUtils.blendARGB(base.strokeColor, sage, 0.55f))
        }
        setTextColor(if (dark) Color.rgb(190, 222, 175) else Color.rgb(60, 111, 60))
    }
    enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
    setOnClickListener { onClick() }
}

/** The row stays put while this measured space moves every following row. */
private class ActionReveal(context: Context) : FrameLayout(context) {
    var progress = 0f
        private set

    init { clipChildren = false; clipToPadding = false }

    fun showProgress(value: Float, content: View? = getChildAt(0)) {
        progress = value
        visibility = if (value <= 0f) GONE else VISIBLE
        content?.apply {
            // Rounded controls only become visible once the reveal can hold their full height.
            alpha = ((value - 0.84f) / 0.16f).coerceIn(0f, 1f)
            translationY = (1f - value.coerceIn(0f, 1f)) * resources.displayMetrics.density * 5f
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(measuredWidth, (measuredHeight * progress.coerceAtLeast(0f)).roundToInt())
    }
}

private class FloatingLineEditor(
    private val host: MainActivityUiHost,
    private val sheet: FrameLayout,
    private val overlay: FrameLayout,
    private val document: EditableLrcDocument,
    private val rows: LrcRowsAdapter,
    private val onChanged: () -> Unit
) {
    private var card: HeightBoundScrollView? = null
    private var anchor: View? = null
    private var errorBubble: TextView? = null
    private var errorAnchor: View? = null
    private var closing = false

    init {
        overlay.setBackgroundColor(ColorUtils.setAlphaComponent(host.colorBackground, 120))
        overlay.setOnClickListener { if (!hideErrorBubble()) close() }
    }

    fun open(row: EditableLrcDocument.Row, anchor: View, insert: Boolean) {
        if (card != null) return
        this.anchor = anchor
        val form = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12))
        }
        val timestamp = ValidatedLineField(host, host.getString(R.string.ui_lyrics_time_hint), singleLine = true).apply {
            input.setText(row.timestamp.orEmpty().removeSurrounding("[", "]"))
        }
        val lyric = ValidatedLineField(host, host.getString(R.string.ui_lyrics), singleLine = false).apply {
            input.setText(if (insert) "" else row.lyric)
            input.minLines = 2
            input.maxLines = 3
            input.gravity = Gravity.TOP or Gravity.START
        }
        form.addView(timestamp.view, LinearLayout.LayoutParams(-1, -2))
        form.addView(lyric.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(7) })
        val footer = LinearLayout(host).apply { gravity = Gravity.END }
        val cancel = host.sheetButton(host.getString(R.string.ui_cancel), AirTonalButtonTone.DEFAULT) { close() }
            .apply { makeCompact(host) }
        val confirm = host.sheetButton(host.getString(R.string.ui_confirm_line), AirTonalButtonTone.SELECTED) {
            val tag = EditableLrcDocument.normalizedTimestamp(timestamp.input.text.toString())
                ?: return@sheetButton
            val text = EditableLrcDocument.normalizedLyric(lyric.input.text.toString())
            if (!EditableLrcDocument.canStoreAsOneRow(tag, text)) return@sheetButton
            if (!insert && tag == row.timestamp && text == row.lyric) {
                close()
                return@sheetButton
            }
            if (insert) {
                rows.insertAfter(row, tag, text)
            } else {
                document.update(document.rows.indexOf(row), tag, text)
                rows.update(row)
            }
            onChanged()
            close()
        }.apply { makeCompact(host) }
        footer.addView(cancel)
        footer.addView(confirm, LinearLayout.LayoutParams(-2, -2).apply { marginStart = host.dp(8) })
        form.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(9) })

        fun validateInputs() {
            val timeResult = LrcParser.validateTimeTag(
                EditableLrcDocument.timeTagInput(timestamp.input.text.toString())
            )
            val timeError = when (timeResult) {
                LrcParser.TimeTagValidation.VALID -> null
                LrcParser.TimeTagValidation.INVALID_FORMAT -> host.getString(R.string.ui_lyrics_time_error)
                LrcParser.TimeTagValidation.SECONDS_OUT_OF_RANGE -> host.getString(R.string.ui_lyrics_seconds_error)
            }
            val text = EditableLrcDocument.normalizedLyric(lyric.input.text.toString())
            val tagForText = EditableLrcDocument.normalizedTimestamp(timestamp.input.text.toString())
                ?: row.timestamp.orEmpty()
            val lyricError = if (EditableLrcDocument.canStoreAsOneRow(tagForText, text)) null
                else host.getString(R.string.ui_lyrics_one_line_error)
            timestamp.showError(timeError)
            lyric.showError(lyricError)
            val currentError = when (errorAnchor) {
                timestamp.errorIcon -> timeError
                lyric.errorIcon -> lyricError
                else -> null
            }
            if (errorBubble != null) {
                if (currentError == null) hideErrorBubble()
                else errorBubble?.apply {
                    if (this.text.toString() != currentError) {
                        this.text = currentError
                        post { errorAnchor?.let { icon -> positionErrorBubble(this, icon) } }
                    }
                }
            }
            confirm.isEnabled = timeError == null && lyricError == null
            confirm.alpha = if (confirm.isEnabled) 1f else 0.5f
        }
        timestamp.errorIcon.setOnClickListener {
            timestamp.errorMessage?.let { message -> toggleErrorBubble(timestamp.errorIcon, message) }
        }
        lyric.errorIcon.setOnClickListener {
            lyric.errorMessage?.let { message -> toggleErrorBubble(lyric.errorIcon, message) }
        }
        timestamp.input.setOnClickListener { hideErrorBubble() }
        lyric.input.setOnClickListener { hideErrorBubble() }
        timestamp.input.doAfterTextChanged { validateInputs() }
        lyric.input.doAfterTextChanged { validateInputs() }
        validateInputs()

        val editorCard = HeightBoundScrollView(host).apply {
            isFillViewport = false
            clipToOutline = false
            background = GradientDrawable().apply {
                cornerRadius = host.dp(20).toFloat()
                setColor(host.colorSurfaceLight)
                setStroke(host.dp(1), host.colorStroke)
            }
            elevation = host.dp(16).toFloat()
            isClickable = true
            isFocusableInTouchMode = true
            setOnClickListener { hideErrorBubble() }
            addView(form)
        }
        card = editorCard
        overlay.removeAllViews()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        val cardWidth = (sheet.width - host.dp(32)).coerceAtLeast(1).coerceAtMost(host.dp(304))
        overlay.addView(editorCard, FrameLayout.LayoutParams(cardWidth, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = host.dp(16)
        })
        editorCard.alpha = 0f
        editorCard.scaleX = 0.94f
        editorCard.scaleY = 0.94f
        editorCard.translationX = host.dp(56).toFloat()
        editorCard.post {
            if (card !== editorCard) return@post
            reposition()
            if (ValueAnimator.areAnimatorsEnabled()) {
                overlay.animate().alpha(1f).setDuration(160).start()
                editorCard.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(330).setInterpolator(OvershootInterpolator(0.72f)).start()
            } else {
                overlay.alpha = 1f
                editorCard.translationX = 0f
                editorCard.alpha = 1f
                editorCard.scaleX = 1f
                editorCard.scaleY = 1f
            }
        }
    }

    fun reposition() {
        val editorCard = card ?: return
        if (sheet.width == 0 || sheet.height == 0) return
        val params = editorCard.layoutParams as FrameLayout.LayoutParams
        val width = (sheet.width - host.dp(32)).coerceAtLeast(1).coerceAtMost(host.dp(304))
        if (params.width != width) {
            params.width = width
            editorCard.layoutParams = params
        }
        editorCard.maxHeightPx = (sheet.height - host.dp(24)).coerceAtLeast(host.dp(80))
        val source = anchor ?: return
        val sheetPosition = IntArray(2)
        val anchorPosition = IntArray(2)
        sheet.getLocationOnScreen(sheetPosition)
        source.getLocationOnScreen(anchorPosition)
        val centered = anchorPosition[1] - sheetPosition[1] + source.height / 2 - editorCard.height / 2
        val top = centered.coerceIn(host.dp(12), (sheet.height - editorCard.height - host.dp(12)).coerceAtLeast(host.dp(12)))
        if (params.topMargin != top) {
            params.topMargin = top
            editorCard.layoutParams = params
        }
        errorBubble?.post {
            val bubble = errorBubble ?: return@post
            val bubbleAnchor = errorAnchor ?: return@post
            positionErrorBubble(bubble, bubbleAnchor)
        }
    }

    private fun toggleErrorBubble(icon: View, message: String) {
        if (errorAnchor === icon) {
            hideErrorBubble()
            return
        }
        hideErrorBubble(animate = false)
        val bubble = TextView(host).apply {
            text = message
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(host.colorTextStrong)
            maxWidth = (sheet.width - host.dp(24)).coerceAtMost(host.dp(220))
            setPadding(host.dp(12), host.dp(9), host.dp(12), host.dp(9))
            background = GradientDrawable().apply {
                cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
                setColor(host.colorCard)
                setStroke(host.dp(1), ColorUtils.blendARGB(host.colorStroke, host.colorDanger, 0.16f))
            }
            elevation = host.dp(24).toFloat()
            isClickable = true
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            setOnClickListener { hideErrorBubble() }
        }
        overlay.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        errorBubble = bubble
        errorAnchor = icon
        bubble.post {
            if (errorBubble !== bubble) return@post
            positionErrorBubble(bubble, icon)
            bubble.pivotX = bubble.width * 0.85f
            bubble.pivotY = bubble.height.toFloat()
            if (ValueAnimator.areAnimatorsEnabled()) {
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(OvershootInterpolator(0.35f)).start()
            } else { bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f }
        }
    }

    private fun positionErrorBubble(bubble: TextView, icon: View) {
        if (sheet.width == 0 || sheet.height == 0) return
        val screen = IntArray(2)
        val iconPosition = IntArray(2)
        sheet.getLocationOnScreen(screen)
        icon.getLocationOnScreen(iconPosition)
        val margin = host.dp(12)
        val preferredLeft = iconPosition[0] - screen[0] + icon.width - bubble.width + host.dp(8)
        val left = preferredLeft.coerceIn(margin, (sheet.width - bubble.width - margin).coerceAtLeast(margin))
        val above = iconPosition[1] - screen[1] - bubble.height - host.dp(8)
        val below = iconPosition[1] - screen[1] + icon.height + host.dp(8)
        val top = (if (above >= margin) above else below)
            .coerceIn(margin, (sheet.height - bubble.height - margin).coerceAtLeast(margin))
        (bubble.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = left
            topMargin = top
            bubble.layoutParams = this
        }
    }

    private fun hideErrorBubble(animate: Boolean = true): Boolean {
        val bubble = errorBubble ?: return false
        errorBubble = null
        errorAnchor = null
        bubble.animate().cancel()
        if (animate && ValueAnimator.areAnimatorsEnabled() && bubble.isAttachedToWindow) {
            bubble.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(120)
                .withEndAction { overlay.removeView(bubble) }.start()
        } else overlay.removeView(bubble)
        return true
    }

    fun close(): Boolean {
        val editorCard = card ?: return false
        if (closing) return true
        closing = true
        hideErrorBubble(animate = false)
        (host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editorCard.windowToken, 0)
        editorCard.clearFocus()
        editorCard.animate().cancel()
        overlay.animate().cancel()
        fun finish() {
            overlay.removeAllViews()
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            card = null
            anchor = null
            closing = false
        }
        if (ValueAnimator.areAnimatorsEnabled() && editorCard.isAttachedToWindow) {
            overlay.animate().alpha(0f).setDuration(170).start()
            editorCard.animate().translationX(host.dp(42).toFloat()).alpha(0f)
                .scaleX(0.97f).scaleY(0.97f)
                .setDuration(170).setInterpolator(DecelerateInterpolator())
                .withEndAction(::finish).start()
        } else finish()
        return true
    }

    fun dispose() {
        hideErrorBubble(animate = false)
        card?.animate()?.cancel()
        overlay.animate().cancel()
        overlay.removeAllViews()
        card = null
        anchor = null
    }
}

private fun TextView.makeCompact(host: MainActivityUiHost) {
    minHeight = host.dp(38)
    setPadding(host.dp(12), host.dp(7), host.dp(12), host.dp(7))
}

private class HeightBoundScrollView(context: Context) : NestedScrollView(context) {
    var maxHeightPx = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}

private class ValidatedLineField(
    private val host: MainActivityUiHost,
    hintText: String,
    singleLine: Boolean
) {
    var errorMessage: String? = null
        private set

    val view = FrameLayout(host)
    val input = AppCompatEditText(host).apply {
        hint = hintText
        contentDescription = hintText
        textSize = AirUiTokens.TextSize.Body
        setTextColor(host.colorTextStrong)
        setHintTextColor(host.colorTextMuted)
        minHeight = host.dp(40)
        setPadding(host.dp(12), host.dp(9), host.dp(42), host.dp(9))
        background = inputBackground(null)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (singleLine) 0 else InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setSingleLine(singleLine)
        if (!singleLine) setHorizontallyScrolling(false)
    }
    val errorIcon = ImageView(host).apply {
        setImageResource(R.drawable.ic_air_error)
        imageTintList = ColorStateList.valueOf(host.colorDanger)
        visibility = View.GONE
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(host.dp(7), host.dp(7), host.dp(7), host.dp(7))
    }

    init {
        view.addView(input, FrameLayout.LayoutParams(-1, -2))
        view.addView(errorIcon, FrameLayout.LayoutParams(host.dp(38), host.dp(38), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = host.dp(2)
        })
    }

    fun showError(message: String?) {
        if (message == errorMessage) return
        errorMessage = message
        input.background = inputBackground(message)
        errorIcon.visibility = if (message == null) View.GONE else View.VISIBLE
        errorIcon.contentDescription = message
    }

    private fun inputBackground(error: String?) = GradientDrawable().apply {
        cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
        setColor(host.colorCard)
        setStroke(host.dp(1), if (error == null) host.colorStroke else host.colorDanger)
    }
}
