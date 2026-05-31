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
import com.andsi.airlyrics.i18n.tr
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

    fun onOff(value: Boolean): String = if (value) tr("开启", "On") else tr("关闭", "Off")
    fun localizedPresetTitle(key: String): String = localizedFloatingPresetTitle(key)
    fun localizedGravityTitle(gravity: Int): String = localizedFloatingGravityTitle(gravity)
    fun style() = FloatingLyricsStyleStore.getStyle(this)
    fun contentDisplayMode() = LyricsSettingsStore.getContentDisplayMode(this)
    fun lineDisplayMode() = LyricsSettingsStore.getLineDisplayMode(this)
    fun switchAnimationMode() = LyricsSettingsStore.getSwitchAnimationMode(this)
    fun karaokeLyricsEnabled() = LyricsSettingsStore.isKaraokeLyricsEnabled(this)
    fun wordLyricsSubtitle(): String = if (karaokeLyricsEnabled()) tr("优先显示 · 本地 enhanced LRC", "Local enhanced LRC") else tr("关闭", "Off")
    var previewExpanded = FloatingLyricsStyleStore.isPreviewExpanded(this)

    fun lyricsDisplaySummary(): String {
        return "${localizedContentDisplayTitle(contentDisplayMode())} · ${localizedLineDisplayTitle(lineDisplayMode())}"
    }

    fun previewLyricsText(): String {
        val previous = previewLineText(contentDisplayMode(), tr("上一行歌词预览", "Previous lyric preview"), "Previous lyric preview")
        val current = previewLineText(contentDisplayMode(), tr("这是一行歌词预览", "This is a lyric preview"), "This is a lyric preview")
        val next = previewLineText(contentDisplayMode(), tr("下一行歌词预览", "Next lyric preview"), "Next lyric preview")
        return when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> current
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(previous, current).joinToString("\n")
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(current, next).joinToString("\n")
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(previous, current, next).joinToString("\n")
        }
    }


    fun karaokePreviewText(): CharSequence {
        val text = tr("这是一行歌词预览\nThis is a lyric preview", "This is a lyric preview")
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
        updateFloatingTileSubtitle(tr("皮肤预设", "Skin preset"), localizedPresetTitle(latestStyle.presetName))
        updateFloatingTileSubtitle(tr("文字颜色", "Text color"), FloatingLyricsStyleStore.colorSummary(latestStyle.textColor))
        updateFloatingTileSubtitle(tr("背景气泡", "Background bubble"), if (latestStyle.backgroundEnabled) tr("已开启", "On") else tr("已关闭", "Off"))
        updateFloatingTileSubtitle(tr("字体大小", "Font size"), "${latestStyle.textSizeSp.toInt()}sp")
        updateFloatingTileSubtitle(tr("阴影描边", "Shadow stroke"), tr("半径", "Radius") + " ${latestStyle.shadowRadius.toInt()}")
        updateFloatingTileSubtitle(tr("窗口布局", "Window layout"), tr("宽度", "Width") + " ${latestStyle.maxWidthPercent}%")
        updateFloatingTileSubtitle(tr("显示内容", "Content"), localizedContentDisplayTitle(contentDisplayMode()))
        updateFloatingTileSubtitle(tr("显示范围", "Line range"), localizedLineDisplayTitle(lineDisplayMode()))
        updateFloatingTileSubtitle(tr("文字对齐", "Text alignment"), localizedGravityTitle(latestStyle.gravity))
        updateFloatingTileSubtitle(tr("歌词偏移", "Lyrics offset"), uiActions.currentLyricsOffsetSummary())
        updateFloatingTileSubtitle(tr("歌词切换动画", "Switch animation"), localizedSwitchAnimationTitle(switchAnimationMode()))
        updateFloatingTileSubtitle(tr("本地逐字歌词", "Word LRC"), wordLyricsSubtitle())
        updateFloatingTileSubtitle(tr("高亮颜色", "Highlight color"), FloatingLyricsStyleStore.colorSummary(latestStyle.karaokeHighlightColor))
        updateFloatingTileSubtitle(tr("显示控制", "Display control"), floatingDisplaySummary())
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
            Toast.makeText(activity, tr("请先播放并选择一首歌", "Please play and select a song first"), Toast.LENGTH_SHORT).show()
            statusView?.text = tr("等待当前音乐", "Waiting for current song")
            return
        }
        statusView?.text = localizedOffsetDescription(offset)
        refreshFloatingSettingTiles()
    }

    fun resetLyricsOffset(statusView: TextView?) {
        if (!uiActions.resetLyricsOffsetForCurrentMedia()) {
            Toast.makeText(activity, tr("请先播放并选择一首歌", "Please play and select a song first"), Toast.LENGTH_SHORT).show()
            statusView?.text = tr("等待当前音乐", "Waiting for current song")
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
        addView(floatingSectionTitle(tr("外观", "Appearance")))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = tr("皮肤预设", "Skin preset"),
                    subtitle = localizedPresetTitle(style().presetName),
                    iconRes = R.drawable.ic_air_style,
                    onClick = { tile ->
                        openPanel(tile, tr("皮肤预设", "Skin preset"), "") {
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
                    title = tr("文字颜色", "Text color"),
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().textColor),
                    iconRes = R.drawable.ic_air_text_color,
                    onClick = { tile ->
                        openPanel(tile, tr("文字颜色", "Text color"), "") {
                            addView(colorControl(tr("文字", "Text"), style().textColor) { color ->
                                applyFloatingTextColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = tr("背景气泡", "Background bubble"),
                    subtitle = if (style().backgroundEnabled) tr("已开启", "On") else tr("已关闭", "Off"),
                    iconRes = R.drawable.ic_air_chat_bubble,
                    onClick = { tile ->
                        openPanel(tile, tr("背景气泡", "Background bubble"), "") {
                            val backgroundButton = actionButton(activity, if (style().backgroundEnabled) tr("背景：开启", "Background: on") else tr("背景：关闭", "Background: off")) { }
                            backgroundButton.setOnClickListener {
                                val enabled = !FloatingLyricsStyleStore.getStyle(activity).backgroundEnabled
                                FloatingLyricsStyleStore.setBackgroundEnabled(activity, enabled)
                                notifyFloatingStyleChanged()
                                backgroundButton.text = if (enabled) tr("背景：开启", "Background: on") else tr("背景：关闭", "Background: off")
                                refreshFloatingPreview()
                            }
                            addView(backgroundButton)
                            addView(colorControl(tr("背景", "Background"), FloatingLyricsStyleStore.backgroundColorWithAlpha(style())) { color ->
                                applyFloatingBackgroundColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = tr("字体大小", "Font size"),
                    subtitle = "${style().textSizeSp.toInt()}sp",
                    iconRes = R.drawable.ic_air_format_size,
                    onClick = { tile ->
                        openPanel(tile, tr("字体大小", "Font size"), "") {
                            addView(sliderRow(tr("大小", "Size"), style().textSizeSp.toInt(), 14, 56, "sp") { value ->
                                applyFloatingTextSize(value.toFloat(), refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = tr("阴影描边", "Shadow stroke"),
                    subtitle = tr("半径", "Radius") + " ${style().shadowRadius.toInt()}",
                    iconRes = R.drawable.ic_air_shadow,
                    onClick = { tile ->
                        openPanel(tile, tr("阴影描边", "Shadow stroke"), "") {
                            addView(sliderRow(tr("阴影半径", "Shadow radius"), style().shadowRadius.toInt(), 0, 24, "") { value ->
                                FloatingLyricsStyleStore.setShadowRadius(activity, value.toFloat())
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(colorControl(tr("阴影", "Shadow"), style().shadowColor) { color ->
                                FloatingLyricsStyleStore.setShadowColor(activity, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = tr("窗口布局", "Window layout"),
                    subtitle = tr("宽度", "Width") + " ${style().maxWidthPercent}%",
                    iconRes = R.drawable.ic_air_pip,
                    onClick = { tile ->
                        openPanel(tile, tr("窗口布局", "Window layout"), "") {
                            addView(sliderRow(tr("最大宽度", "Max width"), style().maxWidthPercent, 45, 100, "%") { value ->
                                FloatingLyricsStyleStore.setMaxWidthPercent(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow(tr("横向内边距", "Horizontal padding"), style().paddingHorizontalDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingHorizontal(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow(tr("纵向内边距", "Vertical padding"), style().paddingVerticalDp, 0, 28, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingVertical(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow(tr("圆角", "Corner radius"), style().cornerRadiusDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setCornerRadius(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )

        addView(floatingSectionTitle(tr("歌词显示", "Lyrics display")))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = tr("显示内容", "Content"),
                    subtitle = localizedContentDisplayTitle(contentDisplayMode()),
                    iconRes = R.drawable.ic_air_lyrics,
                    onClick = { tile ->
                        openPanel(tile, tr("显示内容", "Content"), "") {
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
                    title = tr("显示范围", "Line range"),
                    subtitle = localizedLineDisplayTitle(lineDisplayMode()),
                    iconRes = R.drawable.ic_air_line_spacing,
                    onClick = { tile ->
                        openPanel(tile, tr("显示范围", "Line range"), "") {
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
                    title = tr("文字对齐", "Text alignment"),
                    subtitle = localizedGravityTitle(style().gravity),
                    iconRes = R.drawable.ic_air_align_center,
                    onClick = { tile ->
                        openPanel(tile, tr("文字对齐", "Text alignment"), "") {
                            addView(liveOptionGrid(listOf(
                                KeyedOptionItem("left", tr("左对齐", "Left"), style().gravity == (Gravity.START or Gravity.CENTER_VERTICAL)) {
                                    applyFloatingGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                                    refreshFloatingPreview()
                                },
                                KeyedOptionItem("center", tr("居中", "Center"), style().gravity == Gravity.CENTER) {
                                    applyFloatingGravity(Gravity.CENTER)
                                    refreshFloatingPreview()
                                },
                                KeyedOptionItem("right", tr("右对齐", "Right"), style().gravity == (Gravity.END or Gravity.CENTER_VERTICAL)) {
                                    applyFloatingGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                                    refreshFloatingPreview()
                                }
                            )))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = tr("歌词偏移", "Lyrics offset"),
                    subtitle = uiActions.currentLyricsOffsetSummary(),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, tr("歌词偏移", "Lyrics offset"), "") {
                            val statusText = normalText(activity, uiActions.currentLyricsOffsetSummary()).apply {
                                textSize = FloatingPageTokens.OFFSET_STATUS_TEXT_SP
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(colorTextStrong)
                                setPadding(0, dp(FloatingPageTokens.OFFSET_STATUS_PADDING_TOP_DP), 0, dp(FloatingPageTokens.OFFSET_STATUS_PADDING_BOTTOM_DP))
                            }
                            addView(statusText)
                            addView(horizontalButtons(activity,
                                tr("延后 1s", "Delay 1s") to { applyLyricsOffsetDelta(-1_000L, statusText) },
                                tr("提前 1s", "Advance 1s") to { applyLyricsOffsetDelta(1_000L, statusText) }
                            ))
                            addView(horizontalButtons(activity,
                                tr("延后 0.1s", "Delay 0.1s") to { applyLyricsOffsetDelta(-100L, statusText) },
                                tr("提前 0.1s", "Advance 0.1s") to { applyLyricsOffsetDelta(100L, statusText) }
                            ))
                            addView(actionButton(activity, tr("重置当前歌曲偏移", "Reset current song offset")) {
                                resetLyricsOffset(statusText)
                            })
                        }
                    }
                )
            )
        )
        addView(floatingSectionTitle(tr("动画效果", "Animation")))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = tr("歌词切换动画", "Switch animation"),
                    subtitle = localizedSwitchAnimationTitle(switchAnimationMode()),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, tr("歌词切换动画", "Switch animation"), "") {
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
                    title = tr("本地逐字歌词", "Word LRC"),
                    subtitle = wordLyricsSubtitle(),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, tr("本地逐字歌词", "Word LRC"), "") {
                            addView(liveOptionGrid(listOf(
                                KeyedOptionItem("karaoke_on", tr("开启", "On"), karaokeLyricsEnabled()) {
                                    applyKaraokeLyricsChanged(true)
                                },
                                KeyedOptionItem("karaoke_off", tr("关闭", "Off"), !karaokeLyricsEnabled()) {
                                    applyKaraokeLyricsChanged(false)
                                }
                            )))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = tr("高亮颜色", "Highlight color"),
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().karaokeHighlightColor),
                    iconRes = R.drawable.ic_air_text_color,
                    onClick = { tile ->
                        openPanel(tile, tr("逐字高亮颜色", "Word color"), "") {
                            addView(colorControl(tr("高亮", "Highlight"), style().karaokeHighlightColor) { color ->
                                FloatingLyricsStyleStore.setKaraokeHighlightColor(activity, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )
        addView(floatingSectionTitle(tr("行为设置", "Behavior")))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = tr("显示控制", "Display control"),
                    subtitle = floatingDisplaySummary(),
                    iconRes = R.drawable.ic_air_visibility,
                    onClick = { tile ->
                        openPanel(tile, tr("显示控制", "Display control"), "") {
                            addView(horizontalButtons(activity, 
                                tr("显示", "Show") to { uiActions.showFloatingLyrics() },
                                tr("隐藏", "Hide") to { uiActions.hideFloatingLyrics() }
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
        val summaryButton = actionButton(activity, tr("查看当前配置", "View current setup")) { }
        summaryButton.setOnClickListener {
            openPanel(summaryButton, tr("当前配置", "Current setup"), "") {
                addView(settingRow(activity, tr("皮肤", "Skin"), localizedPresetTitle(style().presetName)))
                addView(settingRow(activity, tr("字号", "Font size"), "${style().textSizeSp.toInt()}sp"))
                addView(settingRow(activity, tr("文字", "Text"), FloatingLyricsStyleStore.colorSummary(style().textColor)))
                addView(settingRow(activity, tr("高亮", "Highlight"), FloatingLyricsStyleStore.colorSummary(style().karaokeHighlightColor)))
                addView(settingRow(activity, tr("背景", "Background"), onOff(style().backgroundEnabled)))
                addView(settingRow(activity, tr("宽度", "Width"), "${style().maxWidthPercent}%"))
                addView(settingRow(activity, tr("内容", "Content"), localizedContentDisplayTitle(contentDisplayMode())))
                addView(settingRow(activity, tr("范围", "Range"), localizedLineDisplayTitle(lineDisplayMode())))
                addView(settingRow(activity, tr("对齐", "Alignment"), localizedGravityTitle(style().gravity)))
                addView(settingRow(activity, tr("动画", "Animation"), localizedSwitchAnimationTitle(switchAnimationMode())))
                addView(settingRow(activity, tr("本地逐字歌词", "Word LRC"), if (karaokeLyricsEnabled()) tr("优先显示", "Preferred") else tr("关闭", "Off")))
                addView(settingRow(activity, tr("歌词偏移", "Lyrics offset"), uiActions.currentLyricsOffsetSummary()))
                addView(settingRow(activity, tr("锁定", "Locked"), onOff(FloatingLyricsStyleStore.isLocked(activity))))
                addView(settingRow(activity, tr("穿透", "Click-through"), onOff(FloatingLyricsStyleStore.isClickThrough(activity))))
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
