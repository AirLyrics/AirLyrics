package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.andsi.airlyrics.ui.components.TextDisplayPolicy
import com.andsi.airlyrics.ui.components.AdaptiveLabelValueLayout
import com.andsi.airlyrics.ui.components.ExpandableTextLayout
import com.andsi.airlyrics.ui.components.expandableText
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.settingRow
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.state.showPersistentInfo
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.CurrentLyricsUiState
import com.andsi.airlyrics.ui.model.LyricsDeleteMode
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens


internal fun createCurrentLyricsCard(activity: MainUiHost): RefreshableSettingsCard = with(activity) {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val feedback = TextView(this).apply {
        text = ""
        textSize = AirUiTokens.TextSize.Caption
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccentText)
        gravity = Gravity.CENTER_VERTICAL
    }
    var cardView: LinearLayout? = null
    var renderedState: CurrentLyricsUiState? = null
    var detailsView: LinearLayout? = null
    var detailTexts: CurrentLyricsDetailTexts? = null
    val loadingView = normalText(activity, getString(R.string.ui_loading))
    body.addView(loadingView)

    fun confirmDeleteLyrics(label: String, mode: LyricsDeleteMode, message: String? = null) {
        val state = renderedState ?: return
        val media = state.media ?: return
        activity.confirmOperation(
            action = ConfirmationAction.DeleteCurrent(state.operationTarget ?: return, mode),
            title = label,
            message = message?.let { media.displayText + "\n\n" + it } ?: media.displayText,
            positiveText = getString(R.string.ui_remove)
        )
    }

    val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(actionButton(activity, getString(R.string.search_lyrics)) { uiActions.openLyricsSearch() })
        addView(actionButton(activity, getString(R.string.ui_import_lyrics_for_current_song)) { uiActions.importLyricsForCurrentMedia() })
        addView(actionButton(activity, getString(R.string.ui_remove_plain_lrc)) {
            confirmDeleteLyrics(getString(R.string.ui_remove_plain_lrc_confirm), LyricsDeleteMode.PLAIN)
        })
        addView(actionButton(activity, getString(R.string.ui_remove_word_by_word_lyrics)) {
            confirmDeleteLyrics(
                getString(R.string.ui_remove_word_by_word_lyrics_confirm),
                LyricsDeleteMode.WORD_BY_WORD,
                getString(R.string.ui_remove_word_by_word_lyrics_message)
            )
        })
        addView(actionButton(activity, getString(R.string.ui_remove_all_lyrics)) {
            confirmDeleteLyrics(getString(R.string.ui_remove_all_lyrics_confirm), LyricsDeleteMode.ALL)
        })
    }
    body.addView(actions)

    fun wordByWordSummaryRes(state: CurrentLyricsUiState) = when {
        state.hasLocalWordByWordLyrics && state.wordByWordLyricsEnabled -> R.string.ui_available_local_word_by_word_lyrics
        state.hasLocalWordByWordLyrics -> R.string.ui_imported_off
        else -> R.string.ui_not_imported
    }

    fun buildDetails(state: CurrentLyricsUiState): Pair<LinearLayout, CurrentLyricsDetailTexts?> {
        val details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val media = state.media
        if (media == null) {
            details.addView(normalText(activity, getString(R.string.ui_no_active_media_found)))
            details.addView(smallHint(activity, getString(R.string.ui_manage_lyrics_select_player_hint)))
            return details to null
        }
        val title = normalText(activity, media.displayText)
        val source = settingRow(activity, getString(R.string.ui_plain_lyrics_source), state.localSourceText ?: getString(R.string.ui_no_plain_lrc))
        val plain = settingRow(activity, getString(R.string.ui_plain_lyrics), state.plainLyricsTitle ?: getString(R.string.ui_not_bound), valuePolicy = TextDisplayPolicy.ExpandableSummary)
        val wordByWord = wordByWordStatusRow(activity, wordByWordSummaryRes(state))
        val offset = settingRow(activity, getString(R.string.ui_current_offset), localizedOffsetDescription(state.offsetMs))
        val offsetHint = smallHint(activity, getString(R.string.ui_offset_per_song_hint)).apply {
            visibility = if (state.offsetMs == 0L) View.GONE else View.VISIBLE
        }
        details.addView(expandableText(title))
        details.addView(source)
        details.addView(plain)
        details.addView(wordByWord)
        details.addView(offset)
        details.addView(offsetHint)
        return details to CurrentLyricsDetailTexts(
            title, source.settingValueText(), plain.settingValueText(),
            wordByWord.settingValueText(), offset.settingValueText(), offsetHint
        )
    }

    fun render(state: CurrentLyricsUiState) {
        val previous = renderedState
        if (previous == state) return
        val sameSong = previous?.media != null && state.media != null &&
            (previous.operationTarget ?: previous.media.displayText) ==
                (state.operationTarget ?: state.media.displayText)
        val currentMedia = state.media
        if (sameSong && detailTexts != null) {
            beginLyricsCardLayoutTransition(cardView)
            detailTexts?.apply {
                if (previous.media.displayText != currentMedia.displayText) title.text = currentMedia.displayText
                if (previous.localSourceText != state.localSourceText) source.text = state.localSourceText ?: getString(R.string.ui_no_plain_lrc)
                if (previous.plainLyricsTitle != state.plainLyricsTitle) plain.text = state.plainLyricsTitle ?: getString(R.string.ui_not_bound)
                if (previous.hasLocalWordByWordLyrics != state.hasLocalWordByWordLyrics ||
                    previous.wordByWordLyricsEnabled != state.wordByWordLyricsEnabled) {
                    wordByWord.text = getString(wordByWordSummaryRes(state))
                }
                if (previous.offsetMs != state.offsetMs) {
                    offset.text = localizedOffsetDescription(state.offsetMs)
                    offsetHint.visibility = if (state.offsetMs == 0L) View.GONE else View.VISIBLE
                }
            }
        } else {
            if (previous != null) beginLyricsCardContentTransition(cardView)
            val (nextDetails, nextTexts) = buildDetails(state)
            detailsView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            detailsView?.animate()?.cancel()
            body.removeView(detailsView ?: loadingView)
            body.addView(nextDetails, 0)
            detailsView = nextDetails
            detailTexts = nextTexts
            if (previous != null && ValueAnimator.areAnimatorsEnabled()) {
                nextDetails.translationY = dp(6).toFloat()
                nextDetails.animate().translationY(0f).setDuration(180L).start()
            }
        }
        renderedState = state
        actions.visibility = if (state.media == null) View.GONE else View.VISIBLE
        actions.getChildAt(2).visibility = if (state.canRemovePlainLyrics) View.VISIBLE else View.GONE
        actions.getChildAt(3).visibility = if (state.canRemoveWordByWordLyrics) View.VISIBLE else View.GONE
        actions.getChildAt(4).visibility = if (state.canRemoveAllLyrics) View.VISIBLE else View.GONE
    }

    fun populate(
        showRefreshFeedback: Boolean = false,
        preserveContent: Boolean = false
    ) {
        if (showRefreshFeedback) {
            showInlineRefreshFeedback(feedback, getString(R.string.ui_refreshing))
        } else if (!preserveContent && renderedState == null) {
            loadingView.text = getString(R.string.ui_loading)
        }

        body.setTag(R.id.interaction_loading, true)
        loadCurrentLyrics(force = showRefreshFeedback) { state ->
            body.setTag(R.id.interaction_loading, false)
            render(state)
            if (showRefreshFeedback) {
                playLocalRefreshFeedback(activity, target = body, feedback = feedback, message = getString(R.string.ui_refreshed), animateTarget = false)
            }
        }
    }

    populate()

    val createdCard = card(activity) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText(activity, getString(R.string.ui_current_song_lyrics)).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(feedback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl), 0)
            })
            addView(airIconView(
                iconRes = R.drawable.ic_air_refresh,
                tint = colorAccentText,
                contentDescription = getString(R.string.ui_refresh_media_status)
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconTouchSize),
                    dp(AirUiTokens.Layout.IconTouchSize)
                )
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    animate().rotationBy(360f).setDuration(AirUiTokens.Motion.RefreshSpinMs).start()
                    populate(showRefreshFeedback = true)
                }
            })
        })
        addView(body)
    }
    cardView = createdCard
    return RefreshableSettingsCard(
        view = createdCard,
        refreshContent = { populate(preserveContent = true) }
    )
}

