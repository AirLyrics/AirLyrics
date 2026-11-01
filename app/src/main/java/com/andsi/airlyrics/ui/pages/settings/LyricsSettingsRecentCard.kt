package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.andsi.airlyrics.ui.components.adaptiveHeader
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.LocalLyricsRowView
import com.andsi.airlyrics.ui.model.CurrentMediaUiInfo
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.LocalLyricsUiChange
import com.andsi.airlyrics.ui.model.RecentLyricsUiState
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong


internal fun createRecentLyricsCard(activity: MainUiHost): RefreshableSettingsCard = with(activity) {
    val listBody = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val feedback = TextView(this).apply {
        text = ""
        textSize = AirUiTokens.TextSize.Caption
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentText)
        gravity = Gravity.CENTER_VERTICAL
    }
    var closeHeaderHint: () -> Unit = {}
    var cardView: LinearLayout? = null
    var renderedState: RecentLyricsUiState? = null
    var currentKey: String? = null
    var currentView: View? = null
    var currentItemBinding: LocalLyricsUiItem? = null
    var fallbackMediaText: TextView? = null
    var recentRows = emptyMap<String, Pair<LocalLyricsUiItem, LocalLyricsRowView>>()
    val loadingView = normalText(activity, getString(R.string.ui_loading))
    val emptyHint = normalText(activity, getString(R.string.ui_recent_lyrics_empty_hint))
    listBody.addView(loadingView)

    lateinit var populateRecentLyrics: (Boolean, Boolean) -> Unit

    fun createRow(item: LocalLyricsUiItem, badgeText: String? = null): LocalLyricsRowView =
        localLyricsRow(item, onLyricsChanged = { change ->
            closeHeaderHint()
            populateRecentLyrics(false, false)
            if (change == LocalLyricsUiChange.SAVED) {
                playLocalRefreshFeedback(activity, listBody, feedback, getString(R.string.ui_applied), animateTarget = false)
            }
        }, badgeText = badgeText) as LocalLyricsRowView

    fun createFallback(media: CurrentMediaUiInfo?): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(AirUiTokens.Space.Xxl), 0, 0)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        addView(TextView(activity).apply {
            setText(R.string.ui_now_playing)
            textSize = AirUiTokens.TextSize.Tiny
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentText)
            setPadding(0, 0, 0, dp(AirUiTokens.Space.Sm))
        })
        addView(TextView(activity).apply {
            text = media?.displayText ?: getString(R.string.ui_no_active_media_found_short)
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
            fallbackMediaText = this
        })
        addView(TextView(activity).apply {
            setText(R.string.ui_no_plain_lrc_bound_to_song)
            textSize = AirUiTokens.TextSize.Caption
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        })
    }

    val viewAll = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            closeHeaderHint()
            uiActions.openSettingsSubPage(SettingsSubPage.SAVED_LYRICS)
        }
        addView(TextView(activity).apply {
            setText(R.string.ui_view_all)
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentText)
        })
        addView(airIconView(R.drawable.ic_air_chevron_right, colorAccentText).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.IconSize), dp(AirUiTokens.Layout.IconSize)).apply {
                setMargins(dp(AirUiTokens.Space.Sm), 0, 0, 0)
            }
        })
    }

    fun renderLyricsList(state: RecentLyricsUiState, showRefreshFeedback: Boolean) {
        val currentItem = state.currentItem
        val recentLyrics = state.recentLyrics
        val previous = renderedState
        fun refreshMessage(): String = if (recentLyrics.isNotEmpty()) {
            getString(R.string.ui_refreshed) + " " + recentLyrics.size + " " + getString(R.string.ui_songs)
        } else getString(R.string.ui_refreshed)
        if (previous == state) {
            if (showRefreshFeedback) {
                playLocalRefreshFeedback(activity, listBody, feedback, refreshMessage(), animateTarget = false)
            }
            return
        }

        val otherRecentLyrics = recentLyrics.filterNot { item ->
            if (currentItem == null) {
                false
            } else if (currentItem.indexKey.isNotBlank() && item.indexKey.isNotBlank()) {
                currentItem.indexKey == item.indexKey
            } else {
                currentItem.name == item.name
            }
        }
        val nextCurrentKey = currentItem?.lyricsRowKey()
        val nextRecentKeys = otherRecentLyrics.map(LocalLyricsUiItem::lyricsRowKey)
        val previousRecentKeys = recentRows.keys.toList()
        val fallbackChanged = currentItem == null && previous?.currentItem == null &&
            previous?.media?.displayText != state.media?.displayText
        val primaryChanged = currentKey != nextCurrentKey || fallbackChanged
        val contentChanged = previous != null && (primaryChanged || previousRecentKeys != nextRecentKeys)
        if (contentChanged) beginLyricsCardContentTransition(cardView)
        else if (previous != null) beginLyricsCardLayoutTransition(cardView)

        val nextCurrentView = if (currentItem != null) {
            if (currentKey == nextCurrentKey && currentView is LocalLyricsRowView) {
                (currentView as LocalLyricsRowView).also { if (currentItemBinding != currentItem) it.bind(currentItem) }
            } else {
                createRow(currentItem, getString(R.string.ui_now_playing))
            }
        } else if (!fallbackChanged && currentKey == null && currentView is LinearLayout) {
            fallbackMediaText?.text = state.media?.displayText ?: getString(R.string.ui_no_active_media_found_short)
            currentView as LinearLayout
        } else {
            createFallback(state.media)
        }
        if (contentChanged && currentView !== nextCurrentView) currentView?.animate()?.cancel()
        currentKey = nextCurrentKey
        currentView = nextCurrentView
        currentItemBinding = currentItem

        val nextRows = LinkedHashMap<String, Pair<LocalLyricsUiItem, LocalLyricsRowView>>()
        otherRecentLyrics.forEach { item ->
            val key = item.lyricsRowKey()
            val old = recentRows[key]
            val row = old?.let { previousRow ->
                previousRow.second.also { if (previousRow.first != item) it.bind(item) }
            } ?: createRow(item)
            nextRows[key] = item to row
        }
        recentRows = nextRows

        val desiredViews = buildList {
            add(nextCurrentView)
            if (currentItem == null && otherRecentLyrics.isEmpty()) add(emptyHint)
            nextRows.values.forEach { add(it.second) }
            if (currentItem != null || recentLyrics.isNotEmpty()) add(viewAll)
        }
        if (viewAll in desiredViews) {
            viewAll.isClickable = true
            viewAll.isFocusable = true
            viewAll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        for (index in 0 until listBody.childCount) {
            val oldView = listBody.getChildAt(index)
            if (oldView !in desiredViews) {
                oldView.isClickable = false
                oldView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        desiredViews.forEachIndexed { index, view ->
            if (listBody.getChildAt(index) !== view) {
                if (view.parent === listBody) listBody.removeView(view)
                listBody.addView(view, index)
            }
        }
        while (listBody.childCount > desiredViews.size) {
            listBody.getChildAt(listBody.childCount - 1).animate().cancel()
            listBody.removeViewAt(listBody.childCount - 1)
        }
        renderedState = state
        if (contentChanged && primaryChanged && ValueAnimator.areAnimatorsEnabled()) {
            nextCurrentView.translationY = dp(6).toFloat()
            nextCurrentView.animate().translationY(0f).setDuration(180L).start()
        }

        if (showRefreshFeedback) {
            playLocalRefreshFeedback(activity, listBody, feedback, refreshMessage(), animateTarget = false)
        }
    }

    populateRecentLyrics = { showRefreshFeedback, preserveContent ->
        if (showRefreshFeedback) {
            showInlineRefreshFeedback(feedback, getString(R.string.ui_refreshing))
        } else if (!preserveContent && renderedState == null) {
            loadingView.text = getString(R.string.ui_loading)
        }

        listBody.setTag(R.id.interaction_loading, true)
        loadRecentLyrics(force = showRefreshFeedback) { state ->
            listBody.setTag(R.id.interaction_loading, false)
            renderLyricsList(state, showRefreshFeedback)
        }
    }

    populateRecentLyrics(false, false)

    val createdCard = card(activity) {
        val hintText = TextView(activity).apply {
            setText(R.string.ui_tap_to_preview_or_edit)
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextMuted)
            alpha = 0f
            isVisible = false
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        }
        closeHeaderHint = {
            if (hintText.isVisible || hintText.alpha > 0f) {
                hintText.animate().cancel()
                hintText.alpha = 0f
                hintText.isVisible = false
            }
        }

        val help = airIconView(R.drawable.ic_air_info, colorTextMuted, getString(R.string.ui_recent_local_lyrics)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.CompactIconButtonSize), dp(AirUiTokens.Layout.CompactIconButtonSize))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSurfaceLight)
                setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
            }
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setOnClickListener {
                if (hintText.isVisible) closeHeaderHint() else {
                    hintText.isVisible = true
                    hintText.animate().alpha(1f).setDuration(AirUiTokens.Motion.FeedbackInMs).start()
                }
            }
        }
        val refresh = airIconView(R.drawable.ic_air_refresh, colorAccentText, getString(R.string.ui_refresh_media_status)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.IconTouchSize), dp(AirUiTokens.Layout.IconTouchSize))
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setOnClickListener {
                closeHeaderHint()
                animate().rotationBy(360f).setDuration(AirUiTokens.Motion.RefreshSpinMs).start()
                populateRecentLyrics(true, true)
            }
        }
        val icons = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(help)
            addView(refresh)
        }
        addView(adaptiveHeader(bigText(activity, getString(R.string.ui_recent_local_lyrics)), feedback, icons))
        addView(hintText)
        addView(listBody)
    }
    cardView = createdCard
    return RefreshableSettingsCard(
        view = createdCard,
        refreshContent = { populateRecentLyrics(false, true) }
    )
}

private fun LocalLyricsUiItem.lyricsRowKey(): String = indexKey.ifBlank { "root:$name" }
