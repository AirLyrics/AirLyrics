package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.AbsListView
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.setAirTopIcon
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.LocalLyricsUiChange
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.text.NumberFormat
import java.util.Locale

private val savedLyricsSearchWhitespace = Regex("\\s+")
private const val SEARCH_KEYBOARD_DELAY_MS = 80L
private const val SEARCH_FILTER_DELAY_MS = 100L

internal fun createSavedLyricsPage(activity: MainUiHost): View = with(activity) {
    val container = pageContainer(activity, animateChanges = false)
    val listBody = ListView(activity).apply {
        divider = null
        dividerHeight = 0
        cacheColorHint = Color.TRANSPARENT
        selector = Color.TRANSPARENT.toDrawable()
        clipToPadding = false
    }
    val listSurface = FrameLayout(activity).apply {
        addView(listBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    val listCard = card(activity) {
        addView(listSurface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    val listOverlay = FrameLayout(activity).apply {
        addView(listCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
    var deletionForNextRender = emptySet<String>()
    var deferSelectionUi = false
    lateinit var populateSavedLyrics: (Boolean) -> Unit
    lateinit var deletionRefresh: (Set<String>) -> Unit
    lateinit var updateSelectionUi: () -> Unit
    var refreshSelectAll: () -> Unit = {}
    lateinit var selectionAction: SavedLyricsSelectionIconView
    lateinit var selectionMotion: SavedLyricsSelectionMotion
    var dragSelectionLayout: SavedLyricsDragSelectionLayout? = null
    val selection = interactions.read("savedLyricsSelection")?.getStringArrayList("keys")?.toMutableSet() ?: mutableSetOf()
    var selecting = interactions.read("savedLyricsSelection") != null
    lateinit var selectionPanel: SavedLyricsSelectionPanel
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
        { change, key ->
            if (change == LocalLyricsUiChange.DELETED) deletionForNextRender = setOf(key)
            populateSavedLyrics(true)
        })
    listBody.adapter = adapter
    val deletionMotion = SavedLyricsDeletionMotion(activity, container, listBody, adapter)
    val scrollKey = "savedLyrics.list"
    var restoringScroll = true
    var listScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
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
            listScrollState = state
            if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) restoringScroll = false
            if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) deletionMotion.cancel()
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
            deletionMotion.cancel()
            if (activity.savedLyricsDeletionRefresh === deletionRefresh) activity.savedLyricsDeletionRefresh = null
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

    fun renderSavedLyrics(deletedKeys: Set<String> = emptySet()): Boolean {
        dragSelectionLayout?.cancelSelectionGesture()
        val terms = searchInput.text.toString()
            .trim()
            .lowercase(Locale.ROOT)
            .split(savedLyricsSearchWhitespace)
            .filter(String::isNotBlank)

        val visible = searchableLyrics.filter { (_, text) -> terms.all(text::contains) }.map { it.first }
        val deletionStarted = if (deletedKeys.isNotEmpty() &&
            listScrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        ) {
            deletionMotion.submit(visible, deletedKeys) { completed ->
                if (deferSelectionUi) {
                    deferSelectionUi = false
                    updateSelectionUi()
                }
                val hint = when {
                    emptyHint.isVisible -> emptyHint
                    noSearchResultsHint.isVisible -> noSearchResultsHint
                    else -> null
                }
                hint?.let {
                    it.animate().cancel()
                    if (completed && ValueAnimator.areAnimatorsEnabled()) {
                        it.animate().alpha(1f).translationY(0f).setDuration(190L)
                            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
                    } else {
                        it.alpha = 1f
                        it.translationY = 0f
                    }
                }
            }
        } else {
            if (adapter.items != visible) deletionMotion.cancel()
            adapter.submit(visible)
            false
        }
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
        val hint = when {
            emptyHint.isVisible -> emptyHint
            noSearchResultsHint.isVisible -> noSearchResultsHint
            else -> null
        }
        if (hint != null && deletionStarted) {
            hint.alpha = 0f
            hint.translationY = dp(8).toFloat()
        } else if (!deletionStarted && !deletionMotion.isActive) {
            listOf(emptyHint, noSearchResultsHint).forEach {
                it.animate().cancel()
                it.alpha = 1f
                it.translationY = 0f
            }
        }
        refreshSelectAll()
        return deletionStarted
    }

    fun toolbarLabel(label: String, icon: Int, tint: Int) = TextView(activity).apply {
        text = label
        textSize = AirUiTokens.TextSize.Tiny
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(tint)
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 2
        setPadding(0, dp(2), 0, dp(2))
        setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP)
        setAirTopIcon(activity, icon, tint, paddingDp = 3)
    }
    fun toolbarRipple(base: GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
        setColor(Color.TRANSPARENT)
    }) = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorAccent, 72)),
        base,
        GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(Color.WHITE)
        }
    )
    fun toolbarAction(label: String, icon: Int, tint: Int, action: () -> Unit) = toolbarLabel(label, icon, tint).apply {
        isFocusable = true
        background = toolbarRipple()
        enableSavedLyricsToolbarPressFeedback()
        setOnClickListener { action() }
    }
    val selectedLabel = TextView(activity).apply {
        text = getString(R.string.ui_saved_lyrics_toolbar_selected)
        textSize = AirUiTokens.TextSize.Tiny
        setTextColor(colorTextStrong)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(7, 11, 1, TypedValue.COMPLEX_UNIT_SP)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val selectedNumberFormat = NumberFormat.getIntegerInstance(resources.configuration.locales[0])
    val selectedCount = SavedLyricsSelectionCountView(activity, selection.size, selectedNumberFormat).apply {
        textSize = 19f
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 15, 19, 1, TypedValue.COMPLEX_UNIT_SP)
        minimumHeight = dp(32)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentText)
        includeFontPadding = false
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val selectedInfo = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), 0, dp(2), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = getString(R.string.ui_batch_selected, selection.size)
        addView(selectedLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(selectedCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val selectAll = toolbarAction(getString(R.string.ui_saved_lyrics_toolbar_select_all), R.drawable.ic_air_select_all, colorTextStrong) {
            renderSavedLyrics()
            val visibleKeys = adapter.keys
            if (visibleKeys.isNotEmpty() && visibleKeys.all { it in selection }) selection.removeAll(visibleKeys.toSet())
            else selection.addAll(visibleKeys)
            saveSelection()
            updateSelectionUi()
    }
    val selectAllOutgoing = toolbarLabel("", R.drawable.ic_air_select_all, colorTextStrong).apply {
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val selectAllSlot = FrameLayout(activity).apply {
        addView(selectAll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(selectAllOutgoing, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    var selectAllState: Boolean? = null
    var selectAllTransition: ValueAnimator? = null
    fun settleSelectAllTransition() {
        selectAllTransition?.removeAllListeners()
        selectAllTransition?.cancel()
        selectAllTransition = null
        selectAll.translationY = 0f
        selectAllOutgoing.visibility = View.GONE
        selectAllOutgoing.text = ""
        selectAllOutgoing.alpha = 0f
        selectAllOutgoing.translationY = 0f
    }
    refreshSelectAll = refresh@{
        val visibleKeys = adapter.keys
        val allSelected = visibleKeys.isNotEmpty() && visibleKeys.all { it in selection }
        val enabled = selecting && visibleKeys.isNotEmpty()
        val previousState = selectAllState
        if (previousState == allSelected && selectAllTransition != null && enabled) return@refresh
        val useOutgoing = selectAllTransition != null &&
            selectAllOutgoing.isVisible && selectAllOutgoing.alpha > selectAll.alpha
        val visualState = if (useOutgoing) previousState?.not() else previousState
        val visualText = if (useOutgoing) selectAllOutgoing.text else selectAll.text
        val canAnimate = visualState != null && visualState != allSelected && enabled &&
            selectAll.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled() && selectionPanel.expanded
        settleSelectAllTransition()
        if (canAnimate) {
            selectAllOutgoing.text = visualText
            selectAllOutgoing.setAirTopIcon(activity,
                if (visualState) R.drawable.ic_air_deselect_all else R.drawable.ic_air_select_all,
                colorTextStrong, paddingDp = 3)
        }
        selectAllState = allSelected
        selectAll.text = getString(if (allSelected) R.string.ui_saved_lyrics_toolbar_deselect else R.string.ui_saved_lyrics_toolbar_select_all)
        selectAll.setAirTopIcon(activity,
            if (allSelected) R.drawable.ic_air_deselect_all else R.drawable.ic_air_select_all,
            colorTextStrong, paddingDp = 3)
        selectAll.contentDescription = getString(if (allSelected) R.string.ui_batch_deselect_all else R.string.ui_select_all)
        selectAll.isEnabled = enabled
        if (canAnimate) {
            val distance = dp(4).toFloat()
            val direction = if (allSelected) 1f else -1f
            val next = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 270L
                interpolator = PathInterpolator(0.25f, 0f, 0.2f, 1f)
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    selectAll.alpha = fraction
                    selectAll.translationY = direction * distance * (1f - fraction)
                    selectAllOutgoing.alpha = 1f - fraction
                    selectAllOutgoing.translationY = -direction * distance * fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (selectAllTransition !== animation) return
                        settleSelectAllTransition()
                        selectAll.alpha = 1f
                    }
                })
            }
            selectAll.alpha = 0f
            selectAll.translationY = direction * distance
            selectAllOutgoing.alpha = 1f
            selectAllOutgoing.visibility = View.VISIBLE
            selectAllTransition = next
            next.start()
        } else {
            selectAll.alpha = if (enabled) 1f else AirUiTokens.Button.DisabledAlpha
        }
    }
    val importAction = toolbarAction(getString(R.string.ui_saved_lyrics_toolbar_import), R.drawable.ic_air_download, colorTextStrong) {
            manageSavedLyrics(allLyrics.filter { itemKey(it) in selection }, delete = false)
    }.apply { contentDescription = getString(R.string.ui_batch_replace) }
    val exportAction = toolbarAction(getString(R.string.ui_batch_export), R.drawable.ic_air_upload, colorTextStrong) {
            exportSavedLyrics(allLyrics.filter { itemKey(it) in selection })
    }
    val deleteAction = toolbarAction(getString(R.string.ui_delete), R.drawable.ic_air_delete, colorDanger) {
            manageSavedLyrics(allLyrics.filter { itemKey(it) in selection }, delete = true)
    }.apply {
        background = toolbarRipple(GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(ColorUtils.setAlphaComponent(colorDanger, 38))
        })
    }
    val toolbarActions = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        for (action in listOf(selectAllSlot, importAction, exportAction, deleteAction)) {
            addView(action, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }
    var selectionScrollPadding = dp(80)
    val selectionBar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(selectedInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(View(activity).apply {
            setBackgroundColor(ColorUtils.setAlphaComponent(colorTextMuted, 65))
        }, LinearLayout.LayoutParams(dp(1), dp(32)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(toolbarActions, LinearLayout.LayoutParams(dp(192), ViewGroup.LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val padding = bottom - top + dp(24)
            if (padding != selectionScrollPadding) {
                selectionScrollPadding = padding
                if (selecting) listBody.setPadding(0, 0, 0, padding)
            }
        }
    }
    val selectionToggle = airIconView(
        R.drawable.ic_air_chevron_right,
        colorTextStrong,
        getString(R.string.ui_saved_lyrics_toolbar_collapse)
    ).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
    }
    selectionPanel = SavedLyricsSelectionPanel(activity, selectionBar, selectionToggle, toolbarActions) {
        selectedCount.finishAnimation()
        settleSelectAllTransition()
        selectAll.alpha = if (selectAll.isEnabled) 1f else AirUiTokens.Button.DisabledAlpha
    }
    listOverlay.addView(selectionPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    updateSelectionUi = {
        selectionAction.isSelected = selecting
        selectionAction.contentDescription = getString(
            if (selecting) R.string.ui_exit_multi_select else R.string.ui_multi_select
        )
        selectionAction.isEnabled = selecting || (lyricsLoaded && allLyrics.isNotEmpty())
        selectionAction.alpha = if (selectionAction.isEnabled) 1f else 0.5f
        if (selecting) {
            if (selectedCount.count != selection.size) {
                selectedCount.setCount(selection.size,
                    animate = selectionPanel.expanded && selectionPanel.isVisible)
                selectedInfo.contentDescription = getString(R.string.ui_batch_selected, selection.size)
            }
        } else {
            selectedCount.finishAnimation()
        }
        selectionPanel.importantForAccessibility = if (selecting) View.IMPORTANT_FOR_ACCESSIBILITY_NO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        refreshSelectAll()
        for ((index, action) in listOf(importAction, exportAction, deleteAction).withIndex()) {
            val enabled = selecting && selection.isNotEmpty() && (index != 0 || allLyrics.any {
                itemKey(it) in selection && it.indexKey.isNotBlank() && it.title.isNotBlank()
            })
            action.apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.5f }
        }
        val bottomPadding = if (selecting) selectionScrollPadding else 0
        if (listBody.paddingBottom != bottomPadding) listBody.setPadding(0, 0, 0, bottomPadding)
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
        clipToOutline = true
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
            val wasSelecting = selecting
            selecting = interactions.read("savedLyricsSelection") != null
            if (!selecting) selection.clear()
            selection.retainAll(allLyrics.map(::itemKey).toSet())
            saveSelection()
            lyricsLoaded = true
            listBody.setTag(R.id.interaction_loading, false)
            val deleted = deletionForNextRender
            deletionForNextRender = emptySet()
            deferSelectionUi = (wasSelecting && !selecting && deleted.isNotEmpty()) ||
                (deferSelectionUi && deletionMotion.isActive)
            val animated = renderSavedLyrics(deleted)
            if (deferSelectionUi && (animated || deletionMotion.isActive)) {
                adapter.refresh(listBody, animate = false)
            } else {
                deferSelectionUi = false
                updateSelectionUi()
            }
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
                selectionPanel.setExpanded(true, animate = false)
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
    container.addView(listOverlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    deletionRefresh = { keys ->
        deletionForNextRender = keys
        populateSavedLyrics(true)
    }
    activity.savedLyricsDeletionRefresh = deletionRefresh
    populateSavedLyrics(false)
    lyricsSettingsContentRefresh = {
        if (activity.savedLyricsDeletionPending == null) populateSavedLyrics(true)
    }

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
        itemKeys = { adapter.keys },
        excludeTouch = selectionPanel::containsControl
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
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun View.enableSavedLyricsToolbarPressFeedback() {
    setOnTouchListener { view, event ->
        if (!view.isEnabled || !ValueAnimator.areAnimatorsEnabled()) {
            view.animate().cancel()
            view.scaleX = 1f
            view.scaleY = 1f
            return@setOnTouchListener false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().cancel()
                view.animate().scaleX(0.94f).scaleY(0.94f)
                    .setDuration(140L).setInterpolator(DecelerateInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().cancel()
                view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(200L).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        false
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
