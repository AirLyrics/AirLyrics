package com.andsi.airlyrics.app.host

import android.annotation.SuppressLint
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
import android.view.animation.PathInterpolator
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
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.WordLyricsTextEditor
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
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
    val document = if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD)
        EditableLrcDocument.fromWords(session.text) else EditableLrcDocument.from(if (session.item.isUntimed) "" else session.text)
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
        // Row editing and action disclosure notify independently; only explicit insertion animates.
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
    var onDocumentChanged: (Boolean) -> Unit = {}
    val rows = LrcRowsAdapter(
        this, document,
        onAction = { row, anchor, insert -> lineEditor.open(row, anchor, insert) },
        onDelete = { onDocumentChanged(false) }
    )
    list.adapter = rows
    var captureRaw: () -> Unit = {}
    val rawInput = if (session.item.isUntimed) object : AppCompatEditText(this) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            captureRaw()
        }
    }.apply {
        isSaveEnabled = session.access == null
        setText(session.text)
        setSelection(session.selectionStart.coerceIn(0, length()), session.selectionEnd.coerceIn(0, length()))
        hint = getString(R.string.ui_lyrics)
        contentDescription = getString(R.string.ui_lyrics)
        textSize = AirUiTokens.TextSize.Body
        minLines = 8
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(false)
        setTextColor(colorTextStrong)
        setHintTextColor(colorTextMuted)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = null
    } else null
    val rawScroll = rawInput?.let { input -> NestedScrollView(this).apply {
        isFillViewport = true
        background = list.background.constantState?.newDrawable()
        addView(LinearLayout(this@showLocalLyricsEditorSheet).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(-1, -2))
            session.untimedTranslation?.let { translation ->
                addView(TextView(this@showLocalLyricsEditorSheet).apply {
                    text = getString(R.string.search_translation)
                    textSize = AirUiTokens.TextSize.Caption
                    setTextColor(colorTextMuted)
                    setPadding(dp(12), dp(8), dp(12), dp(4))
                })
                addView(TextView(this@showLocalLyricsEditorSheet).apply {
                    text = translation
                    textSize = AirUiTokens.TextSize.Body
                    setTextColor(colorTextStrong)
                    setTextIsSelectable(true)
                    setPadding(dp(12), 0, dp(12), dp(12))
                })
            }
        }, ViewGroup.LayoutParams(-1, -2))
    } }
    val editorBody: View = rawScroll ?: list
    val maxListHeight = (resources.displayMetrics.heightPixels * 0.84f).toInt() - dp(220)
    var imeInset = 0
    var listHeightAnimator: ValueAnimator? = null
    fun listHeight(): Int {
        val preferred = (if (rawInput != null) dp(280) else rows.itemCount * dp(64)).coerceAtLeast(dp(86))
            .coerceAtMost(maxListHeight.coerceAtLeast(dp(86)))
        val available = resources.displayMetrics.heightPixels - imeInset - dp(220)
        return preferred.coerceAtMost(available.coerceAtLeast(dp(76)))
    }
    fun updateListHeight(animate: Boolean = false) {
        val height = listHeight()
        val current = editorBody.layoutParams.height
        val previous = listHeightAnimator
        listHeightAnimator = null
        previous?.cancel()
        if (current == height) return
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !editorBody.isAttachedToWindow) {
            editorBody.layoutParams = editorBody.layoutParams.apply { this.height = height }
            return
        }
        val motion = ValueAnimator.ofInt(current, height).apply {
            duration = 235L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val next = animation.animatedValue as Int
                if (editorBody.layoutParams.height != next) {
                    editorBody.layoutParams = editorBody.layoutParams.apply { this.height = next }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (listHeightAnimator === animation) {
                        listHeightAnimator = null
                        editorBody.layoutParams = editorBody.layoutParams.apply { this.height = height }
                    }
                }
            })
        }
        listHeightAnimator = motion
        motion.start()
    }
    content.addView(editorBody, LinearLayout.LayoutParams(-1, listHeight()))

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

    if (rawInput != null && rawScroll != null) {
        captureRaw = {
            editorSession.edit(rawInput.text.toString(), rawInput.selectionStart, rawInput.selectionEnd, rawScroll.scrollY)
            save.isEnabled = editorSession.state.value?.busy != true && (!rawInput.text.isNullOrBlank() || !session.untimedTranslation.isNullOrBlank())
            save.alpha = if (save.isEnabled) 1f else 0.5f
        }
        rawInput.doAfterTextChanged { captureRaw() }
        rawScroll.setOnScrollChangeListener { _, _, _, _, _ -> captureRaw() }
        interactionUi.snapshot(rawInput, captureRaw)
        rawInput.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { interactionUi.forgetSnapshot(rawInput) }
        })
    }
    val entranceParts = listOf(header, editorBody, footer)
    if (ValueAnimator.areAnimatorsEnabled()) entranceParts.forEachIndexed { index, view ->
        view.alpha = 0.72f
        view.translationY = dp(6 + index * 2).toFloat()
    }

    sheet.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    onDocumentChanged = { inserted ->
        val text = document.serialize()
        editorSession.edit(text, 0, 0, list.computeVerticalScrollOffset())
        updateListHeight(inserted)
    }
    lineEditor = FloatingLineEditor(this, sheet, overlay, document, rows, onDocumentChanged)
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
        if (ValueAnimator.areAnimatorsEnabled()) {
            entranceParts.forEachIndexed { index, view ->
                view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(index * 28L).setDuration(175L)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
        } else {
            entranceParts.forEach { it.alpha = 1f; it.translationY = 0f }
        }
        editorBody.post { if (rawScroll != null) rawScroll.scrollTo(0, session.scrollY) else list.scrollBy(0, session.scrollY) }
        ViewCompat.requestApplyInsets(sheet)
    }
    dialog.setOnDismissListener {
        listHeightAnimator?.cancel()
        listHeightAnimator = null
        entranceParts.forEach { it.animate().cancel() }
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
        rawInput?.isEnabled = !busy
        buttons.forEach { it.isEnabled = !busy; it.alpha = if (busy) 0.55f else 1f }
        if (rawInput != null) {
            save.isEnabled = !busy && (!rawInput.text.isNullOrBlank() || !session.untimedTranslation.isNullOrBlank())
            save.alpha = if (save.isEnabled) 1f else 0.5f
        }
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
    private val onAction: (EditableLrcDocument.Row?, View, Boolean) -> Unit,
    private val onDelete: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val visibleRows = document.rows.filter(EditableLrcDocument.Row::isVisible).toMutableList()
    private val pendingInserted = mutableSetOf<EditableLrcDocument.Row>()
    private var list: RecyclerView? = null
    private var expanded: EditableLrcDocument.Row? = null
    var enabled = true

    override fun getItemCount(): Int = visibleRows.size.coerceAtLeast(1)
    override fun getItemViewType(position: Int): Int = when {
        visibleRows.isEmpty() -> VIEW_TYPE_EMPTY
        visibleRows[position].isTimed -> VIEW_TYPE_TIMED
        else -> VIEW_TYPE_RAW
    }
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { list = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        pendingInserted.clear()
        list = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7))
        }
        if (viewType == VIEW_TYPE_EMPTY) {
            val add = actionButton(host, R.string.ui_add_next_line, tone = RowActionTone.ADD) {
                if (enabled && visibleRows.isEmpty()) onAction(null, container, true)
            }.apply {
                minHeight = host.dp(48)
                setPadding(host.dp(6), host.dp(8), host.dp(6), host.dp(8))
            }
            container.addView(add, LinearLayout.LayoutParams(-1, -2))
            return EmptyHolder(rowView(container))
        }
        if (viewType == VIEW_TYPE_RAW) {
            val raw = TextView(host).apply {
                textSize = AirUiTokens.TextSize.Caption
                setTextColor(host.colorTextMuted)
            }
            container.addView(raw, LinearLayout.LayoutParams(-1, -2))
            return RowHolder(rowView(container), raw, null, null, null)
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
        val delete = actionButton(host, R.string.ui_delete, tone = RowActionTone.DELETE) {
            if (enabled) visibleRows.getOrNull(holderPosition(container))?.let(::delete)
        }.apply { contentDescription = host.getString(R.string.ui_delete_lyric_line) }
        val add = actionButton(host, R.string.ui_add_next_line, tone = RowActionTone.ADD) {
            if (enabled) visibleRows.getOrNull(holderPosition(container))?.let { onAction(it, container, true) }
        }
        val lineHeight = add.paint.fontMetricsInt.run { descent - ascent }
        val actionHeight = maxOf(host.dp(48), lineHeight * 2 + host.dp(8))
        actions.addView(edit, LinearLayout.LayoutParams(0, actionHeight, 1f))
        actions.addView(delete, LinearLayout.LayoutParams(0, actionHeight, 1.2f).apply { marginStart = host.dp(8) })
        actions.addView(add, LinearLayout.LayoutParams(0, actionHeight, 1.3f).apply { marginStart = host.dp(8) })
        reveal.addView(actions, FrameLayout.LayoutParams(-1, actionHeight).apply {
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
        return RowHolder(rowView(container), lyric, time, reveal, actions)
    }

    private fun rowView(content: LinearLayout): InsertReveal = InsertReveal(host, content).apply {
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun holderPosition(view: View): Int =
        (list?.findContainingViewHolder(view)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION)

    private fun refreshExpansion(row: EditableLrcDocument.Row) {
        val index = visibleRows.indexOf(row)
        if (index < 0) return
        (list?.findViewHolderForAdapterPosition(index) as? RowHolder)?.animateExpansion(expanded === row)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is RowHolder) {
            val row = visibleRows[position]
            holder.bind(row, expanded === row, pendingInserted.remove(row))
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) { (holder as? RowHolder)?.stopAnimation() }
    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) { (holder as? RowHolder)?.stopAnimation() }

    fun update(row: EditableLrcDocument.Row) {
        visibleRows.indexOf(row).takeIf { it >= 0 }?.let(::notifyItemChanged)
    }

    private fun delete(row: EditableLrcDocument.Row) {
        val visibleIndex = visibleRows.indexOf(row)
        val documentIndex = document.rows.indexOf(row)
        if (visibleIndex < 0 || documentIndex < 0) return
        expanded = null
        pendingInserted.remove(row)
        document.removeAt(documentIndex)
        visibleRows.removeAt(visibleIndex)
        if (visibleRows.isEmpty()) notifyItemChanged(0) else notifyItemRemoved(visibleIndex)
        onDelete()
    }

    fun insertAfter(anchor: EditableLrcDocument.Row?, timestamp: String, lyric: String, wordLine: WordByWordLine? = null) {
        val wasEmpty = visibleRows.isEmpty()
        val added: EditableLrcDocument.Row
        val insertIndex: Int
        if (anchor == null) {
            if (!wasEmpty) return
            added = document.append(timestamp, lyric)
            insertIndex = 0
        } else {
            val documentIndex = document.rows.indexOf(anchor)
            val visibleIndex = visibleRows.indexOf(anchor)
            if (documentIndex < 0 || visibleIndex < 0) return
            added = document.insertAfter(documentIndex, timestamp, lyric)
            insertIndex = visibleIndex + 1
        }
        added.wordLine = wordLine
        visibleRows.add(insertIndex, added)
        val manager = list?.layoutManager as? LinearLayoutManager
        val visibleInsert = manager != null && insertIndex in
            manager.findFirstVisibleItemPosition()..(manager.findLastVisibleItemPosition() + 1)
        if (visibleInsert) pendingInserted += added
        // The first lyric replaces the empty-state action at the same adapter position.
        if (wasEmpty) notifyItemChanged(0) else notifyItemInserted(insertIndex)
        if (visibleInsert) list?.handler?.postDelayed({ pendingInserted.remove(added) }, 400L)
    }

    private class EmptyHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val VIEW_TYPE_RAW = 0
        const val VIEW_TYPE_TIMED = 1
        const val VIEW_TYPE_EMPTY = 2
    }

    class RowHolder(
        private val insertion: InsertReveal,
        private val lyricView: TextView,
        private val timeView: TextView?,
        private val reveal: ActionReveal?,
        private val actions: View?
    ) : RecyclerView.ViewHolder(insertion) {
        private var animator: ValueAnimator? = null
        private var insertionAnimator: ValueAnimator? = null
        private var startInsertion: Runnable? = null
        fun bind(row: EditableLrcDocument.Row, expanded: Boolean, inserted: Boolean) {
            stopAnimation()
            lyricView.text = listOfNotNull(row.lyric, row.wordLine?.translatedText?.takeIf { it.isNotBlank() }).joinToString("\n")
            timeView?.text = listOfNotNull(row.timestamp,
                lyricView.context.getString(R.string.ui_lyrics_background).takeIf { row.wordLine?.isBackground == true }).joinToString("\n")
            insertion.setRowDescription(if (row.isTimed) "${row.timestamp}, ${row.lyric}" else row.raw)
            reveal?.showProgress(if (expanded) 1f else 0f)
            if (inserted) {
                insertion.showProgress(0f)
                val start = Runnable {
                    startInsertion = null
                    animateInsertion()
                }
                startInsertion = start
                itemView.post(start)
            }
        }

        private fun animateInsertion() {
            if (!itemView.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
                insertion.showProgress(1f)
                return
            }
            val motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250L
                interpolator = OvershootInterpolator(0.32f)
                addUpdateListener { insertion.showProgress(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (insertionAnimator === animation) {
                            insertionAnimator = null
                            insertion.showProgress(1f)
                        }
                    }
                })
            }
            insertionAnimator = motion
            motion.start()
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
            startInsertion?.let(itemView::removeCallbacks)
            startInsertion = null
            insertionAnimator?.removeAllListeners()
            insertionAnimator?.cancel()
            insertionAnimator = null
            insertion.showProgress(1f)
            animator?.removeAllListeners()
            animator?.cancel()
            animator = null
        }
    }
}

