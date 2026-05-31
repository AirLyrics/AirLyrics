package com.andsi.airlyrics.ui.pages

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.sliderRow
import com.andsi.airlyrics.app.colorControl
import com.andsi.airlyrics.app.liveOptionGrid
import com.andsi.airlyrics.app.settingGrid
import com.andsi.airlyrics.app.floatingFocusBubble
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun createFloatingPage(activity: MainActivity): View  = with(activity) createFloatingPage@ {
    val rootFrame = FrameLayout(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(FloatingPageTokens.PAGE_PADDING_HORIZONTAL_DP),
            dp(FloatingPageTokens.PAGE_PADDING_TOP_DP),
            dp(FloatingPageTokens.PAGE_PADDING_HORIZONTAL_DP),
            dp(FloatingPageTokens.PAGE_PADDING_BOTTOM_DP)
        )
    }

    val pageFrame = FrameLayout(this)
    var previewHandle: FloatingPreviewCardHandle? = null
    var baseContentView: View? = null
    var contentScroll: ScrollView? = null
    var focusOverlay: FrameLayout? = null
    var activeBubble: LinearLayout? = null
    var selectedTileView: View? = null

    fun onOff(value: Boolean): String = if (value) getString(R.string.ui_on) else getString(R.string.ui_off)
    fun localizedPresetTitle(key: String): String = localizedFloatingPresetTitle(key)
    fun localizedGravityTitle(gravity: Int): String = localizedFloatingGravityTitle(gravity)
    fun style() = FloatingLyricsStyleStore.getStyle(this)
    fun contentDisplayMode() = LyricsSettingsStore.getContentDisplayMode(this)
    fun lineDisplayMode() = LyricsSettingsStore.getLineDisplayMode(this)
    fun switchAnimationMode() = LyricsSettingsStore.getSwitchAnimationMode(this)
    fun karaokeLyricsEnabled() = LyricsSettingsStore.isKaraokeLyricsEnabled(this)
    fun wordLyricsSubtitle(): String = if (karaokeLyricsEnabled()) getString(R.string.ui_local_enhanced_lrc) else getString(R.string.ui_off)
    var previewExpanded = FloatingLyricsStyleStore.isPreviewExpanded(this)

    fun lyricsDisplaySummary(): String {
        return "${localizedContentDisplayTitle(contentDisplayMode())} · ${localizedLineDisplayTitle(lineDisplayMode())}"
    }

    fun previewLyricsText(): String {
        val previous = previewLineText(contentDisplayMode(), getString(R.string.ui_previous_lyric_preview), "Previous lyric preview")
        val current = previewLineText(contentDisplayMode(), getString(R.string.ui_this_is_a_lyric_preview), "This is a lyric preview")
        val next = previewLineText(contentDisplayMode(), getString(R.string.ui_next_lyric_preview), "Next lyric preview")
        return when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> current
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(previous, current).joinToString("\n")
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(current, next).joinToString("\n")
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(previous, current, next).joinToString("\n")
        }
    }


    fun karaokePreviewText(): CharSequence {
        val text = getString(R.string.ui_floating_preview_sample)
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

    fun updateFloatingTileSubtitle(title: String, subtitle: String) {
        rootFrame.findViewWithTag<TextView>("floating_tile_subtitle:$title")?.text = subtitle
    }

    fun refreshFloatingSettingTiles() {
        val latestStyle = style()
        updateFloatingTileSubtitle(getString(R.string.ui_skin_preset), localizedPresetTitle(latestStyle.presetName))
        updateFloatingTileSubtitle(getString(R.string.ui_text_color), FloatingLyricsStyleStore.colorSummary(latestStyle.textColor))
        updateFloatingTileSubtitle(getString(R.string.ui_background_bubble), if (latestStyle.backgroundEnabled) getString(R.string.ui_on) else getString(R.string.ui_off))
        updateFloatingTileSubtitle(getString(R.string.ui_font_size), "${latestStyle.textSizeSp.toInt()}sp")
        updateFloatingTileSubtitle(getString(R.string.ui_shadow_stroke), getString(R.string.ui_radius) + " ${latestStyle.shadowRadius.toInt()}")
        updateFloatingTileSubtitle(getString(R.string.ui_window_layout), getString(R.string.ui_width) + " ${latestStyle.maxWidthPercent}%")
        updateFloatingTileSubtitle(getString(R.string.ui_content), localizedContentDisplayTitle(contentDisplayMode()))
        updateFloatingTileSubtitle(getString(R.string.ui_line_range), localizedLineDisplayTitle(lineDisplayMode()))
        updateFloatingTileSubtitle(getString(R.string.ui_text_alignment), localizedGravityTitle(latestStyle.gravity))
        updateFloatingTileSubtitle(getString(R.string.ui_lyrics_offset), uiActions.currentLyricsOffsetSummary())
        updateFloatingTileSubtitle(getString(R.string.ui_switch_animation), localizedSwitchAnimationTitle(switchAnimationMode()))
        updateFloatingTileSubtitle(getString(R.string.ui_enhanced_lrc), wordLyricsSubtitle())
        updateFloatingTileSubtitle(getString(R.string.ui_highlight_color), FloatingLyricsStyleStore.colorSummary(latestStyle.karaokeHighlightColor))
        updateFloatingTileSubtitle(getString(R.string.ui_display_control), floatingDisplaySummary())
    }

    fun applyLyricsDisplaySettingsChanged() {
        previewHandle?.lyricTextView?.text = if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText()
        previewHandle?.lyricTextView?.maxLines = previewMaxLines(lineDisplayMode())
        previewHandle?.bodyView?.requestLayout()
        refreshFloatingSettingTiles()
        notifyFloatingStyleChanged()
    }

    fun playPreviewSwitchAnimation() {
        val view = previewHandle?.lyricTextView ?: return
        view.animate().cancel()
        when (switchAnimationMode()) {
            LyricsSwitchAnimationMode.NONE -> {
                view.alpha = 1f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            LyricsSwitchAnimationMode.FADE -> {
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.animate()
                    .alpha(1f)
                    .setDuration(FloatingPageTokens.PREVIEW_FADE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            LyricsSwitchAnimationMode.SLIDE_UP -> {
                view.alpha = 0f
                view.translationY = dp(FloatingPageTokens.PREVIEW_SLIDE_Y_DP).toFloat()
                view.scaleX = 1f
                view.scaleY = 1f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(FloatingPageTokens.PREVIEW_SLIDE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            LyricsSwitchAnimationMode.SCALE_FADE -> {
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = FloatingPageTokens.PREVIEW_SCALE_START
                view.scaleY = FloatingPageTokens.PREVIEW_SCALE_START
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(FloatingPageTokens.PREVIEW_SCALE_FADE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    fun applyLyricsAnimationSettingsChanged() {
        playPreviewSwitchAnimation()
        refreshFloatingSettingTiles()
        notifyFloatingStyleChanged()
    }

    fun applyKaraokeLyricsChanged(enabled: Boolean) {
        LyricsSettingsStore.setKaraokeLyricsEnabled(activity, enabled)
        previewHandle?.lyricTextView?.text = if (enabled) {
            karaokePreviewText()
        } else {
            previewLyricsText()
        }
        refreshFloatingSettingTiles()
        notifyFloatingStyleChanged()
    }



    fun applyLyricsOffsetDelta(deltaMs: Long, statusView: TextView?) {
        val offset = uiActions.adjustLyricsOffsetForCurrentMedia(deltaMs)
        if (offset == null) {
            Toast.makeText(activity, getString(R.string.ui_please_play_and_select_a_song_first), Toast.LENGTH_SHORT).show()
            statusView?.text = getString(R.string.ui_waiting_for_current_song)
            return
        }
        statusView?.text = localizedOffsetDescription(offset)
        refreshFloatingSettingTiles()
    }

    fun resetLyricsOffset(statusView: TextView?) {
        if (!uiActions.resetLyricsOffsetForCurrentMedia()) {
            Toast.makeText(activity, getString(R.string.ui_please_play_and_select_a_song_first), Toast.LENGTH_SHORT).show()
            statusView?.text = getString(R.string.ui_waiting_for_current_song)
            return
        }
        statusView?.text = localizedOffsetDescription(0L)
        refreshFloatingSettingTiles()
    }

    fun refreshFloatingPreview() {
        val latestStyle = style()
        val summary = floatingPreviewSummary(latestStyle)
        previewHandle?.summaryTextView?.text = summary
        previewHandle?.lyricTextView?.apply {
            text = if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText()
            applyFloatingPreviewStyle(latestStyle)
        }
        refreshFloatingSettingTiles()
    }

    fun clearContentFocus() {
        selectedTileView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
            ?.start()
        selectedTileView = null
    }

    fun closePanel() {
        val overlay = focusOverlay ?: return
        val bubble = activeBubble
        bubble?.animate()
            ?.alpha(0f)
            ?.scaleX(FloatingPageTokens.PANEL_CLOSE_SCALE)
            ?.scaleY(FloatingPageTokens.PANEL_CLOSE_SCALE)
            ?.translationY(dp(FloatingPageTokens.PANEL_CLOSE_TRANSLATION_Y_DP).toFloat())
            ?.setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
            ?.withEndAction {
                overlay.visibility = View.GONE
                overlay.removeAllViews()
                activeBubble = null
                clearContentFocus()
            }
            ?.start()
            ?: run {
                overlay.visibility = View.GONE
                overlay.removeAllViews()
                activeBubble = null
                clearContentFocus()
            }
    }

    floatingPanelBackHandler = {
        if (activeBubble != null || focusOverlay?.visibility == View.VISIBLE) {
            closePanel()
            true
        } else {
            false
        }
    }

    fun openPanel(anchor: View, title: String, subtitle: String, content: LinearLayout.() -> Unit) {
        val overlay = focusOverlay ?: return
        selectedTileView = anchor

        anchor.animate()
            .scaleX(FloatingPageTokens.PANEL_SELECTED_SCALE)
            .scaleY(FloatingPageTokens.PANEL_SELECTED_SCALE)
            .alpha(FloatingPageTokens.PANEL_SELECTED_ALPHA)
            .setDuration(FloatingPageTokens.FAST_ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val bubble = floatingFocusBubble(title, subtitle, ::closePanel) {
            content()
        }

        overlay.removeAllViews()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.setOnClickListener { closePanel() }
        overlay.addView(bubble)
        activeBubble = bubble

        bubble.setOnClickListener { /* keep clicks inside the bubble */ }
        bubble.isClickable = true
        bubble.alpha = 0f
        bubble.scaleX = FloatingPageTokens.PANEL_OPEN_START_SCALE
        bubble.scaleY = FloatingPageTokens.PANEL_OPEN_START_SCALE

        overlay.post {
            val overlayCenter = IntArray(2)
            val anchorCenter = IntArray(2)
            overlay.getLocationOnScreen(overlayCenter)
            anchor.getLocationOnScreen(anchorCenter)
            val startX = anchorCenter[0] + anchor.width / 2f - overlayCenter[0] - overlay.width / 2f
            val startY = anchorCenter[1] + anchor.height / 2f - overlayCenter[1] - overlay.height / 2f

            bubble.translationX = startX
            bubble.translationY = startY
            overlay.animate().alpha(1f).setDuration(FloatingPageTokens.PANEL_OVERLAY_FADE_MS).start()
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(FloatingPageTokens.PANEL_OPEN_MS)
                .setInterpolator(OvershootInterpolator(FloatingPageTokens.PANEL_OPEN_OVERSHOOT_TENSION))
                .start()
        }
    }

    previewHandle = createFloatingPreviewCard(
        isExpanded = { previewExpanded },
        setExpanded = { expanded ->
            previewExpanded = expanded
            FloatingLyricsStyleStore.setPreviewExpanded(activity, expanded)
        },
        style = ::style,
        lineDisplayMode = ::lineDisplayMode,
        isKaraokeEnabled = ::karaokeLyricsEnabled,
        plainPreviewText = ::previewLyricsText,
        karaokePreviewText = ::karaokePreviewText,
        summaryText = { floatingPreviewSummary(style()) },
        onExpandedChanged = { refreshFloatingSettingTiles() }
    )
    root.addView(previewHandle!!.cardView)

    val list = pageContainer(activity).apply {
        setPadding(0, dp(FloatingPageTokens.LIST_PADDING_TOP_DP), 0, 0)
        addView(floatingSectionTitle(getString(R.string.ui_appearance)))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = getString(R.string.ui_skin_preset),
                    subtitle = localizedPresetTitle(style().presetName),
                    iconRes = R.drawable.ic_air_style,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_skin_preset), "") {
                            addView(liveOptionGrid(
                                FloatingLyricsStyleStore.presets.map { preset ->
                                    KeyedOptionItem(
                                        key = preset.key,
                                        title = localizedPresetTitle(preset.key),
                                        selected = preset.key == style().presetName,
                                        action = {
                                            applyFloatingPreset(preset.key)
                                            refreshFloatingPreview()
                                        }
                                    )
                                }
                            ))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_text_color),
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().textColor),
                    iconRes = R.drawable.ic_air_text_color,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_text_color), "") {
                            addView(colorControl(getString(R.string.ui_text), style().textColor) { color ->
                                applyFloatingTextColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_background_bubble),
                    subtitle = if (style().backgroundEnabled) getString(R.string.ui_on) else getString(R.string.ui_off),
                    iconRes = R.drawable.ic_air_chat_bubble,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_background_bubble), "") {
                            val backgroundButton = actionButton(activity, if (style().backgroundEnabled) getString(R.string.ui_background_on) else getString(R.string.ui_background_off)) { }
                            backgroundButton.setOnClickListener {
                                val enabled = !FloatingLyricsStyleStore.getStyle(activity).backgroundEnabled
                                FloatingLyricsStyleStore.setBackgroundEnabled(activity, enabled)
                                notifyFloatingStyleChanged()
                                backgroundButton.text = if (enabled) getString(R.string.ui_background_on) else getString(R.string.ui_background_off)
                                refreshFloatingPreview()
                            }
                            addView(backgroundButton)
                            addView(colorControl(getString(R.string.ui_background), FloatingLyricsStyleStore.backgroundColorWithAlpha(style())) { color ->
                                applyFloatingBackgroundColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_font_size),
                    subtitle = "${style().textSizeSp.toInt()}sp",
                    iconRes = R.drawable.ic_air_format_size,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_font_size), "") {
                            addView(sliderRow(getString(R.string.ui_size), style().textSizeSp.toInt(), 14, 56, "sp") { value ->
                                applyFloatingTextSize(value.toFloat(), refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_shadow_stroke),
                    subtitle = getString(R.string.ui_radius) + " ${style().shadowRadius.toInt()}",
                    iconRes = R.drawable.ic_air_shadow,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_shadow_stroke), "") {
                            addView(sliderRow(getString(R.string.ui_shadow_radius), style().shadowRadius.toInt(), 0, 24, "") { value ->
                                FloatingLyricsStyleStore.setShadowRadius(activity, value.toFloat())
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(colorControl(getString(R.string.ui_shadow), style().shadowColor) { color ->
                                FloatingLyricsStyleStore.setShadowColor(activity, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_window_layout),
                    subtitle = getString(R.string.ui_width) + " ${style().maxWidthPercent}%",
                    iconRes = R.drawable.ic_air_pip,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_window_layout), "") {
                            addView(sliderRow(getString(R.string.ui_max_width), style().maxWidthPercent, 45, 100, "%") { value ->
                                FloatingLyricsStyleStore.setMaxWidthPercent(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow(getString(R.string.ui_horizontal_padding), style().paddingHorizontalDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingHorizontal(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow(getString(R.string.ui_vertical_padding), style().paddingVerticalDp, 0, 28, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingVertical(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow(getString(R.string.ui_corner_radius), style().cornerRadiusDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setCornerRadius(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )

        addView(floatingSectionTitle(getString(R.string.ui_lyrics_display)))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = getString(R.string.ui_content),
                    subtitle = localizedContentDisplayTitle(contentDisplayMode()),
                    iconRes = R.drawable.ic_air_lyrics,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_content), "") {
                            addView(liveOptionGrid(
                                LyricsContentDisplayMode.entries.map { mode ->
                                    KeyedOptionItem(
                                        key = mode.key,
                                        title = localizedContentDisplayTitle(mode),
                                        selected = mode == contentDisplayMode(),
                                        action = {
                                            LyricsSettingsStore.setContentDisplayMode(activity, mode)
                                            applyLyricsDisplaySettingsChanged()
                                        }
                                    )
                                }
                            ))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_line_range),
                    subtitle = localizedLineDisplayTitle(lineDisplayMode()),
                    iconRes = R.drawable.ic_air_line_spacing,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_line_range), "") {
                            addView(liveOptionGrid(
                                LyricsLineDisplayMode.entries.map { mode ->
                                    KeyedOptionItem(
                                        key = mode.key,
                                        title = localizedLineDisplayTitle(mode),
                                        selected = mode == lineDisplayMode(),
                                        action = {
                                            LyricsSettingsStore.setLineDisplayMode(activity, mode)
                                            applyLyricsDisplaySettingsChanged()
                                        }
                                    )
                                }
                            ))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_text_alignment),
                    subtitle = localizedGravityTitle(style().gravity),
                    iconRes = R.drawable.ic_air_align_center,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_text_alignment), "") {
                            addView(liveOptionGrid(listOf(
                                KeyedOptionItem("left", getString(R.string.ui_left), style().gravity == (Gravity.START or Gravity.CENTER_VERTICAL)) {
                                    applyFloatingGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                                    refreshFloatingPreview()
                                },
                                KeyedOptionItem("center", getString(R.string.ui_center), style().gravity == Gravity.CENTER) {
                                    applyFloatingGravity(Gravity.CENTER)
                                    refreshFloatingPreview()
                                },
                                KeyedOptionItem("right", getString(R.string.ui_right), style().gravity == (Gravity.END or Gravity.CENTER_VERTICAL)) {
                                    applyFloatingGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                                    refreshFloatingPreview()
                                }
                            )))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_lyrics_offset),
                    subtitle = uiActions.currentLyricsOffsetSummary(),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_lyrics_offset), "") {
                            val statusText = normalText(activity, uiActions.currentLyricsOffsetSummary()).apply {
                                textSize = FloatingPageTokens.OFFSET_STATUS_TEXT_SP
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(colorTextStrong)
                                setPadding(0, dp(FloatingPageTokens.OFFSET_STATUS_PADDING_TOP_DP), 0, dp(FloatingPageTokens.OFFSET_STATUS_PADDING_BOTTOM_DP))
                            }
                            addView(statusText)
                            addView(horizontalButtons(activity,
                                getString(R.string.ui_delay_1s) to { applyLyricsOffsetDelta(-1_000L, statusText) },
                                getString(R.string.ui_advance_1s) to { applyLyricsOffsetDelta(1_000L, statusText) }
                            ))
                            addView(horizontalButtons(activity,
                                getString(R.string.ui_delay_0_1s) to { applyLyricsOffsetDelta(-100L, statusText) },
                                getString(R.string.ui_advance_0_1s) to { applyLyricsOffsetDelta(100L, statusText) }
                            ))
                            addView(actionButton(activity, getString(R.string.ui_reset_current_song_offset)) {
                                resetLyricsOffset(statusText)
                            })
                        }
                    }
                )
            )
        )
        addView(floatingSectionTitle(getString(R.string.ui_animation)))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = getString(R.string.ui_switch_animation),
                    subtitle = localizedSwitchAnimationTitle(switchAnimationMode()),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_switch_animation), "") {
                            addView(liveOptionGrid(
                                LyricsSwitchAnimationMode.entries.map { mode ->
                                    KeyedOptionItem(
                                        key = mode.key,
                                        title = localizedSwitchAnimationTitle(mode),
                                        selected = mode == switchAnimationMode(),
                                        action = {
                                            LyricsSettingsStore.setSwitchAnimationMode(activity, mode)
                                            applyLyricsAnimationSettingsChanged()
                                        }
                                    )
                                }
                            ))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_enhanced_lrc),
                    subtitle = wordLyricsSubtitle(),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_enhanced_lrc), "") {
                            addView(liveOptionGrid(listOf(
                                KeyedOptionItem("karaoke_on", getString(R.string.ui_on), karaokeLyricsEnabled()) {
                                    applyKaraokeLyricsChanged(true)
                                },
                                KeyedOptionItem("karaoke_off", getString(R.string.ui_off), !karaokeLyricsEnabled()) {
                                    applyKaraokeLyricsChanged(false)
                                }
                            )))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = getString(R.string.ui_highlight_color),
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().karaokeHighlightColor),
                    iconRes = R.drawable.ic_air_text_color,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_enhanced_color), "") {
                            addView(colorControl(getString(R.string.ui_highlight), style().karaokeHighlightColor) { color ->
                                FloatingLyricsStyleStore.setKaraokeHighlightColor(activity, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )
        addView(floatingSectionTitle(getString(R.string.ui_behavior)))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = getString(R.string.ui_display_control),
                    subtitle = floatingDisplaySummary(),
                    iconRes = R.drawable.ic_air_visibility,
                    onClick = { tile ->
                        openPanel(tile, getString(R.string.ui_display_control), "") {
                            addView(horizontalButtons(activity, 
                                getString(R.string.ui_show) to { uiActions.showFloatingLyrics() },
                                getString(R.string.ui_hide) to { uiActions.hideFloatingLyrics() }
                            ))

                            val lockButton = actionButton(activity, floatingLockButtonText()) { }
                            lockButton.setOnClickListener {
                                uiActions.toggleLock()
                                lockButton.text = floatingLockButtonText()
                                refreshFloatingPreview()
                            }
                            addView(lockButton)

                            val clickThroughButton = actionButton(activity, floatingClickThroughButtonText()) { }
                            clickThroughButton.setOnClickListener {
                                uiActions.toggleClickThrough()
                                clickThroughButton.text = floatingClickThroughButtonText()
                                refreshFloatingPreview()
                            }
                            addView(clickThroughButton)
                        }
                    }
                )
            )
        )
        val summaryButton = actionButton(activity, getString(R.string.ui_view_current_setup)) { }
        summaryButton.setOnClickListener {
            openPanel(summaryButton, getString(R.string.ui_current_setup), "") {
                addView(settingRow(activity, getString(R.string.ui_skin), localizedPresetTitle(style().presetName)))
                addView(settingRow(activity, getString(R.string.ui_font_size), "${style().textSizeSp.toInt()}sp"))
                addView(settingRow(activity, getString(R.string.ui_text), FloatingLyricsStyleStore.colorSummary(style().textColor)))
                addView(settingRow(activity, getString(R.string.ui_highlight), FloatingLyricsStyleStore.colorSummary(style().karaokeHighlightColor)))
                addView(settingRow(activity, getString(R.string.ui_background), onOff(style().backgroundEnabled)))
                addView(settingRow(activity, getString(R.string.ui_width), "${style().maxWidthPercent}%"))
                addView(settingRow(activity, getString(R.string.ui_content), localizedContentDisplayTitle(contentDisplayMode())))
                addView(settingRow(activity, getString(R.string.ui_range), localizedLineDisplayTitle(lineDisplayMode())))
                addView(settingRow(activity, getString(R.string.ui_alignment), localizedGravityTitle(style().gravity)))
                addView(settingRow(activity, getString(R.string.ui_animation), localizedSwitchAnimationTitle(switchAnimationMode())))
                addView(settingRow(activity, getString(R.string.ui_enhanced_lrc), if (karaokeLyricsEnabled()) getString(R.string.ui_preferred) else getString(R.string.ui_off)))
                addView(settingRow(activity, getString(R.string.ui_lyrics_offset), uiActions.currentLyricsOffsetSummary()))
                addView(settingRow(activity, getString(R.string.ui_locked), onOff(FloatingLyricsStyleStore.isLocked(activity))))
                addView(settingRow(activity, getString(R.string.ui_click_through), onOff(FloatingLyricsStyleStore.isClickThrough(activity))))
            }
        }
        addView(summaryButton)
    }

    contentScroll = scroll(activity, list)
    pageFrame.addView(contentScroll!!.apply {
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

    baseContentView = pageFrame
    rootFrame.addView(root.apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    })

    focusOverlay = FrameLayout(this).apply {
        visibility = View.GONE
        isClickable = true
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    rootFrame.addView(focusOverlay)

    return rootFrame
}
