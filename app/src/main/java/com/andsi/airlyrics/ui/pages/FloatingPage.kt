package com.andsi.airlyrics.ui.pages

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
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

    fun applyLyricsDisplaySettingsChanged() {
        lyricPreviewTextView?.text = previewLyricsText()
        lyricPreviewTextView?.maxLines = when (lineDisplayMode()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> 2
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> 4
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 6
        }
        previewBodyView?.requestLayout()
        notifyFloatingStyleChanged()
    }

    fun updatePreviewFold() {
        previewBodyView?.visibility = if (previewExpanded) View.VISIBLE else View.GONE
        previewToggleTextView?.text = if (previewExpanded) "收起" else "展开预览"
        previewCardView?.requestLayout()
    }

    fun refreshFloatingPreview() {
        val latestStyle = style()
        val summary = floatingPreviewSummary(latestStyle)
        summaryTextView?.text = summary
        lyricPreviewTextView?.apply {
            text = previewLyricsText()
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
            addView(spacer(activity, 6))
            addView(smallHint(activity, "调节时上方预览会即时刷新，正在显示的悬浮窗也会同步变化。"))
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
            lyricPreviewTextView = floatingPreviewText(previewLyricsText(), style()).apply {
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
        addView(
            sectionTitle(activity, 
                "悬浮窗设置",
                "上方负责看效果，下方分区调参数；颜色先选预设，再按需展开 RGB 细调。"
            )
        )

        addView(sectionTitle(activity, "外观", "主题、颜色、字号、背景、阴影和窗口尺寸。"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "皮肤预设",
                    subtitle = FloatingLyricsStyleStore.getPresetTitle(style().presetName),
                    mark = "✦",
                    onClick = { tile ->
                        openPanel(tile, "皮肤预设", "选择一套基础样式，再继续细调。") {
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
                    mark = "T",
                    onClick = { tile ->
                        openPanel(tile, "文字颜色", "先从标准色里快速选择，再展开 RGB 细调。") {
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
                    mark = "▣",
                    onClick = { tile ->
                        openPanel(tile, "背景气泡", "设置背景开关、颜色和透明度。") {
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
                    mark = "Aa",
                    onClick = { tile ->
                        openPanel(tile, "字体大小", "调整歌词文字的显示尺寸。") {
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
                    mark = "◌",
                    onClick = { tile ->
                        openPanel(tile, "阴影描边", "让白字在复杂背景上更清楚。") {
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
                    mark = "⌗",
                    onClick = { tile ->
                        openPanel(tile, "窗口布局", "调整最大宽度、圆角和内边距。") {
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

        addView(sectionTitle(activity, "歌词显示", "控制悬浮窗里显示原文、翻译，以及当前句前后的歌词范围。"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "显示内容",
                    subtitle = contentDisplayMode().title,
                    mark = "文",
                    onClick = { tile ->
                        openPanel(tile, "显示内容", "选择显示原文、翻译，或两者一起显示。") {
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
                            addView(smallHint(activity, "Musixmatch 当前通常只提供原文；选择仅翻译时，如果歌词没有翻译，后续显示层会给出提示。"))
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "显示范围",
                    subtitle = lineDisplayMode().title,
                    mark = "行",
                    onClick = { tile ->
                        openPanel(tile, "显示范围", "决定悬浮窗显示当前句，还是额外显示上一句 / 下一句。") {
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
                    mark = "≡",
                    onClick = { tile ->
                        openPanel(tile, "文字对齐", "控制歌词整体对齐方向。") {
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
                )
            )
        )
        addView(card(activity) {
            addView(bigText(activity, "当前显示方案"))
            addView(settingRow(activity, "内容", contentDisplayMode().title))
            addView(settingRow(activity, "范围", lineDisplayMode().title))
            addView(smallHint(activity, "这一步先保存显示偏好并更新预览；下一步会让悬浮窗真实歌词渲染读取这些设置。"))
        })

        addView(sectionTitle(activity, "动画效果", "歌词切换、淡入淡出、滚动和逐字高亮。"))
        addView(card(activity) {
            addView(bigText(activity, "动画预留区"))
            addView(settingRow(activity, "歌词切换动画", "预留"))
            addView(settingRow(activity, "逐字高亮 / 卡拉 OK", "预留"))
            addView(smallHint(activity, "当前先不做无效开关，只把模块位置留好，后面加动画时结构不会塌。"))
        })

        addView(sectionTitle(activity, "行为设置", "显示隐藏、拖动锁定、点击穿透和位置记忆。"))
        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "显示控制",
                    subtitle = floatingDisplaySummary(),
                    mark = "●",
                    onClick = { tile ->
                        openPanel(tile, "显示控制", "快速显示、隐藏、锁定或开启点击穿透。") {
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
        addView(
            card(activity) {
                addView(bigText(activity, "当前行为"))
                addView(settingRow(activity, "记住位置", "已开启"))
                addView(settingRow(activity, "拖动锁定", if (FloatingLyricsStyleStore.isLocked(activity)) "已开启" else "已关闭"))
                addView(settingRow(activity, "点击穿透", if (FloatingLyricsStyleStore.isClickThrough(activity)) "已开启" else "已关闭"))
                addView(smallHint(activity, "预览区展开/收起会自动记住；锁定只禁止拖动，穿透会让触摸事件落到下面的 App。"))
            }
        )
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