/** The inserted row reveals its measured height, pushing its neighbours during the same motion. */
@SuppressLint("ViewConstructor")
private class InsertReveal(context: Context, private val content: LinearLayout) : FrameLayout(context) {
    private var progress = 1f

    init {
        clipChildren = true
        clipToPadding = true
        addView(content, LayoutParams(-1, -2))
    }

    fun setRowDescription(description: String) { content.contentDescription = description }

    fun showProgress(value: Float) {
        if (progress == value) return
        progress = value
        content.alpha = (0.4f + value.coerceIn(0f, 1f) * 0.6f).coerceAtMost(1f)
        content.translationY = (1f - value) * resources.displayMetrics.density * 8f
        content.pivotY = 0f
        content.scaleY = 1f + (value - 1f) * 0.015f
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(measuredWidth, (measuredHeight * progress.coerceIn(0f, 1f)).roundToInt())
    }
}

private enum class RowActionTone { DEFAULT, ADD, DELETE }

private fun actionButton(
    host: MainActivityUiHost,
    label: Int,
    tone: RowActionTone = RowActionTone.DEFAULT,
    onClick: () -> Unit
): TextView = TextView(host).apply {
    text = host.getString(label)
    textSize = AirUiTokens.TextSize.BodySmall
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    maxLines = 2
    setPadding(host.dp(6), 0, host.dp(6), 0)
    host.styleAirTonalButton(this, AirTonalButtonTone.DEFAULT, AirUiTokens.Radius.Sm)
    if (tone != RowActionTone.DEFAULT) {
        val base = host.airTonalButtonStyle(AirTonalButtonTone.DEFAULT)
        val tint = if (tone == RowActionTone.ADD) Color.rgb(143, 184, 129) else host.colorDanger
        val dark = host.isDarkTheme()
        val solid = host.buttonStyle() == ButtonStyle.SOLID
        val fillTint = if (tone == RowActionTone.ADD) {
            if (solid) 0.42f else 0.22f
        } else {
            if (solid) 0.24f else 0.16f
        }
        val strokeTint = if (tone == RowActionTone.DELETE) 0.4f else 0.55f
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(ColorUtils.blendARGB(base.fillColor, tint, fillTint))
            setStroke(host.dp(base.strokeWidthDp), ColorUtils.blendARGB(base.strokeColor, tint, strokeTint))
        }
        setTextColor(
            if (tone == RowActionTone.ADD) {
                if (dark) Color.rgb(190, 222, 175) else Color.rgb(60, 111, 60)
            } else {
                host.colorDanger
            }
        )
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
    private val onChanged: (Boolean) -> Unit
) {
    private var card: HeightBoundScrollView? = null
    private var anchor: View? = null
    private var errorBubble: TextView? = null
    private var errorAnchor: View? = null
    private var closing = false
    private val restingMinHeight = sheet.minimumHeight

    init {
        overlay.setBackgroundColor(ColorUtils.setAlphaComponent(host.colorBackground, 120))
        overlay.setOnClickListener { if (!hideErrorBubble()) close() }
    }

    fun open(row: EditableLrcDocument.Row?, anchor: View, insert: Boolean) {
        if (card != null || (!insert && row == null)) return
        this.anchor = anchor
        if (document.isWordByWord) {
            openWords(row, insert)
            return
        }
        val form = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12))
        }
        val timestamp = ValidatedLineField(host, host.getString(R.string.ui_lyrics_time_hint), singleLine = true).apply {
            input.setText((row?.timestamp ?: "[00:00.00]").removeSurrounding("[", "]"))
        }
        val lyric = ValidatedLineField(host, host.getString(R.string.ui_lyrics), singleLine = false).apply {
            input.setText(if (insert) "" else row?.lyric.orEmpty())
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
            if (!insert && row != null && tag == row.timestamp && text == row.lyric) {
                close()
                return@sheetButton
            }
            if (insert) {
                rows.insertAfter(row, tag, text)
            } else if (row != null) {
                document.update(document.rows.indexOf(row), tag, text)
                rows.update(row)
            }
            onChanged(insert)
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
                ?: row?.timestamp.orEmpty()
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

        showForm(form)
    }

    private fun openWords(row: EditableLrcDocument.Row?, insert: Boolean) {
        val start = if (insert) row?.wordLine?.endMs ?: 0L else row?.wordLine?.startMs ?: 0L
        val baseline = if (insert) WordByWordLine(start, start + 400, "",
            listOf(WordByWordSegment("", start, start + 400))) else row?.wordLine ?: return
        val form = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12))
        }
        fun field(label: Int, text: String, singleLine: Boolean = true) =
            ValidatedLineField(host, host.getString(label), singleLine).apply {
                input.setText(text)
                input.minHeight = host.dp(48)
            }
        val lineText = field(R.string.ui_lyrics_line_text, WordLyricsTextEditor.rawLine(baseline), singleLine = false).apply {
            input.minLines = 2
            input.maxLines = 3
            input.gravity = Gravity.TOP or Gravity.START
        }
        fun label(text: CharSequence) = TextView(host).apply {
            this.text = text
            textSize = AirUiTokens.TextSize.Caption
            setTextColor(host.colorTextMuted)
            setPadding(0, 0, 0, host.dp(4))
        }
        fun labeled(input: ValidatedLineField) = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(input.input.hint), LinearLayout.LayoutParams(-1, -2))
            addView(input.view, LinearLayout.LayoutParams(-1, -2))
        }
        fun times(parent: LinearLayout, a: ValidatedLineField, b: ValidatedLineField) {
            parent.addView(LinearLayout(host).apply {
                addView(labeled(a), LinearLayout.LayoutParams(0, -2, 1f))
                addView(labeled(b), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = host.dp(8) })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        form.addView(labeled(lineText), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(10) })
        val lineActions = LinearLayout(host).apply { gravity = Gravity.END }
        form.addView(lineActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(6) })
        val tokenList = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        form.addView(tokenList, LinearLayout.LayoutParams(-1, -2))
        data class TokenFields(val text: ValidatedLineField, val start: ValidatedLineField,
            val end: ValidatedLineField, val view: LinearLayout, val heading: TextView)
        val tokens = mutableListOf<TokenFields>()
        var validate: () -> Unit = {}
        var syncingText = false
        var appliedLine = baseline
        var confirmedRaw = WordLyricsTextEditor.rawLine(baseline)
        fun editedLine(): WordByWordLine? {
            val segments = tokens.map {
                WordByWordSegment(it.text.input.text.toString(),
                    WordLyricsDocument.parseTime(it.start.input.text.toString()) ?: return null,
                    WordLyricsDocument.parseTime(it.end.input.text.toString()) ?: return null)
            }
            return WordLyricsTextEditor.withSegments(appliedLine, segments)
        }
        fun syncWholeText() {
            if (syncingText) return
            val edited = editedLine() ?: return
            confirmedRaw = WordLyricsTextEditor.rawLine(edited)
            if (lineText.input.text.toString() != confirmedRaw) {
                syncingText = true
                lineText.input.setText(confirmedRaw)
                syncingText = false
            }
        }
        fun watch(input: ValidatedLineField) {
            input.errorIcon.setOnClickListener {
                input.errorMessage?.let { toggleErrorBubble(input.errorIcon, it) }
            }
            input.input.setOnClickListener { hideErrorBubble() }
            input.input.doAfterTextChanged {
                if (!syncingText) {
                    syncWholeText()
                    validate()
                }
            }
        }
        fun renumber() {
            tokens.forEachIndexed { index, fields ->
                val label = host.getString(R.string.ui_lyrics_word_text, index + 1)
                fields.heading.text = label
                fields.text.input.hint = label
                fields.text.input.contentDescription = label
                fields.start.input.contentDescription = "$label, ${host.getString(R.string.ui_lyrics_word_start)}"
                fields.end.input.contentDescription = "$label, ${host.getString(R.string.ui_lyrics_word_end)}"
            }
        }
        fun addToken(segment: WordByWordSegment) {
            val text = field(R.string.ui_text, segment.text)
            val a = field(R.string.ui_lyrics_word_start, WordLyricsDocument.time(segment.startMs))
            val b = field(R.string.ui_lyrics_word_end, WordLyricsDocument.time(segment.endMs))
            val container = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, host.dp(14), 0, 0)
            }
            val heading = label(host.getString(R.string.ui_text))
            container.addView(heading, LinearLayout.LayoutParams(-1, -2))
            container.addView(text.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(7) })
            times(container, a, b)
            val fields = TokenFields(text, a, b, container, heading)
            container.addView(host.sheetButton(host.getString(R.string.ui_delete_word), AirTonalButtonTone.DEFAULT) {
                hideErrorBubble(animate = false)
                tokens.remove(fields)
                tokenList.removeView(container)
                renumber()
                syncWholeText()
                validate()
            }.apply { minHeight = host.dp(48) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(4) })
            tokens += fields
            tokenList.addView(container, LinearLayout.LayoutParams(-1, -2))
            watch(text)
            watch(a)
            watch(b)
            renumber()
        }
        baseline.segments.forEach(::addToken)
        val addWord = host.sheetButton(host.getString(R.string.ui_add_word), AirTonalButtonTone.DEFAULT) {
            val a = tokens.lastOrNull()?.end?.input?.text?.toString()?.let(WordLyricsDocument::parseTime)
                ?: appliedLine.startMs
            val b = a + 400
            addToken(WordByWordSegment("", a, b))
            syncWholeText()
            validate()
            tokens.last().text.input.requestFocus()
        }.apply { minHeight = host.dp(48) }
        form.addView(addWord, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(10) })
        fun hasPendingLineChanges() = lineText.input.text.toString() != confirmedRaw
        fun restoreLineFields() {
            syncingText = true
            lineText.input.setText(confirmedRaw)
            syncingText = false
        }
        fun parsedRawLine(): WordByWordLine? {
            val source = editedLine()?.takeIf { WordLyricsDocument(listOf(it)).invalidLines().isEmpty() } ?: appliedLine
            return WordLyricsTextEditor.parseRawLine(source, lineText.input.text.toString())
        }
        val resetLine = host.sheetButton(host.getString(R.string.ui_lyrics_reset_line), AirTonalButtonTone.DEFAULT) {
            restoreLineFields()
            validate()
        }.apply { minHeight = host.dp(48) }
        val updateWords = host.sheetButton(host.getString(R.string.ui_lyrics_update_words), AirTonalButtonTone.SELECTED) {
            val updated = parsedRawLine() ?: return@sheetButton
            syncingText = true
            hideErrorBubble(animate = false)
            tokenList.removeAllViews()
            tokens.clear()
            appliedLine = updated
            confirmedRaw = WordLyricsTextEditor.rawLine(updated)
            updated.segments.forEach(::addToken)
            syncingText = false
            restoreLineFields()
            validate()
        }.apply { minHeight = host.dp(48) }
        lineActions.addView(resetLine, LinearLayout.LayoutParams(0, -2, 1f))
        lineActions.addView(updateWords, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = host.dp(8) })
        lineText.errorIcon.setOnClickListener {
            lineText.errorMessage?.let { toggleErrorBubble(lineText.errorIcon, it) }
        }
        lineText.input.setOnClickListener { hideErrorBubble() }
        lineText.input.doAfterTextChanged { if (!syncingText) validate() }
        val footer = LinearLayout(host).apply { gravity = Gravity.END }
        footer.addView(host.sheetButton(host.getString(R.string.ui_cancel), AirTonalButtonTone.DEFAULT) { close() })
        val confirm = host.sheetButton(host.getString(R.string.ui_confirm_line), AirTonalButtonTone.SELECTED) {
            if (hasPendingLineChanges()) return@sheetButton
            val line = editedLine() ?: return@sheetButton
            if (WordLyricsDocument(listOf(line)).invalidLines().isNotEmpty()) return@sheetButton
            if (insert) rows.insertAfter(row, "[${WordLyricsDocument.time(line.startMs)}]", line.text, line)
            else if (row != null) {
                if (line == baseline) { close(); return@sheetButton }
                document.updateWord(row, line)
                rows.update(row)
            }
            onChanged(insert)
            close()
        }
        footer.addView(confirm, LinearLayout.LayoutParams(-2, -2).apply { marginStart = host.dp(8) })
        form.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(9) })
        validate = {
            val formatError = host.getString(R.string.ui_lyrics_time_error)
            val rangeError = host.getString(R.string.ui_lyrics_word_time_error)
            val text = lineText.input.text.toString()
            val textError = when {
                text.isBlank() -> host.getString(R.string.ui_lyrics_line_empty)
                '\n' in text || '\r' in text -> host.getString(R.string.ui_lyrics_one_line_error)
                parsedRawLine() == null -> host.getString(R.string.ui_lyrics_raw_line_error)
                else -> null
            }
            lineText.showError(textError)
            val pending = hasPendingLineChanges()
            val lineValid = textError == null
            updateWords.isEnabled = pending && lineValid
            resetLine.isEnabled = pending
            updateWords.alpha = if (updateWords.isEnabled) 1f else 0.5f
            resetLine.alpha = if (resetLine.isEnabled) 1f else 0.5f
            fun enableWordEditing(view: View) {
                view.isEnabled = !pending
                if (view is ViewGroup) for (i in 0 until view.childCount) enableWordEditing(view.getChildAt(i))
            }
            enableWordEditing(tokenList)
            tokenList.alpha = if (pending) 0.5f else 1f
            addWord.isEnabled = !pending
            addWord.alpha = if (pending) 0.5f else 1f
            var previousStart = -1L
            tokens.forEach { fields ->
                val tokenStart = WordLyricsDocument.parseTime(fields.start.input.text.toString())
                val tokenEnd = WordLyricsDocument.parseTime(fields.end.input.text.toString())
                fields.text.showError(if (fields.text.input.text.isNullOrBlank())
                    host.getString(R.string.ui_lyrics_word_empty) else null)
                fields.start.showError(if (tokenStart == null) formatError else if (
                    tokenStart < previousStart) rangeError else null)
                fields.end.showError(if (tokenEnd == null) formatError else if (
                    tokenStart != null && tokenEnd <= tokenStart) rangeError else null)
                if (tokenStart != null) previousStart = tokenStart
            }
            hideErrorBubble(animate = false)
            val edited = editedLine()
            confirm.isEnabled = !pending && lineValid && edited != null &&
                WordLyricsDocument(listOf(edited)).invalidLines().isEmpty()
            confirm.alpha = if (confirm.isEnabled) 1f else 0.5f
        }
        validate()
        showForm(form)
    }

    private fun showForm(form: LinearLayout) {
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
        if (document.isWordByWord && !closing) {
            val insets = ViewCompat.getRootWindowInsets(sheet)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            val available = host.resources.displayMetrics.heightPixels -
                (insets?.top ?: 0) - (insets?.bottom ?: 0) - host.dp(24)
            val height = available.coerceIn(host.dp(80), host.dp(640)).coerceAtLeast(restingMinHeight)
            if (sheet.minimumHeight != height) sheet.minimumHeight = height
        }
        if (sheet.width == 0 || sheet.height == 0) return
        val params = editorCard.layoutParams as FrameLayout.LayoutParams
        val width = (sheet.width - host.dp(32)).coerceAtLeast(1).coerceAtMost(host.dp(304))
        if (params.width != width) {
            params.width = width
            editorCard.layoutParams = params
        }
        editorCard.maxHeightPx = (sheet.height - host.dp(24)).coerceAtLeast(host.dp(80))
        val source = anchor ?: return
        if (closing || !source.isAttachedToWindow) return
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
            sheet.minimumHeight = restingMinHeight
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
        sheet.minimumHeight = restingMinHeight
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
