package com.andsi.airlyrics.ui.pages

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
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
        setPadding(dp(20), dp(6), dp(20), dp(24))
    }

    val pageFrame = FrameLayout(this)
    var summaryTextView: TextView? = null
    var lyricPreviewTextView: TextView? = null
    var previewCardView: View? = null
    var baseContentView: View? = null
    var contentScroll: ScrollView? = null
    var focusOverlay: FrameLayout? = null
    var activeBubble: LinearLayout? = null
    var selectedTileView: View? = null
    var previewExpanded = FloatingLyricsStyleStore.isPreviewExpanded(this)
    var previewBodyView: View? = null
    var previewToggleTextView: TextView? = null

    fun style() = FloatingLyricsStyleStore.getStyle(this)
    fun contentDisplayMode() = LyricsSettingsStore.getContentDisplayMode(this)
    fun lineDisplayMode() = LyricsSettingsStore.getLineDisplayMode(this)
    fun switchAnimationMode() = LyricsSettingsStore.getSwitchAnimationMode(this)
    fun karaokeLyricsEnabled() = LyricsSettingsStore.isKaraokeLyricsEnabled(this)

    fun compactSectionTitle(title: String): TextView {
        return TextView(activity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
            setPadding(0, dp(12), 0, dp(10))
        }
    }

    fun lyricsDisplaySummary(): String {
        return "${contentDisplayMode().title} · ${lineDisplayMode().title}"
    }

    fun previewLine(original: String, translation: String): String {
        return when (contentDisplayMode()) {
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> "$original\n$translation"
            LyricsContentDisplayMode.ORIGINAL_ONLY -> original
            LyricsContentDisplayMode.TRANSLATION_ONLY -> translation
        }
    }

    fun previewLyricsText(): String {
        val previous = previewLine("昨日の夢を抱きしめて", "拥抱昨日的梦")
        val current = previewLine("夜に駆ける", "奔向夜晚")
        val next = previewLine("君の声を探している", "寻找你的声音")
        return when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> current
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(previous, current).joinToString("\n")
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(current, next).joinToString("\n")
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(previous, current, next).joinToString("\n")
        }
    }


    fun karaokePreviewText(): CharSequence {
        val text = "夜に駆ける\n高亮会覆盖已经唱到的文字"
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

    fun applyLyricsDisplaySettingsChanged() {
        lyricPreviewTextView?.text = if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText()
        lyricPreviewTextView?.maxLines = when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> 2
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> 4
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 6
        }
        previewBodyView?.requestLayout()
        notifyFloatingStyleChanged()
    }

    fun playPreviewSwitchAnimation() {
        val view = lyricPreviewTextView ?: return
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
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            LyricsSwitchAnimationMode.SLIDE_UP -> {
                view.alpha = 0f
                view.translationY = dp(8).toFloat()
                view.scaleX = 1f
                view.scaleY = 1f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(190L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            LyricsSwitchAnimationMode.SCALE_FADE -> {
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = 0.96f
                view.scaleY = 0.96f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    fun applyLyricsAnimationSettingsChanged() {
        playPreviewSwitchAnimation()
        notifyFloatingStyleChanged()
    }

    fun applyKaraokeLyricsChanged(enabled: Boolean) {
        LyricsSettingsStore.setKaraokeLyricsEnabled(activity, enabled)
        lyricPreviewTextView?.text = if (enabled) {
            karaokePreviewText()
        } else {
            previewLyricsText()
        }
        notifyFloatingStyleChanged()
        if (enabled && isQuickFloatingVisible() &&
            LyricsSettingsStore.getLyricsSource(activity) == LyricsSettingsStore.SOURCE_MUSIXMATCH
        ) {
            reloadFloatingLyricsFromOnline()
        }
    }

    fun updatePreviewFold() {
        previewBodyView?.visibility = if (previewExpanded) View.VISIBLE else View.GONE
        previewToggleTextView?.text = if (previewExpanded) "收起" else "展开预览"
        previewCardView?.requestLayout()
    }

    fun applyLyricsOffsetDelta(deltaMs: Long, statusView: TextView?) {
        val offset = uiActions.adjustLyricsOffsetForCurrentMedia(deltaMs)
        if (offset == null) {
            Toast.makeText(activity, "请先播放并选择一首歌", Toast.LENGTH_SHORT).show()
            statusView?.text = "等待当前音乐"
            return
        }
        statusView?.text = LyricsOffsetStore.description(offset)
    }

    fun resetLyricsOffset(statusView: TextView?) {
        if (!uiActions.resetLyricsOffsetForCurrentMedia()) {
            Toast.makeText(activity, "请先播放并选择一首歌", Toast.LENGTH_SHORT).show()
            statusView?.text = "等待当前音乐"
            return
        }
        statusView?.text = LyricsOffsetStore.description(0L)
    }

    fun refreshFloatingPreview() {
        val latestStyle = style()
        val summary = floatingPreviewSummary(latestStyle)
        summaryTextView?.text = summary
        lyricPreviewTextView?.apply {
            text = if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText()
            applyFloatingPreviewStyle(latestStyle)
        }
    }

    fun clearContentFocus() {
        selectedTileView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(150L)
            ?.start()
        selectedTileView = null
    }

    fun closePanel() {
        val overlay = focusOverlay ?: return
        val bubble = activeBubble
        bubble?.animate()
            ?.alpha(0f)
            ?.scaleX(0.86f)
            ?.scaleY(0.86f)
            ?.translationY(dp(10).toFloat())
            ?.setDuration(150L)
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
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(0.92f)
            .setDuration(130L)
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
        bubble.scaleX = 0.72f
        bubble.scaleY = 0.72f

        overlay.post {
            val overlayCenter = IntArray(2)
            val anchorCenter = IntArray(2)
            overlay.getLocationOnScreen(overlayCenter)
            anchor.getLocationOnScreen(anchorCenter)
            val startX = anchorCenter[0] + anchor.width / 2f - overlayCenter[0] - overlay.width / 2f
            val startY = anchorCenter[1] + anchor.height / 2f - overlayCenter[1] - overlay.height / 2f

            bubble.translationX = startX
            bubble.translationY = startY
            overlay.animate().alpha(1f).setDuration(120L).start()
            bubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(240L)
                .setInterpolator(OvershootInterpolator(0.72f))
                .start()
        }
    }

    previewCardView = floatingStatusPreviewCard(activity) {
        layoutTransition = softLayoutTransition()
        previewBodyView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            lyricPreviewTextView = floatingPreviewText(
                if (karaokeLyricsEnabled()) karaokePreviewText() else previewLyricsText(),
                style()
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(72)
                ).apply {
                    setMargins(dp(12), 0, dp(12), dp(6))
                }
                maxLines = when (lineDisplayMode()) {
                    LyricsLineDisplayMode.CURRENT_ONLY -> 2
                    LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
                    LyricsLineDisplayMode.CURRENT_AND_NEXT -> 4
                    LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 6
                }
                includeFontPadding = false
            }
            addView(lyricPreviewTextView!!)
        }
        addView(previewBodyView!!)

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            summaryTextView = normalText(activity, floatingPreviewSummary(style())).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            addView(summaryTextView!!)

            previewToggleTextView = TextView(activity).apply {
                text = "收起"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(colorAccent)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(colorSurfaceLight)
                }
                enableSoftPressFeedback(0.94f)
                setOnClickListener {
                    previewExpanded = !previewExpanded
                    FloatingLyricsStyleStore.setPreviewExpanded(activity, previewExpanded)
                    playTinyPulse(this)
                    updatePreviewFold()
                }
            }
            addView(previewToggleTextView!!)
        })
        updatePreviewFold()
    }
    root.addView(previewCardView!!)

    val list = pageContainer(activity).apply {
        setPadding(0, dp(4), 0, 0)
        addView(compactSectionTitle("外观"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "皮肤预设",
                    subtitle = FloatingLyricsStyleStore.getPresetTitle(style().presetName),
                    iconRes = R.drawable.ic_air_style,
                    onClick = { tile ->
                        openPanel(tile, "皮肤预设", "") {
                            addView(liveOptionGrid(
                                FloatingLyricsStyleStore.presets.map { preset ->
                                    KeyedOptionItem(
                                        key = preset.key,
                                        title = preset.title,
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
                    title = "文字颜色",
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().textColor),
                    iconRes = R.drawable.ic_air_text_color,
                    onClick = { tile ->
                        openPanel(tile, "文字颜色", "") {
                            addView(colorControl("文字", style().textColor) { color ->
                                applyFloatingTextColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "背景气泡",
                    subtitle = if (style().backgroundEnabled) "已开启" else "已关闭",
                    iconRes = R.drawable.ic_air_chat_bubble,
                    onClick = { tile ->
                        openPanel(tile, "背景气泡", "") {
                            val backgroundButton = actionButton(activity, if (style().backgroundEnabled) "背景：开启" else "背景：关闭") { }
                            backgroundButton.setOnClickListener {
                                val enabled = !FloatingLyricsStyleStore.getStyle(activity).backgroundEnabled
                                FloatingLyricsStyleStore.setBackgroundEnabled(activity, enabled)
                                notifyFloatingStyleChanged()
                                backgroundButton.text = if (enabled) "背景：开启" else "背景：关闭"
                                refreshFloatingPreview()
                            }
                            addView(backgroundButton)
                            addView(colorControl("背景", FloatingLyricsStyleStore.backgroundColorWithAlpha(style())) { color ->
                                applyFloatingBackgroundColor(color, refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "字体大小",
                    subtitle = "${style().textSizeSp.toInt()}sp",
                    iconRes = R.drawable.ic_air_format_size,
                    onClick = { tile ->
                        openPanel(tile, "字体大小", "") {
                            addView(sliderRow("大小", style().textSizeSp.toInt(), 14, 56, "sp") { value ->
                                applyFloatingTextSize(value.toFloat(), refreshPage = false)
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "阴影描边",
                    subtitle = "半径 ${style().shadowRadius.toInt()}",
                    iconRes = R.drawable.ic_air_shadow,
                    onClick = { tile ->
                        openPanel(tile, "阴影描边", "") {
                            addView(sliderRow("阴影半径", style().shadowRadius.toInt(), 0, 24, "") { value ->
                                FloatingLyricsStyleStore.setShadowRadius(activity, value.toFloat())
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(colorControl("阴影", style().shadowColor) { color ->
                                FloatingLyricsStyleStore.setShadowColor(activity, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "窗口布局",
                    subtitle = "宽度 ${style().maxWidthPercent}%",
                    iconRes = R.drawable.ic_air_pip,
                    onClick = { tile ->
                        openPanel(tile, "窗口布局", "") {
                            addView(sliderRow("最大宽度", style().maxWidthPercent, 45, 100, "%") { value ->
                                FloatingLyricsStyleStore.setMaxWidthPercent(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow("横向内边距", style().paddingHorizontalDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingHorizontal(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow("纵向内边距", style().paddingVerticalDp, 0, 28, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingVertical(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow("圆角", style().cornerRadiusDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setCornerRadius(activity, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )

        addView(compactSectionTitle("歌词显示"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "显示内容",
                    subtitle = contentDisplayMode().title,
                    iconRes = R.drawable.ic_air_lyrics,
                    onClick = { tile ->
                        openPanel(tile, "显示内容", "") {
                            addView(liveOptionGrid(
                                LyricsContentDisplayMode.entries.map { mode ->
                                    KeyedOptionItem(
                                        key = mode.key,
                                        title = mode.title,
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
                    title = "显示范围",
                    subtitle = lineDisplayMode().title,
                    iconRes = R.drawable.ic_air_line_spacing,
                    onClick = { tile ->
                        openPanel(tile, "显示范围", "") {
                            addView(liveOptionGrid(
                                LyricsLineDisplayMode.entries.map { mode ->
                                    KeyedOptionItem(
                                        key = mode.key,
                                        title = mode.title,
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
                    title = "文字对齐",
                    subtitle = FloatingLyricsStyleStore.getGravityTitle(style().gravity),
                    iconRes = R.drawable.ic_air_align_center,
                    onClick = { tile ->
                        openPanel(tile, "文字对齐", "") {
                            addView(liveOptionGrid(listOf(
                                KeyedOptionItem("left", "左对齐", style().gravity == (Gravity.START or Gravity.CENTER_VERTICAL)) {
                                    applyFloatingGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                                    refreshFloatingPreview()
                                },
                                KeyedOptionItem("center", "居中", style().gravity == Gravity.CENTER) {
                                    applyFloatingGravity(Gravity.CENTER)
                                    refreshFloatingPreview()
                                },
                                KeyedOptionItem("right", "右对齐", style().gravity == (Gravity.END or Gravity.CENTER_VERTICAL)) {
                                    applyFloatingGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                                    refreshFloatingPreview()
                                }
                            )))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "歌词偏移",
                    subtitle = uiActions.currentLyricsOffsetSummary(),
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, "歌词偏移", "按当前音乐保存，不修改原始歌词文件。歌词慢了点提前，歌词快了点延后。") {
                            val statusText = normalText(activity, uiActions.currentLyricsOffsetSummary()).apply {
                                textSize = 15f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(colorTextStrong)
                                setPadding(0, dp(2), 0, dp(8))
                            }
                            addView(statusText)
                            addView(horizontalButtons(activity,
                                "提前 1s" to { applyLyricsOffsetDelta(1_000L, statusText) },
                                "提前 0.1s" to { applyLyricsOffsetDelta(100L, statusText) }
                            ))
                            addView(horizontalButtons(activity,
                                "延后 0.1s" to { applyLyricsOffsetDelta(-100L, statusText) },
                                "延后 1s" to { applyLyricsOffsetDelta(-1_000L, statusText) }
                            ))
                            addView(actionButton(activity, "重置当前歌曲偏移") {
                                resetLyricsOffset(statusText)
                            })
                        }
                    }
                )
            )
        )
        addView(compactSectionTitle("动画效果"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "歌词切换动画",
                    subtitle = switchAnimationMode().title,
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, "歌词切换动画", "") {
                            addView(liveOptionGrid(
                                LyricsSwitchAnimationMode.entries.map { mode ->
                                    KeyedOptionItem(
                                        key = mode.key,
                                        title = mode.title,
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
                    title = "逐字歌词",
                    subtitle = if (karaokeLyricsEnabled()) "开启 · 状态在歌词获取页查看" else "关闭",
                    iconRes = R.drawable.ic_air_motion,
                    onClick = { tile ->
                        openPanel(tile, "逐字歌词", "使用 Musixmatch 逐字歌词时生效；当前音乐是否支持可在 设置 → 歌词获取 → 当前音乐歌词 中查看。") {
                            addView(liveOptionGrid(listOf(
                                KeyedOptionItem("karaoke_on", "开启", karaokeLyricsEnabled()) {
                                    applyKaraokeLyricsChanged(true)
                                },
                                KeyedOptionItem("karaoke_off", "关闭", !karaokeLyricsEnabled()) {
                                    applyKaraokeLyricsChanged(false)
                                }
                            )))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "高亮颜色",
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().karaokeHighlightColor),
                    iconRes = R.drawable.ic_air_text_color,
                    onClick = { tile ->
                        openPanel(tile, "逐字高亮颜色", "用于左到右流动的歌词高亮；建议和普通文字颜色拉开差异。") {
                            addView(colorControl("高亮", style().karaokeHighlightColor) { color ->
                                FloatingLyricsStyleStore.setKaraokeHighlightColor(activity, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )
        addView(compactSectionTitle("行为设置"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "显示控制",
                    subtitle = floatingDisplaySummary(),
                    iconRes = R.drawable.ic_air_visibility,
                    onClick = { tile ->
                        openPanel(tile, "显示控制", "") {
                            addView(horizontalButtons(activity, 
                                "显示" to { uiActions.showFloatingLyrics() },
                                "隐藏" to { uiActions.hideFloatingLyrics() }
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
        val summaryButton = actionButton(activity, "查看当前配置") { }
        summaryButton.setOnClickListener {
            openPanel(summaryButton, "当前配置", "") {
                addView(settingRow(activity, "皮肤", FloatingLyricsStyleStore.getPresetTitle(style().presetName)))
                addView(settingRow(activity, "字号", "${style().textSizeSp.toInt()}sp"))
                addView(settingRow(activity, "文字", FloatingLyricsStyleStore.colorSummary(style().textColor)))
                addView(settingRow(activity, "高亮", FloatingLyricsStyleStore.colorSummary(style().karaokeHighlightColor)))
                addView(settingRow(activity, "背景", if (style().backgroundEnabled) "开启" else "关闭"))
                addView(settingRow(activity, "宽度", "${style().maxWidthPercent}%"))
                addView(settingRow(activity, "内容", contentDisplayMode().title))
                addView(settingRow(activity, "范围", lineDisplayMode().title))
                addView(settingRow(activity, "对齐", FloatingLyricsStyleStore.getGravityTitle(style().gravity)))
                addView(settingRow(activity, "动画", switchAnimationMode().title))
                addView(settingRow(activity, "逐字歌词", if (karaokeLyricsEnabled()) "开启" else "关闭"))
                addView(settingRow(activity, "歌词偏移", uiActions.currentLyricsOffsetSummary()))
                addView(settingRow(activity, "锁定", if (FloatingLyricsStyleStore.isLocked(activity)) "开启" else "关闭"))
                addView(settingRow(activity, "穿透", if (FloatingLyricsStyleStore.isClickThrough(activity)) "开启" else "关闭"))
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
