package com.andsi.airlyrics.ui.pages.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.async.LatestUiTaskRunner
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.util.Locale

private val savedLyricsLoadRunner = LatestUiTaskRunner()
private val savedLyricsSearchWhitespace = Regex("\\s+")
private const val SEARCH_KEYBOARD_DELAY_MS = 80L
private const val SEARCH_FILTER_DELAY_MS = 100L

private data class SavedLyricsRow(
    val view: View,
    val searchableText: String
)

internal fun createSavedLyricsPage(activity: MainUiHost): View = with(activity) {
    val container = pageContainer(activity, animateChanges = false)
    val listBody = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }
    val listCard = card(activity) {
        addView(listBody)
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
    listBody.addView(loadingHint)
    listBody.addView(emptyHint)
    listBody.addView(noSearchResultsHint)

    var allLyrics = emptyList<LocalLyricsUiItem>()
    var savedLyricsRows = emptyList<SavedLyricsRow>()
    var lyricsLoaded = false
    lateinit var populateSavedLyrics: (Boolean) -> Unit

    val searchInput = EditText(activity).apply {
        setHint(R.string.ui_search_saved_lyrics_hint)
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorTextStrong)
        setHintTextColor(colorTextMuted)
        background = null
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(0, 0, 0, 0)
        setText(activity.savedLyricsSearchQuery)
        setSelection(text.length)
        layoutParams = LinearLayout.LayoutParams(
            0,
            dp(AirUiTokens.Layout.IconTouchSize),
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
        val terms = searchInput.text.toString()
            .trim()
            .lowercase(Locale.ROOT)
            .split(savedLyricsSearchWhitespace)
            .filter(String::isNotBlank)

        var visibleLyricsCount = 0
        savedLyricsRows.forEach { row ->
            val visible = lyricsLoaded && (
                terms.isEmpty() || terms.all { term -> row.searchableText.contains(term) }
            )
            row.view.isVisible = visible
            if (visible) visibleLyricsCount += 1
        }

        loadingHint.isVisible = !lyricsLoaded
        emptyHint.isVisible = lyricsLoaded && allLyrics.isEmpty()
        noSearchResultsHint.isVisible = lyricsLoaded &&
            allLyrics.isNotEmpty() &&
            visibleLyricsCount == 0
    }

    fun rebuildSavedLyricsRows() {
        savedLyricsRows = allLyrics.map { item ->
            SavedLyricsRow(
                view = localLyricsRow(item, onLyricsChanged = { _ ->
                    populateSavedLyrics(true)
                }),
                searchableText = item.savedLyricsSearchText()
            )
        }

        listBody.removeAllViews()
        listBody.addView(loadingHint)
        listBody.addView(emptyHint)
        listBody.addView(noSearchResultsHint)
        savedLyricsRows.forEach { row -> listBody.addView(row.view) }
        renderSavedLyrics()
    }

    val applySearchFilter = Runnable { renderSavedLyrics() }
    searchInput.doAfterTextChanged {
        activity.uiActions.updateSavedLyricsSearchQuery(it?.toString().orEmpty())
        listBody.removeCallbacks(applySearchFilter)
        listBody.postDelayed(applySearchFilter, SEARCH_FILTER_DELAY_MS)
    }

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
            renderSavedLyrics()
        }
        savedLyricsLoadRunner.submit(
            runtime = activity,
            load = { savedLyricsState() }
        ) { state ->
            allLyrics = state.lyrics
            lyricsLoaded = true
            rebuildSavedLyricsRows()
        }
    }

    container.addView(settingsBackHeader(
        title = getString(R.string.ui_saved_lyrics),
        titleAction = searchAction
    ))
    container.addView(searchBar)
    container.addView(listCard)
    populateSavedLyrics(false)
    lyricsSettingsContentRefresh = { populateSavedLyrics(true) }

    scroll(activity, container, animateChildren = false)
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
