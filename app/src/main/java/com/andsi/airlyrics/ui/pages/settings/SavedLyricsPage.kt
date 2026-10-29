package com.andsi.airlyrics.ui.pages.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.AbsListView
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.util.Locale

private val savedLyricsSearchWhitespace = Regex("\\s+")
private const val SEARCH_KEYBOARD_DELAY_MS = 80L
private const val SEARCH_FILTER_DELAY_MS = 100L

internal fun createSavedLyricsPage(activity: MainUiHost): View = with(activity) {
    val container = pageContainer(activity, animateChanges = false)
    val listBody = ListView(activity).apply {
        divider = null
        dividerHeight = 0
        cacheColorHint = android.graphics.Color.TRANSPARENT
        selector = android.graphics.Color.TRANSPARENT.toDrawable()
        clipToPadding = false
    }
    val listCard = card(activity) {
        addView(listBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    val loadingHint = normalText(activity, getString(R.string.ui_loading))
    val emptyHint = normalText(activity, getString(R.string.ui_saved_lyrics_empty_hint)).apply {
        isVisible = false
    }
    val noSearchResultsHint = normalText(
        activity,
        getString(R.string.ui_saved_lyrics_no_search_results)
    ).apply {
        isVisible = false
    }
    var allLyrics = emptyList<LocalLyricsUiItem>()
    var searchableLyrics = emptyList<Pair<LocalLyricsUiItem, String>>()
    var lyricsLoaded = false
    lateinit var populateSavedLyrics: (Boolean) -> Unit
    lateinit var updateSelectionUi: () -> Unit
    var refreshSelectAll: () -> Unit = {}
    lateinit var selectionAction: SavedLyricsSelectionIconView
    lateinit var selectionMotion: SavedLyricsSelectionMotion
    var dragSelectionLayout: SavedLyricsDragSelectionLayout? = null
    val selection = interactions.read("savedLyricsSelection")?.getStringArrayList("keys")?.toMutableSet() ?: mutableSetOf()
    var selecting = interactions.read("savedLyricsSelection") != null
    val selectionBar = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.PageH), 0, dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.Lg))
    }
    val selectionPanel = SavedLyricsSelectionPanel(activity, selectionBar)
    fun itemKey(item: LocalLyricsUiItem) = item.selectionKey()
    fun saveSelection() {
        if (selecting) interactions.write("savedLyricsSelection") { putStringArrayList("keys", ArrayList(selection)) }
        else interactions.remove("savedLyricsSelection")
    }
    fun toggleSelection(item: LocalLyricsUiItem) {
        if (!selecting) return
        if (!selection.add(itemKey(item))) selection.remove(itemKey(item))
        saveSelection()
        updateSelectionUi()
    }
    fun endSelection(): Boolean {
        if (!selecting) return false
        selecting = false
        dragSelectionLayout?.cancelSelectionGesture()
        selection.clear()
        saveSelection()
        updateSelectionUi()
        return true
    }
    val adapter = SavedLyricsListAdapter(activity, { selecting }, { it in selection }, ::toggleSelection,
        { populateSavedLyrics(true) })
    listBody.adapter = adapter
    val scrollKey = "savedLyrics.list"
    var restoringScroll = true
    val savedScroll = interactions.read(scrollKey)
    fun captureScroll() {
        if (restoringScroll || !listBody.isAttachedToWindow || adapter.count == 0) return
        val first = listBody.firstVisiblePosition
        val item = adapter.items.getOrNull(first) ?: return
        interactions.write(scrollKey) {
            putString("key", itemKey(item))
            putInt("position", first)
            putFloat("offset", (listBody.getChildAt(0)?.top ?: 0) / resources.displayMetrics.density)
        }
    }
    listBody.setOnScrollListener(object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView?, state: Int) {
            if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) restoringScroll = false
        }
        override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = captureScroll()
    })
    interactionUi.snapshot(listBody, ::captureScroll)
    val backHandler: () -> Boolean = { endSelection() }
    activity.savedLyricsSelectionBackHandler = backHandler
    container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            activity.savedLyricsSelectionBackHandler = backHandler
            interactionUi.snapshot(listBody, ::captureScroll)
        }
        override fun onViewDetachedFromWindow(view: View) {
            interactionUi.forgetSnapshot(listBody)
            if (activity.savedLyricsSelectionBackHandler === backHandler) activity.savedLyricsSelectionBackHandler = null
        }
    })

    val searchInput = EditText(activity).apply {
        setHint(R.string.ui_search_saved_lyrics_hint)
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorTextStrong)
        setHintTextColor(colorTextMuted)
        background = null
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(0, 0, 0, 0)
        setText(activity.savedLyricsSearchQuery)
        setSelection(text.length)
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(dp(AirUiTokens.Space.Xl), 0, 0, 0)
        }
        setOnEditorActionListener { view, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                false
            } else {
                view.clearFocus()
                val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
                true
            }
        }
    }

    fun renderSavedLyrics() {
        dragSelectionLayout?.cancelSelectionGesture()
        val terms = searchInput.text.toString()
            .trim()
            .lowercase(Locale.ROOT)
            .split(savedLyricsSearchWhitespace)
            .filter(String::isNotBlank)

        val visible = searchableLyrics.filter { (_, text) -> terms.all(text::contains) }.map { it.first }
        adapter.submit(visible)
        if (lyricsLoaded && restoringScroll) {
            val index = visible.indexOfFirst { itemKey(it) == savedScroll?.getString("key") }
            val position = if (index >= 0) index else (savedScroll?.getInt("position") ?: 0).coerceIn(0, (visible.size - 1).coerceAtLeast(0))
            // Apply after the adapter's initial layout; AbsListView's data-change handling can
            // otherwise replace this request with its default position before any rows exist.
            listBody.post {
                listBody.setSelectionFromTop(position, ((savedScroll?.getFloat("offset") ?: 0f) * resources.displayMetrics.density).toInt())
                listBody.post { restoringScroll = false }
            }
        }

        loadingHint.isVisible = !lyricsLoaded
        emptyHint.isVisible = lyricsLoaded && allLyrics.isEmpty()
        noSearchResultsHint.isVisible = lyricsLoaded &&
            allLyrics.isNotEmpty() &&
            visible.isEmpty()
        refreshSelectAll()
    }

    fun batchButtons(vararg buttons: Pair<String, () -> Unit>): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        setPadding(0, dp(AirUiTokens.Space.Lg), 0, 0)
        buttons.forEachIndexed { index, (label, action) ->
            addView(TextView(activity).apply {
                text = label
                textSize = AirUiTokens.TextSize.Button
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = dp(AirUiTokens.Layout.IconTouchSize)
                setPadding(dp(AirUiTokens.Space.Sm), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Sm), dp(AirUiTokens.Space.Lg))
                enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = dp(AirUiTokens.Space.Lg)
            })
        }
    }
    fun styleButton(button: TextView, tone: AirTonalButtonTone) {
        activity.styleAirTonalButton(button, tone)
    }
    val selectedCount = normalText(activity, getString(R.string.ui_batch_selected, selection.size))
    val selectionControls = batchButtons(
        getString(R.string.ui_select_all) to {
            renderSavedLyrics()
            val visibleKeys = adapter.keys
            if (visibleKeys.isNotEmpty() && visibleKeys.all { it in selection }) selection.removeAll(visibleKeys.toSet())
            else selection.addAll(visibleKeys)
            saveSelection()
            updateSelectionUi()
        },
        getString(R.string.ui_cancel) to { endSelection() }
    )
    styleButton(selectionControls.getChildAt(0) as TextView, AirTonalButtonTone.DEFAULT)
    styleButton(selectionControls.getChildAt(1) as TextView, AirTonalButtonTone.DEFAULT)
    refreshSelectAll = {
        val visibleKeys = adapter.keys
        (selectionControls.getChildAt(0) as TextView).apply {
            text = getString(if (visibleKeys.isNotEmpty() && visibleKeys.all { it in selection })
                R.string.ui_batch_deselect_all else R.string.ui_select_all)
            isEnabled = selecting && visibleKeys.isNotEmpty()
            alpha = if (isEnabled) 1f else AirUiTokens.Button.DisabledAlpha
        }
    }
    val selectionActions = batchButtons(
        getString(R.string.ui_batch_replace) to {
            manageSavedLyrics(allLyrics.filter { itemKey(it) in selection }, delete = false)
        },
        getString(R.string.ui_batch_export) to {
            exportSavedLyrics(allLyrics.filter { itemKey(it) in selection })
        },
        getString(R.string.ui_delete) to {
            manageSavedLyrics(allLyrics.filter { itemKey(it) in selection }, delete = true)
        }
    )
    for (index in 0 until selectionActions.childCount) {
        styleButton(
            selectionActions.getChildAt(index) as TextView,
            if (index == 2) AirTonalButtonTone.DANGER else AirTonalButtonTone.SELECTED
        )
    }
    selectionBar.addView(selectedCount)
    selectionBar.addView(selectionControls)
    selectionBar.addView(selectionActions)

    updateSelectionUi = {
        selectionAction.isSelected = selecting
        selectionAction.contentDescription = getString(
            if (selecting) R.string.ui_exit_multi_select else R.string.ui_multi_select
        )
        selectionAction.isEnabled = selecting || (lyricsLoaded && allLyrics.isNotEmpty())
        selectionAction.alpha = if (selectionAction.isEnabled) 1f else 0.5f
        if (selecting) selectedCount.text = getString(R.string.ui_batch_selected, selection.size)
        selectionPanel.importantForAccessibility = if (selecting) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        for (index in 0 until selectionControls.childCount) {
            selectionControls.getChildAt(index).apply {
                isEnabled = selecting
                alpha = if (isEnabled) 1f else 0.5f
            }
        }
        refreshSelectAll()
        for (index in 0 until selectionActions.childCount) {
            val enabled = selecting && selection.isNotEmpty() && (index != 0 || allLyrics.any {
                itemKey(it) in selection && it.indexKey.isNotBlank() && it.title.isNotBlank()
            })
            selectionActions.getChildAt(index).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.5f }
        }
        // Update interaction immediately, while the visual state may still be reversing.
        adapter.progress = selectionMotion.progress
        adapter.refresh(listBody)
        selectionMotion.moveTo(if (selecting) 1f else 0f)
    }
    val applySearchFilter = Runnable { renderSavedLyrics() }

    val searchBarBottomMargin = dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
    val searchBar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(
            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
            0,
            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
            0
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, searchBarBottomMargin)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        addView(airIconView(
            iconRes = R.drawable.ic_air_search,
            tint = colorTextMuted
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(AirUiTokens.Layout.IconSize),
                dp(AirUiTokens.Layout.IconSize)
            )
        })
        addView(searchInput)
    }

    var searchOpen = activity.savedLyricsSearchOpen
    lateinit var searchAction: SavedLyricsSearchIconView
    lateinit var searchTransition: SavedLyricsSearchTransition
    val showSearchKeyboard = Runnable {
        if (searchOpen && searchInput.isAttachedToWindow && searchInput.requestFocus()) {
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.showSoftInput(searchInput, 0)
        }
    }
    searchInput.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            searchInput.removeCallbacks(showSearchKeyboard)
            listBody.removeCallbacks(applySearchFilter)
        }
    })

    fun clearCollapsedSearchQuery() {
        if (!searchOpen && searchInput.text?.isNotEmpty() == true) {
            searchInput.text?.clear()
            listBody.removeCallbacks(applySearchFilter)
            renderSavedLyrics()
        }
    }

    fun setSearchOpen(open: Boolean) {
        searchOpen = open
        activity.uiActions.setSavedLyricsSearchOpen(open)
        if (open) {
            activity.uiActions.updateSavedLyricsSearchQuery(
                searchInput.text?.toString().orEmpty()
            )
        }
        searchAction.contentDescription = getString(
            if (open) R.string.ui_close_search else R.string.ui_search
        )
        searchTransition.setExpanded(open, animate = true) {
            if (!open) clearCollapsedSearchQuery()
        }
        searchInput.removeCallbacks(showSearchKeyboard)
        if (open) {
            searchInput.postDelayed(showSearchKeyboard, SEARCH_KEYBOARD_DELAY_MS)
        } else {
            searchInput.clearFocus()
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
    }

    searchAction = SavedLyricsSearchIconView(
        context = activity,
        color = colorAccent,
        initiallyClose = searchOpen
    ).apply {
        contentDescription = getString(
            if (searchOpen) R.string.ui_close_search else R.string.ui_search
        )
        layoutParams = LinearLayout.LayoutParams(
            dp(AirUiTokens.Layout.IconTouchSize),
            dp(AirUiTokens.Layout.IconTouchSize)
        ).apply {
            setMargins(dp(AirUiTokens.Space.Xl), 0, 0, 0)
        }
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            setSearchOpen(!searchOpen)
        }
    }
    searchTransition = SavedLyricsSearchTransition(
        host = activity,
        icon = searchAction,
        searchBar = searchBar,
        expandedBottomMargin = searchBarBottomMargin,
        initiallyExpanded = searchOpen
    )

    populateSavedLyrics = { preserveContent ->
        if (!preserveContent) {
            lyricsLoaded = false
            listBody.setTag(R.id.interaction_loading, true)
            renderSavedLyrics()
        }
        loadSavedLyrics(force = preserveContent) { state ->
            allLyrics = state.lyrics
            searchableLyrics = allLyrics.map { it to it.savedLyricsSearchText() }
            selecting = interactions.read("savedLyricsSelection") != null
            if (!selecting) selection.clear()
            selection.retainAll(allLyrics.map(::itemKey).toSet())
            saveSelection()
            lyricsLoaded = true
            listBody.setTag(R.id.interaction_loading, false)
            renderSavedLyrics()
            updateSelectionUi()
        }
    }

    selectionAction = SavedLyricsSelectionIconView(activity, colorAccent).apply {
        layoutParams = LinearLayout.LayoutParams(
            dp(AirUiTokens.Layout.IconTouchSize), dp(AirUiTokens.Layout.IconTouchSize)
        )
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            if (!endSelection()) {
                selecting = true
                saveSelection()
                updateSelectionUi()
            }
        }
    }
    selectionMotion = SavedLyricsSelectionMotion(selectionAction, if (selecting) 1f else 0f) { progress ->
        selectionAction.progress = progress
        selectionPanel.reveal(progress)
        adapter.progress = progress
        adapter.refresh(listBody)
    }
    updateSelectionUi()
    container.addView(settingsBackHeader(
        title = getString(R.string.ui_saved_lyrics),
        titleAction = selectionAction,
        inlineTitleAction = searchAction
    ))
    container.addView(searchBar)
    container.addView(loadingHint)
    container.addView(emptyHint)
    container.addView(noSearchResultsHint)
    container.addView(listCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    populateSavedLyrics(false)
    lyricsSettingsContentRefresh = { populateSavedLyrics(true) }

    // Keep the original 760 dp content limit without an outer ScrollView: the list must
    // receive a bounded viewport so it can recycle rows instead of measuring every song.
    val content = object : FrameLayout(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            container.measure(MeasureSpec.makeMeasureSpec(width.coerceAtMost(dp(760)), MeasureSpec.EXACTLY), heightMeasureSpec)
            setMeasuredDimension(width, container.measuredHeight)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val x = (width - container.measuredWidth) / 2
            container.layout(x, 0, x + container.measuredWidth, container.measuredHeight)
        }
    }.apply { addView(container) }

    SavedLyricsDragSelectionLayout(activity, listBody,
        selectionEnabled = { selecting },
        items = { adapter.attachedItems(listBody) },
        selectedKeys = { selection.toSet() },
        select = { keys ->
            if (selecting && selection != keys) {
                selection.clear()
                selection.addAll(keys)
                saveSelection()
                updateSelectionUi()
            }
        },
        itemKeys = { adapter.keys }
    ).apply {
        dragSelectionLayout = this
        searchInput.doAfterTextChanged {
            cancelSelectionGesture()
            activity.uiActions.updateSavedLyricsSearchQuery(it?.toString().orEmpty())
            listBody.removeCallbacks(applySearchFilter)
            listBody.postDelayed(applySearchFilter, SEARCH_FILTER_DELAY_MS)
        }
        orientation = LinearLayout.VERTICAL
        addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addView(selectionPanel)
    }
}

private fun LocalLyricsUiItem.savedLyricsSearchText(): String {
    return listOf(
        displayTitle,
        title,
        artist,
        subtitle,
        typeText,
        source,
        provider,
        metaText
    ).joinToString(separator = "\n").lowercase(Locale.ROOT)
}
