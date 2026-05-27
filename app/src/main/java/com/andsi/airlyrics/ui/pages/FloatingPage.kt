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
import com.andsi.airlyrics.core.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.settings.FloatingLyricsStyleStore
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.FloatingSettingTile
import com.andsi.airlyrics.MainActivity.KeyedOptionItem
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun MainActivity.createFloatingPage(): View {
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
    var previewExpanded = true
    var previewBodyView: View? = null
    var previewToggleTextView: TextView? = null

    fun style() = FloatingLyricsStyleStore.getStyle(this)

    fun updatePreviewFold() {
        previewBodyView?.visibility = if (previewExpanded) View.VISIBLE else View.GONE
        previewToggleTextView?.text = if (previewExpanded) "收起" else "展开预览"
        previewCardView?.requestLayout()
    }

    fun refreshFloatingPreview() {
        val latestStyle = style()
        val summary = floatingPreviewSummary(latestStyle)
        summaryTextView?.text = summary
        lyricPreviewTextView?.applyFloatingPreviewStyle(latestStyle)
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
            addView(spacer(6))
            addView(smallHint("调节时上方预览会即时刷新，正在显示的悬浮窗也会同步变化。"))
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

    previewCardView = floatingStatusPreviewCard {
        layoutTransition = softLayoutTransition()
        previewBodyView = LinearLayout(this@createFloatingPage).apply {
            orientation = LinearLayout.VERTICAL
            lyricPreviewTextView = floatingPreviewText("夜に駆ける\n奔向夜晚", style()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(72)
                ).apply {
                    setMargins(dp(12), 0, dp(12), dp(6))
                }
                maxLines = 2
                includeFontPadding = false
            }
            addView(lyricPreviewTextView!!)
        }
        addView(previewBodyView!!)

        addView(LinearLayout(this@createFloatingPage).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            summaryTextView = normalText(floatingPreviewSummary(style())).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            addView(summaryTextView!!)

            previewToggleTextView = TextView(this@createFloatingPage).apply {
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
                    playTinyPulse(this)
                    updatePreviewFold()
                }
            }
            addView(previewToggleTextView!!)
        })
        updatePreviewFold()
    }
    root.addView(previewCardView!!)

    val list = pageContainer().apply {
        setPadding(0, dp(4), 0, 0)
        addView(
            sectionTitle(
                "悬浮窗设置",
                "点击方格后，方格会像轻软气泡一样放大成调节卡片。"
            )
        )

        addView(
            settingGrid(
                FloatingSettingTile(
                    title = "显示控制",
                    subtitle = floatingDisplaySummary(),
                    mark = "●",
                    onClick = { tile ->
                        openPanel(tile, "显示控制", "快速显示、隐藏、锁定或开启点击穿透。") {
                            addView(horizontalButtons(
                                "显示" to { showFloatingLyrics() },
                                "隐藏" to { hideFloatingLyrics() }
                            ))

                            val lockButton = actionButton(floatingLockButtonText()) { }
                            lockButton.setOnClickListener {
                                toggleLock()
                                lockButton.text = floatingLockButtonText()
                                refreshFloatingPreview()
                            }
                            addView(lockButton)

                            val clickThroughButton = actionButton(floatingClickThroughButtonText()) { }
                            clickThroughButton.setOnClickListener {
                                toggleClickThrough()
                                clickThroughButton.text = floatingClickThroughButtonText()
                                refreshFloatingPreview()
                            }
                            addView(clickThroughButton)
                        }
                    }
                ),
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
                    title = "文字颜色",
                    subtitle = FloatingLyricsStyleStore.colorSummary(style().textColor),
                    mark = "T",
                    onClick = { tile ->
                        openPanel(tile, "文字颜色", "设置歌词本体颜色。") {
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
                            val backgroundButton = actionButton(if (style().backgroundEnabled) "背景：开启" else "背景：关闭") { }
                            backgroundButton.setOnClickListener {
                                val enabled = !FloatingLyricsStyleStore.getStyle(this@createFloatingPage).backgroundEnabled
                                FloatingLyricsStyleStore.setBackgroundEnabled(this@createFloatingPage, enabled)
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
                    title = "文字对齐",
                    subtitle = FloatingLyricsStyleStore.getGravityTitle(style().gravity),
                    mark = "≡",
                    onClick = { tile ->
                        openPanel(tile, "文字对齐", "控制两行歌词的整体对齐方向。") {
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
                    title = "阴影描边",
                    subtitle = "半径 ${style().shadowRadius.toInt()}",
                    mark = "◌",
                    onClick = { tile ->
                        openPanel(tile, "阴影描边", "让白字在复杂背景上更清楚。") {
                            addView(sliderRow("阴影半径", style().shadowRadius.toInt(), 0, 24, "") { value ->
                                FloatingLyricsStyleStore.setShadowRadius(this@createFloatingPage, value.toFloat())
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(colorControl("阴影", style().shadowColor) { color ->
                                FloatingLyricsStyleStore.setShadowColor(this@createFloatingPage, color)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                ),
                FloatingSettingTile(
                    title = "高级布局",
                    subtitle = "宽度 ${style().maxWidthPercent}%",
                    mark = "⌗",
                    onClick = { tile ->
                        openPanel(tile, "高级布局", "调整最大宽度、圆角和内边距。") {
                            addView(sliderRow("最大宽度", style().maxWidthPercent, 45, 100, "%") { value ->
                                FloatingLyricsStyleStore.setMaxWidthPercent(this@createFloatingPage, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow("横向内边距", style().paddingHorizontalDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingHorizontal(this@createFloatingPage, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow("纵向内边距", style().paddingVerticalDp, 0, 28, "dp") { value ->
                                FloatingLyricsStyleStore.setPaddingVertical(this@createFloatingPage, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                            addView(sliderRow("圆角", style().cornerRadiusDp, 0, 36, "dp") { value ->
                                FloatingLyricsStyleStore.setCornerRadius(this@createFloatingPage, value)
                                notifyFloatingStyleChanged()
                                refreshFloatingPreview()
                            })
                        }
                    }
                )
            )
        )

        addView(
            card {
                addView(bigText("当前行为"))
                addView(settingRow("记住位置", "已开启"))
                addView(settingRow("拖动锁定", if (FloatingLyricsStyleStore.isLocked(this@createFloatingPage)) "已开启" else "已关闭"))
                addView(settingRow("点击穿透", if (FloatingLyricsStyleStore.isClickThrough(this@createFloatingPage)) "已开启" else "已关闭"))
                addView(smallHint("锁定只禁止拖动；穿透会让触摸事件落到下面的 App。"))
            }
        )
    }

    contentScroll = scroll(list)
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