private data class CurrentLyricsDetailTexts(
    val title: TextView,
    val source: TextView,
    val plain: TextView,
    val wordByWord: TextView,
    val offset: TextView,
    val offsetHint: View
)

private fun View.settingValueText(): TextView {
    val value = (this as AdaptiveLabelValueLayout).valueView
    return (value as? ExpandableTextLayout)?.textView ?: value as TextView
}

private fun wordByWordStatusRow(activity: MainUiHost, @StringRes valueRes: Int): View = with(activity) {
    val infoButton = airIconView(
        iconRes = R.drawable.ic_air_info,
        tint = colorTextMuted,
        contentDescription = getString(R.string.ui_local_word_by_word_lyrics_title)
    ).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        layoutParams = ViewGroup.LayoutParams(
            dp(AirUiTokens.Layout.CompactIconButtonSize),
            dp(AirUiTokens.Layout.CompactIconButtonSize)
        )
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            activity.showPersistentInfo(
                titleRes = R.string.ui_local_word_by_word_lyrics_title,
                textRes = R.string.ui_word_by_word_lyrics_local_only
            )
        }
    }

    return settingRow(
        activity = activity,
        name = getString(R.string.ui_word_by_word_lyrics),
        value = getString(valueRes),
        trailingView = infoButton
    )
}
