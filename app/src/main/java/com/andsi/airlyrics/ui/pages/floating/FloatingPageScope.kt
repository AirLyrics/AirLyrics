package com.andsi.airlyrics.ui.pages.floating

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedFloatingGravityTitle
import com.andsi.airlyrics.i18n.localizedFloatingFontFamilyTitle
import com.andsi.airlyrics.i18n.localizedFloatingFontWeightTitle
import com.andsi.airlyrics.i18n.localizedFloatingPresetTitle
import com.andsi.airlyrics.i18n.localizedLyricsContentModeTitle
import com.andsi.airlyrics.i18n.localizedLyricsLineModeTitle
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.i18n.localizedLyricsSwitchAnimationTitle
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
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
import com.andsi.airlyrics.core.color.AirColorUtils

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
    internal var activePanelResetStateUpdater: (() -> Unit)? = null
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
            isWordByWordLyricsEnabled = ::wordByWordLyricsEnabled,
            plainPreviewText = { previewLyricsText(style()) },
            wordByWordPreviewText = { wordByWordPreviewText(style()) }
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

    internal fun updateActivePanelResetState() {
        activePanelResetStateUpdater?.invoke()
    }

    internal fun localizedPresetTitle(key: String): String {
        return host.localizedFloatingPresetTitle(key)
    }

    internal fun localizedGravityTitle(gravity: Int): String {
        return host.localizedFloatingGravityTitle(gravity)
    }

    internal fun localizedFontFamilyTitle(fontFamily: FloatingLyricsFontFamily): String {
        return host.localizedFloatingFontFamilyTitle(fontFamily)
    }

    internal fun fontFamilySubtitle(fontFamily: FloatingLyricsFontFamily = style().fontFamily): String {
        return if (fontFamily == FloatingLyricsFontFamily.CUSTOM) {
            host.floatingCustomFontName() ?: localizedFontFamilyTitle(fontFamily)
        } else {
            localizedFontFamilyTitle(fontFamily)
        }
    }

    internal fun fontWeightSubtitle(fontWeight: Int = style().fontWeight): String {
        return localizedFloatingFontWeightTitle(fontWeight)
    }

    internal fun style() = host.floatingStyle()

    internal fun stylePanelReset(
        isAtDefault: (FloatingLyricsStyle, FloatingLyricsStyle) -> Boolean,
        restoreDefaults: (FloatingLyricsStyle, FloatingLyricsStyle) -> FloatingLyricsStyle
    ): FloatingPanelReset {
        return FloatingPanelReset(
            isAtDefault = {
                val current = style()
                isAtDefault(current, host.floatingStyleDefaults(current.presetName))
            },
            reset = {
                val previous = style()
                val defaults = host.floatingStyleDefaults(previous.presetName)
                host.applyFloatingStyle(restoreDefaults(previous, defaults))
                refreshFloatingPreview()
                val undo: () -> Unit = {
                    host.applyFloatingStyle(previous)
                    refreshFloatingPreview()
                }
                undo
            }
        )
    }

    internal fun contentDisplayMode() = host.lyricsContentDisplayMode()

    internal fun lineDisplayMode() = host.lyricsLineDisplayMode()

    internal fun switchAnimationMode() = host.lyricsSwitchAnimationMode()

    internal fun wordByWordLyricsEnabled() = host.wordByWordLyricsEnabled()

    internal fun wordByWordLyricsSubtitle(): String {
        return if (wordByWordLyricsEnabled()) {
            host.getString(R.string.ui_local_word_by_word_lyrics)
        } else {
            host.getString(R.string.ui_off)
        }
    }

    internal fun previewLyricsText(previewStyle: FloatingLyricsStyle): CharSequence {
        return formattedPreviewLyrics(
            mode = contentDisplayMode(),
            lines = visiblePreviewLyricLines(),
            textColor = previewStyle.textColor
        )
    }

    internal fun wordByWordPreviewText(previewStyle: FloatingLyricsStyle): CharSequence {
        val currentLine = currentPreviewLyricLine()
        val visibleLines = visiblePreviewLyricLines()
        val result = SpannableStringBuilder(
            formattedPreviewLyrics(
                mode = contentDisplayMode(),
                lines = visibleLines,
                textColor = previewStyle.textColor
            )
        )
        if (contentDisplayMode() != LyricsContentDisplayMode.TRANSLATION_ONLY) {
            val currentLineStart = result.toString().indexOf(currentLine.original)
            val highlightedCharacters = (currentLine.original.length / 2)
                .coerceAtLeast(1)
                .coerceAtMost(currentLine.original.length)
            if (currentLineStart >= 0) {
                result.setSpan(
                    ForegroundColorSpan(previewStyle.wordByWordHighlightColor),
                    currentLineStart,
                    currentLineStart + highlightedCharacters,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return result
    }

    private fun visiblePreviewLyricLines(): List<FloatingPreviewLyricLine> {
        val previous = FloatingPreviewLyricLine(
            original = host.getString(R.string.ui_previous_lyric_preview),
            translation = host.getString(R.string.ui_previous_lyric_preview_translation),
            isCurrent = false
        )
        val current = currentPreviewLyricLine()
        val next = FloatingPreviewLyricLine(
            original = host.getString(R.string.ui_next_lyric_preview),
            translation = host.getString(R.string.ui_next_lyric_preview_translation),
            isCurrent = false
        )
        return when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(current)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(previous, current)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(current, next)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(previous, current, next)
        }
    }

    private fun currentPreviewLyricLine(): FloatingPreviewLyricLine {
        return FloatingPreviewLyricLine(
            original = host.getString(R.string.ui_this_is_a_lyric_preview),
            translation = host.getString(R.string.ui_this_is_a_lyric_preview_translation),
            isCurrent = true
        )
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
        updateFloatingTileSubtitle(host.getString(R.string.ui_text_color), AirColorUtils.colorSummary(latestStyle.textColor))
        updateFloatingTileSubtitle(host.getString(R.string.ui_background_bubble), if (latestStyle.backgroundEnabled) host.getString(R.string.ui_on) else host.getString(R.string.ui_off))
        updateFloatingTileSubtitle(host.getString(R.string.ui_font_size), "${latestStyle.textSizeSp.toInt()} sp")
        updateFloatingTileSubtitle(host.getString(R.string.ui_font), fontFamilySubtitle(latestStyle.fontFamily))
        updateFloatingTileSubtitle(host.getString(R.string.ui_font_weight), fontWeightSubtitle(latestStyle.fontWeight))
        updateFloatingTileSubtitle(host.getString(R.string.ui_shadow_stroke), host.getString(R.string.ui_radius) + " ${latestStyle.shadowRadius.toInt()}")
        updateFloatingTileSubtitle(host.getString(R.string.ui_window_layout), host.getString(R.string.ui_width) + " ${latestStyle.maxWidthPercent}%")
        updateFloatingTileSubtitle(host.getString(R.string.ui_content), host.localizedLyricsContentModeTitle(contentDisplayMode()))
        updateFloatingTileSubtitle(host.getString(R.string.ui_line_range), host.localizedLyricsLineModeTitle(lineDisplayMode()))
        updateFloatingTileSubtitle(host.getString(R.string.ui_text_alignment), localizedGravityTitle(latestStyle.gravity))
        updateFloatingTileSubtitle(host.getString(R.string.ui_lyrics_offset), host.uiActions.currentLyricsOffsetSummary())
        updateFloatingTileSubtitle(host.getString(R.string.ui_switch_animation), host.localizedLyricsSwitchAnimationTitle(switchAnimationMode()))
        updateFloatingTileSubtitle(host.getString(R.string.ui_word_by_word_lyrics), wordByWordLyricsSubtitle())
        updateFloatingTileSubtitle(host.getString(R.string.ui_highlight_color), AirColorUtils.colorSummary(latestStyle.wordByWordHighlightColor))
        updateFloatingTileSubtitle(host.getString(R.string.ui_display_control), host.floatingDisplaySummary())
        updateFloatingTileSubtitle(host.getString(R.string.ui_auto_hide_when_paused), onOff(host.autoHideWhenPausedEnabled()))
        updateActivePanelResetState()
    }

    private fun updateFloatingTileSubtitle(title: String, subtitle: String) {
        pageRefs.tileSubtitles[title]?.text = subtitle
    }

    internal fun applyLyricsDisplaySettingsChanged() {
        renderFloatingPreview(style())
        refreshFloatingSettingTiles()
        host.notifyFloatingStyleChanged()
    }

    internal fun applyLyricsAnimationSettingsChanged() {
        playPreviewSwitchAnimation()
        refreshFloatingSettingTiles()
        host.notifyFloatingStyleChanged()
    }

    internal fun applyWordByWordLyricsChanged(enabled: Boolean) {
        host.setWordByWordLyricsEnabled(enabled)
        renderFloatingPreview(style())
        refreshFloatingSettingTiles()
        host.uiActions.reloadFloatingLyrics()
    }

    internal fun applyLyricsOffsetDelta(deltaMs: Long, statusView: TextView?) {
        val offset = host.uiActions.adjustLyricsOffsetForCurrentMedia(deltaMs)
        if (offset == null) {
            host.showShortToast(host.getString(R.string.ui_please_play_and_select_a_song_first))
            statusView?.text = host.getString(R.string.ui_waiting_for_current_song)
            return
        }
        statusView?.text = host.localizedOffsetDescription(offset)
        refreshFloatingSettingTiles()
    }

    internal fun resetLyricsOffset(statusView: TextView?) {
        if (!host.uiActions.resetLyricsOffsetForCurrentMedia()) {
            host.showShortToast(host.getString(R.string.ui_please_play_and_select_a_song_first))
            statusView?.text = host.getString(R.string.ui_waiting_for_current_song)
            return
        }
        statusView?.text = host.localizedOffsetDescription(0L)
        refreshFloatingSettingTiles()
    }

    internal fun refreshFloatingPreview() {
        val latestStyle = style()
        renderFloatingPreview(latestStyle)
        refreshFloatingSettingTiles()
    }

    internal fun previewFloatingTextSize(textSizeSp: Float) {
        val previewStyle = style().copy(textSizeSp = textSizeSp)
        renderFloatingPreview(previewStyle)
        updateFloatingTileSubtitle(host.getString(R.string.ui_font_size), "${textSizeSp.toInt()} sp")
    }

    internal fun previewFloatingFontWeight(fontWeight: Int) {
        val previewStyle = style().copy(fontWeight = fontWeight)
        renderFloatingPreview(previewStyle)
        updateFloatingTileSubtitle(host.getString(R.string.ui_font_weight), fontWeightSubtitle(fontWeight))
    }

    private fun renderFloatingPreview(latestStyle: FloatingLyricsStyle) {
        previewHandle?.updateLineMode?.invoke(lineDisplayMode())
        previewHandle?.updateStyle?.invoke(latestStyle)
        previewHandle?.lyricTextView?.apply {
            text = if (wordByWordLyricsEnabled()) {
                wordByWordPreviewText(latestStyle)
            } else {
                previewLyricsText(latestStyle)
            }
            with(host) {
                applyFloatingPreviewStyle(latestStyle)
            }
        }
        previewHandle?.bodyView?.requestLayout()
    }

}
