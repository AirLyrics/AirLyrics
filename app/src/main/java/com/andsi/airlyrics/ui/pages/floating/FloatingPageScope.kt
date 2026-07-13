package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsContentModeTitle
import com.andsi.airlyrics.i18n.localizedLyricsLineModeTitle
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.i18n.localizedLyricsSwitchAnimationTitle
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.pages.floating.sections.addAnimationSection
import com.andsi.airlyrics.ui.pages.floating.sections.addAppearanceSection
import com.andsi.airlyrics.ui.pages.floating.sections.addBehaviorSection
import com.andsi.airlyrics.ui.pages.floating.sections.addLyricsDisplaySection
import com.andsi.airlyrics.ui.refs.FloatingPageRefs

internal class FloatingPageScope(
    internal val host: MainUiHost
) {
    private val rootFrame = FrameLayout(host)
    private val root = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            host.dp(FloatingPageTokens.PAGE_PADDING_HORIZONTAL_DP),
            host.dp(FloatingPageTokens.PAGE_PADDING_TOP_DP),
            host.dp(FloatingPageTokens.PAGE_PADDING_HORIZONTAL_DP),
            host.dp(FloatingPageTokens.PAGE_PADDING_BOTTOM_DP)
        )
    }
    private val pageFrame = FrameLayout(host)

    internal var previewHandle: FloatingPreviewCardHandle? = null
    internal var focusOverlay: FrameLayout? = null
    internal var activeBubble: LinearLayout? = null
    internal var selectedTileView: View? = null
    internal val pageRefs = FloatingPageRefs()
    internal var previewExpanded = host.isFloatingPreviewExpanded()

    init {
        host.floatingPageRefs = pageRefs
    }

    internal fun createView(): View = with(host) {
        installBackHandler()

        val createdPreviewHandle = createFloatingPreviewCard(
            isExpanded = { previewExpanded },
            setExpanded = { expanded ->
                previewExpanded = expanded
                setFloatingPreviewExpanded(expanded)
            },
            style = ::style,
            lineDisplayMode = ::lineDisplayMode,
            isKaraokeEnabled = ::karaokeLyricsEnabled,
            plainPreviewText = ::previewLyricsText,
            karaokePreviewText = ::karaokePreviewText,
            summaryText = { floatingPreviewSummary(style()) },
            onExpandedChanged = { refreshFloatingSettingTiles() }
        )
        previewHandle = createdPreviewHandle
        root.addView(createdPreviewHandle.cardView)

        val list = pageContainer(host).apply {
            setPadding(0, dp(FloatingPageTokens.LIST_PADDING_TOP_DP), 0, 0)
            addAppearanceSection(this)
            addLyricsDisplaySection(this)
            addAnimationSection(this)
            addBehaviorSection(this)
        }

        val contentScroll = scroll(host, list)
        pageFrame.addView(contentScroll.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
        root.addView(pageFrame.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        })

        rootFrame.addView(root.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        focusOverlay = FrameLayout(host).apply {
            visibility = View.GONE
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(focusOverlay)

        return@with rootFrame
    }

    internal fun onOff(value: Boolean): String {
        return if (value) host.getString(R.string.ui_on) else host.getString(R.string.ui_off)
    }

    internal fun localizedPresetTitle(key: String): String {
        return host.localizedFloatingPresetTitle(key)
    }

    internal fun localizedGravityTitle(gravity: Int): String {
        return host.localizedFloatingGravityTitle(gravity)
    }

    internal fun style() = host.floatingStyle()

    internal fun contentDisplayMode() = host.lyricsContentDisplayMode()

    internal fun lineDisplayMode() = host.lyricsLineDisplayMode()

    internal fun switchAnimationMode() = host.lyricsSwitchAnimationMode()

    internal fun karaokeLyricsEnabled() = host.karaokeLyricsEnabled()

    internal fun wordLyricsSubtitle(): String {
        return if (karaokeLyricsEnabled()) {
            host.getString(R.string.ui_local_enhanced_lrc)
        } else {
            host.getString(R.string.ui_off)
        }
    }

    internal fun previewLyricsText(): String {
        val previous = previewLineText(
            contentDisplayMode(),
            host.getString(R.string.ui_previous_lyric_preview),
            "Previous lyric preview"
        )
        val current = previewLineText(
            contentDisplayMode(),
            host.getString(R.string.ui_this_is_a_lyric_preview),
            "This is a lyric preview"
        )
        val next = previewLineText(
            contentDisplayMode(),
            host.getString(R.string.ui_next_lyric_preview),
            "Next lyric preview"
        )
        return when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> current
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(previous, current).joinToString("\n")
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(current, next).joinToString("\n")
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(previous, current, next).joinToString("\n")
        }
    }

    internal fun karaokePreviewText(): CharSequence {
        val text = host.getString(R.string.ui_floating_preview_sample)
        val firstLineEnd = text.indexOf('\n').takeIf { it > 0 } ?: text.length
        val highlightEnd = (firstLineEnd / 2).coerceAtLeast(1).coerceAtMost(firstLineEnd)
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(style().karaokeHighlightColor),
                0,
                highlightEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                highlightEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    internal fun trackedFloatingTile(
        title: String,
        subtitle: String,
        iconRes: Int,
        onClick: (View) -> Unit
    ): FloatingSettingTile {
        return FloatingSettingTile(
            title = title,
            subtitle = subtitle,
            iconRes = iconRes,
            onClick = onClick,
            onSubtitleViewCreated = { subtitleView ->
                pageRefs.registerTileSubtitle(title, subtitleView)
                if (title == host.getString(R.string.ui_display_control)) {
                    pageRefs.displayControlSubtitle = subtitleView
                }
            }
        )
    }

    internal fun refreshFloatingSettingTiles() {
        val latestStyle = style()
        updateFloatingTileSubtitle(host.getString(R.string.ui_skin_preset), localizedPresetTitle(latestStyle.presetName))
        updateFloatingTileSubtitle(host.getString(R.string.ui_text_color), host.colorSummary(latestStyle.textColor))
        updateFloatingTileSubtitle(host.getString(R.string.ui_background_bubble), if (latestStyle.backgroundEnabled) host.getString(R.string.ui_on) else host.getString(R.string.ui_off))
        updateFloatingTileSubtitle(host.getString(R.string.ui_font_size), "${latestStyle.textSizeSp.toInt()}sp")
        updateFloatingTileSubtitle(host.getString(R.string.ui_shadow_stroke), host.getString(R.string.ui_radius) + " ${latestStyle.shadowRadius.toInt()}")
        updateFloatingTileSubtitle(host.getString(R.string.ui_window_layout), host.getString(R.string.ui_width) + " ${latestStyle.maxWidthPercent}%")
        updateFloatingTileSubtitle(host.getString(R.string.ui_content), host.localizedLyricsContentModeTitle(contentDisplayMode()))
        updateFloatingTileSubtitle(host.getString(R.string.ui_line_range), host.localizedLyricsLineModeTitle(lineDisplayMode()))
        updateFloatingTileSubtitle(host.getString(R.string.ui_text_alignment), localizedGravityTitle(latestStyle.gravity))
        updateFloatingTileSubtitle(host.getString(R.string.ui_lyrics_offset), host.uiActions.currentLyricsOffsetSummary())
        updateFloatingTileSubtitle(host.getString(R.string.ui_switch_animation), host.localizedLyricsSwitchAnimationTitle(switchAnimationMode()))
        updateFloatingTileSubtitle(host.getString(R.string.ui_enhanced_lrc), wordLyricsSubtitle())
        updateFloatingTileSubtitle(host.getString(R.string.ui_highlight_color), host.colorSummary(latestStyle.karaokeHighlightColor))
        updateFloatingTileSubtitle(host.getString(R.string.ui_display_control), host.floatingDisplaySummary())
        updateFloatingTileSubtitle(host.getString(R.string.ui_auto_hide_when_paused), onOff(host.autoHideWhenPausedEnabled()))
    }

    private fun updateFloatingTileSubtitle(title: String, subtitle: String) {
        pageRefs.tileSubtitles[title]?.text = subtitle
    }

    internal fun applyLyricsDisplaySettingsChanged() {
        previewHandle?.lyricTextView?.text = if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText()
        previewHandle?.lyricTextView?.maxLines = previewMaxLines(lineDisplayMode())
        previewHandle?.bodyView?.requestLayout()
        refreshFloatingSettingTiles()
        host.notifyFloatingStyleChanged()
    }

    internal fun applyLyricsAnimationSettingsChanged() {
        playPreviewSwitchAnimation()
        refreshFloatingSettingTiles()
        host.notifyFloatingStyleChanged()
    }

    internal fun applyKaraokeLyricsChanged(enabled: Boolean) {
        host.setKaraokeLyricsEnabled(enabled)
        previewHandle?.lyricTextView?.text = if (enabled) karaokePreviewText() else previewLyricsText()
        refreshFloatingSettingTiles()
        host.uiActions.reloadFloatingLyrics()
    }

    internal fun applyLyricsOffsetDelta(deltaMs: Long, statusView: TextView?) {
        val offset = host.uiActions.adjustLyricsOffsetForCurrentMedia(deltaMs)
        if (offset == null) {
            Toast.makeText(host, host.getString(R.string.ui_please_play_and_select_a_song_first), Toast.LENGTH_SHORT).show()
            statusView?.text = host.getString(R.string.ui_waiting_for_current_song)
            return
        }
        statusView?.text = host.localizedOffsetDescription(offset)
        refreshFloatingSettingTiles()
    }

    internal fun resetLyricsOffset(statusView: TextView?) {
        if (!host.uiActions.resetLyricsOffsetForCurrentMedia()) {
            Toast.makeText(host, host.getString(R.string.ui_please_play_and_select_a_song_first), Toast.LENGTH_SHORT).show()
            statusView?.text = host.getString(R.string.ui_waiting_for_current_song)
            return
        }
        statusView?.text = host.localizedOffsetDescription(0L)
        refreshFloatingSettingTiles()
    }

    internal fun refreshFloatingPreview() {
        val latestStyle = style()
        previewHandle?.summaryTextView?.text = host.floatingPreviewSummary(latestStyle)
        previewHandle?.lyricTextView?.apply {
            text = if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText()
            with(host) {
                applyFloatingPreviewStyle(latestStyle)
            }
        }
        refreshFloatingSettingTiles()
    }

}
