package com.andsi.airlyrics

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.sin
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private enum class Page { MEDIA, FLOATING, SETTINGS }
    private enum class SettingsSubPage { HOME, SYSTEM, LOCAL_LYRICS, ABOUT }

    private var locked = false
    private var clickThrough = false
    private var currentPage = Page.MEDIA
    private var settingsSubPage = SettingsSubPage.HOME
    private var contentContainer: FrameLayout? = null
    private val tabViews = mutableMapOf<Page, TextView>()
    private var tabRow: LinearLayout? = null
    private var tabHighlight: WaterTabHighlightView? = null
    private var quickFloatingVisible = false
    private val pageScrollY = mutableMapOf<Page, Int>()
    private var renderedPage = Page.MEDIA
    private var renderedSettingsSubPage = SettingsSubPage.HOME

    private enum class RefreshState { IDLE, REFRESHING, DONE }

    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private var mediaRefreshState = RefreshState.IDLE

    private val importLyricsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val intent = Intent(this, FloatingLyricsService::class.java).apply {
                action = FloatingLyricsService.ACTION_IMPORT_LYRICS
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startLyricsService(intent)
        }

    private val selectLyricsDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            LyricsStorage.saveLyricsDirUri(this, uri)
            Toast.makeText(this, "已设置歌词保存目录", Toast.LENGTH_LONG).show()
            renderCurrentPage()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                "通知权限已开启"
            } else {
                "通知权限未开启，前台服务通知可能无法显示"
            }

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            renderCurrentPage()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = isQuickFloatingVisible()
        applySystemBarsTheme()
        setContentView(createMainView())
        autoSelectMediaSourceOnceIfNeeded()
        renderCurrentPage()
    }

    override fun onResume() {
        super.onResume()
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = isQuickFloatingVisible()
        renderCurrentPage()
    }

    private fun createMainView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground)
        }

        val topSafeArea = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        }

        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(topSafeArea)
        root.addView(contentContainer)
        root.addView(createBottomTabs())
        return root
    }

    private fun createBottomTabs(): View {
        val shell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(86)
            )
            setPadding(dp(12), dp(8), dp(12), dp(12))
            clipToPadding = false
            clipChildren = false
            background = GradientDrawable().apply {
                setColor(colorSurface)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f,
                    0f, 0f
                )
            }
        }

        tabHighlight = WaterTabHighlightView(this, colorAccent).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipToPadding = false
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        tabRow = bar

        addTab(bar, Page.MEDIA, "媒体流")
        addTab(bar, Page.FLOATING, "悬浮窗")
        addTab(bar, Page.SETTINGS, "设置")

        shell.addView(tabHighlight)
        shell.addView(bar)
        return shell
    }

    private fun addTab(parent: LinearLayout, page: Page, title: String) {
        val slot = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            clipToPadding = false
            clipChildren = false
            isClickable = true
            enableSoftPressFeedback(0.97f)
            setOnClickListener {
                if (page == Page.FLOATING && currentPage == Page.FLOATING) {
                    toggleFloatingFromNav()
                    return@setOnClickListener
                }
                if (currentPage == page && page != Page.SETTINGS) return@setOnClickListener
                currentPage = page
                if (page == Page.SETTINGS) {
                    settingsSubPage = SettingsSubPage.HOME
                }
                renderCurrentPage()
            }
        }

        val tab = TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(18), dp(8), dp(18), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        tabViews[page] = tab
        slot.addView(tab)
        parent.addView(slot)
    }

    private fun renderCurrentPage() {
        val container = contentContainer ?: return
        (container.getChildAt(0) as? ScrollView)?.let { scrollView ->
            pageScrollY[renderedPage] = scrollView.scrollY
        }

        val oldPage = renderedPage
        val oldSubPage = renderedSettingsSubPage
        val shouldAnimate = container.childCount > 0 && (currentPage != oldPage || settingsSubPage != oldSubPage)
        val slideFromRight = when {
            currentPage != oldPage -> currentPage.ordinal > oldPage.ordinal
            currentPage == Page.SETTINGS -> settingsSubPage.ordinal > oldSubPage.ordinal
            else -> true
        }

        container.removeAllViews()
        updateTabs()

        val pageView = when (currentPage) {
            Page.MEDIA -> createMediaPage()
            Page.FLOATING -> createFloatingPage()
            Page.SETTINGS -> createSettingsPage()
        }

        val restoreY = pageScrollY[currentPage] ?: 0
        container.addView(pageView)
        if (shouldAnimate) animatePageEnter(pageView, slideFromRight)
        renderedPage = currentPage
        renderedSettingsSubPage = settingsSubPage

        (pageView as? ScrollView)?.let { scrollView ->
            scrollView.scrollTo(0, restoreY)
            scrollView.post {
                scrollView.scrollTo(0, restoreY)
            }
        }
    }

    private fun quickFloatingTabText(visible: Boolean): SpannableString {
        val icon = if (visible) "×" else "♪"
        val label = if (visible) "隐藏" else "显示"
        return SpannableString("$icon\n$label").apply {
            setSpan(AbsoluteSizeSpan(24, true), 0, icon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(10, true), icon.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun measureTabTextWidth(tab: TextView): Float {
        val lines = tab.text.toString().split('\n')
        return lines.maxOfOrNull { tab.paint.measureText(it) } ?: tab.paint.measureText(tab.text.toString())
    }

    private fun updateTabs() {
        tabViews.forEach { (page, view) ->
            val selected = page == currentPage
            val quickControlSelected = page == Page.FLOATING && selected
            val targetText: CharSequence = if (quickControlSelected) {
                quickFloatingTabText(quickFloatingVisible)
            } else {
                when (page) {
                    Page.MEDIA -> "媒体流"
                    Page.FLOATING -> "悬浮窗"
                    Page.SETTINGS -> "设置"
                }
            }
            if (view.text.toString() != targetText.toString()) {
                view.animate().cancel()
                view.alpha = 0.55f
                view.scaleX = 0.92f
                view.scaleY = 0.92f
                view.text = targetText
            }
            view.textSize = 15f
            view.setLineSpacing(0f, 0.92f)
            view.setTextColor(if (selected) Color.WHITE else colorTextMuted)
            view.background = null
            view.animate()
                .scaleX(if (quickControlSelected) 1.14f else if (selected) 1.02f else 1f)
                .scaleY(if (quickControlSelected) 1.14f else if (selected) 1.02f else 1f)
                .alpha(if (selected) 1f else 0.86f)
                .setDuration(190L)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
        }

        val selectedTab = tabViews[currentPage] ?: return
        selectedTab.post {
            val highlight = tabHighlight ?: return@post
            val textWidth = measureTabTextWidth(selectedTab)
            val horizontalPadding = if (currentPage == Page.FLOATING) dp(62) else dp(58)
            val targetWidth = (textWidth + horizontalPadding).coerceIn(
                dp(104).toFloat(),
                if (currentPage == Page.FLOATING) dp(136).toFloat() else dp(144).toFloat()
            )
            val targetHeight = if (currentPage == Page.FLOATING) dp(54).toFloat() else dp(48).toFloat()

            val tabLocation = IntArray(2)
            val highlightLocation = IntArray(2)
            selectedTab.getLocationInWindow(tabLocation)
            highlight.getLocationInWindow(highlightLocation)

            val centerX = tabLocation[0] - highlightLocation[0] + selectedTab.width / 2f
            val centerY = tabLocation[1] - highlightLocation[1] + selectedTab.height / 2f
            highlight.moveTo(
                targetCenterX = centerX,
                targetCenterY = centerY,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                animate = highlight.hasPosition
            )
        }
    }

    private fun createMediaPage(): View {
        val container = pageContainer()
        val controllers = getActiveMediaControllers().filter { it.metadata != null || it.playbackState != null }
        val selectedPackage = MediaSourceStore.getSelectedPackage(this)
        val selectedController = controllers.firstOrNull { it.packageName == selectedPackage }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()

        container.addView(
            sectionTitle(
                "媒体流",
                "选择要跟随的播放器，AirLyrics 会从这里读取歌曲状态。"
            )
        )

        container.addView(
            card {
                addView(label("当前媒体", colorTextMuted))
                if (selectedController != null) {
                    val title = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        .orEmpty()
                        .ifBlank { "未知歌曲" }
                    val artist = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?: "未知艺术家"
                    val appName = getAppName(selectedController.packageName)
                    val state = getPlaybackStateText(selectedController.playbackState?.state)

                    addView(bigText(title))
                    addView(normalText("$artist · $appName"))
                    addView(statusPill(state, selectedController.playbackState?.state == PlaybackState.STATE_PLAYING))
                } else {
                    addView(bigText("还没有检测到媒体"))
                    addView(normalText("先开启通知访问权限，然后播放一首歌。"))
                }
            }
        )

        container.addView(spacer(12))
        container.addView(label("活跃播放器", colorTextMuted))

        if (controllers.isEmpty()) {
            container.addView(
                card {
                    addView(bigText("等待音乐信号"))
                    addView(normalText("播放音乐后，这里会显示可选择的媒体流。"))
                    addView(smallHint("如果一直没有显示，请确认通知访问权限已开启。"))
                }
            )
        } else {
            controllers.forEach { controller ->
                container.addView(mediaSourceCard(controller, controller.packageName == selectedPackage))
            }
        }

        container.addView(spacer(18))
        container.addView(refreshMediaButton())

        return scroll(container)
    }

    private fun createFloatingPage(): View {
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
            previewBodyView = LinearLayout(this@MainActivity).apply {
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

            addView(LinearLayout(this@MainActivity).apply {
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

                previewToggleTextView = TextView(this@MainActivity).apply {
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
                                    val enabled = !FloatingLyricsStyleStore.getStyle(this@MainActivity).backgroundEnabled
                                    FloatingLyricsStyleStore.setBackgroundEnabled(this@MainActivity, enabled)
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
                                    FloatingLyricsStyleStore.setShadowRadius(this@MainActivity, value.toFloat())
                                    notifyFloatingStyleChanged()
                                    refreshFloatingPreview()
                                })
                                addView(colorControl("阴影", style().shadowColor) { color ->
                                    FloatingLyricsStyleStore.setShadowColor(this@MainActivity, color)
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
                                    FloatingLyricsStyleStore.setMaxWidthPercent(this@MainActivity, value)
                                    notifyFloatingStyleChanged()
                                    refreshFloatingPreview()
                                })
                                addView(sliderRow("横向内边距", style().paddingHorizontalDp, 0, 36, "dp") { value ->
                                    FloatingLyricsStyleStore.setPaddingHorizontal(this@MainActivity, value)
                                    notifyFloatingStyleChanged()
                                    refreshFloatingPreview()
                                })
                                addView(sliderRow("纵向内边距", style().paddingVerticalDp, 0, 28, "dp") { value ->
                                    FloatingLyricsStyleStore.setPaddingVertical(this@MainActivity, value)
                                    notifyFloatingStyleChanged()
                                    refreshFloatingPreview()
                                })
                                addView(sliderRow("圆角", style().cornerRadiusDp, 0, 36, "dp") { value ->
                                    FloatingLyricsStyleStore.setCornerRadius(this@MainActivity, value)
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
                    addView(settingRow("拖动锁定", if (FloatingLyricsStyleStore.isLocked(this@MainActivity)) "已开启" else "已关闭"))
                    addView(settingRow("点击穿透", if (FloatingLyricsStyleStore.isClickThrough(this@MainActivity)) "已开启" else "已关闭"))
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

    private fun createSettingsPage(): View {
        return when (settingsSubPage) {
            SettingsSubPage.HOME -> createSettingsHomePage()
            SettingsSubPage.SYSTEM -> createSystemSettingsPage()
            SettingsSubPage.LOCAL_LYRICS -> createLocalLyricsSettingsPage()
            SettingsSubPage.ABOUT -> createAboutSettingsPage()
        }
    }

    private fun createSettingsHomePage(): View {
        val container = pageContainer()

        container.addView(settingsHomeHeader())

        container.addView(
            settingsCategoryCard(
                title = "系统与权限",
                subtitle = "悬浮窗、通知权限、通知访问权限。",
                status = permissionSummary(),
                accent = colorAccent
            ) {
                settingsSubPage = SettingsSubPage.SYSTEM
                renderCurrentPage()
            }
        )

        container.addView(
            settingsCategoryCard(
                title = "本地歌词",
                subtitle = "歌词源、自动保存、下载目录和最近保存的 .lrc。",
                status = "${LyricsSettingsStore.getLyricsSourceTitle(this)} · ${if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) "自动保存" else "不自动保存"}",
                accent = colorAccentPink
            ) {
                settingsSubPage = SettingsSubPage.LOCAL_LYRICS
                renderCurrentPage()
            }
        )

        container.addView(
            settingsCategoryCard(
                title = "关于",
                subtitle = "版本号、项目地址、更新记录。",
                status = "AirLyrics ${getAppVersionName()}",
                accent = colorAccentMint
            ) {
                settingsSubPage = SettingsSubPage.ABOUT
                renderCurrentPage()
            }
        )

        container.addView(smallHint("当前页面只显示最高分类，进入分类后再调整具体设置。"))

        return scroll(container)
    }

    private fun createSystemSettingsPage(): View {
        val container = pageContainer()
        container.addView(settingsBackHeader("系统与权限", "让悬浮歌词能正常出现、读取媒体状态，并保持前台服务稳定。"))

        container.addView(
            card {
                addView(bigText("权限状态"))
                addView(settingRow("悬浮窗权限", if (Settings.canDrawOverlays(this@MainActivity)) "已开启" else "未开启"))
                addView(settingRow("通知权限", if (hasNotificationPermission()) "已开启" else "未开启"))
                addView(settingRow("通知访问权限", "需要在系统页确认"))
                addView(smallHint("通知访问权限负责读取媒体控制器；悬浮窗权限负责把歌词盖在其他 App 上。"))
            }
        )

        container.addView(
            card {
                addView(bigText("快捷入口"))
                addView(horizontalButtons(
                    "悬浮窗权限" to { requestOverlayPermission() },
                    "通知权限" to { requestNotificationPermissionIfNeeded() }
                ))
                addView(actionButton("打开通知访问设置") {
                    startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
                })
            }
        )

        return scroll(container)
    }

    private fun createLocalLyricsSettingsPage(): View {
        val container = pageContainer()
        val selectedSource = LyricsSettingsStore.getLyricsSource(this)
        val autoSave = LyricsSettingsStore.isAutoSaveLocalEnabled(this)
        val recentLyrics = LyricsStorage.listRecentLyrics(this, limit = 8)

        container.addView(settingsBackHeader("本地歌词", "决定歌词从哪里来，也决定找到后要不要收进本地小仓库。"))

        container.addView(
            card {
                addView(bigText("歌词源"))
                addView(normalText("当前：${LyricsSettingsStore.getLyricsSourceTitle(this@MainActivity)}"))
                addView(liveOptionGrid(
                    LyricsSettingsStore.sourceOptions.map { option ->
                        KeyedOptionItem(
                            key = option.key,
                            title = option.title,
                            selected = option.key == selectedSource,
                            action = {
                                LyricsSettingsStore.setLyricsSource(this@MainActivity, option.key)
                                reloadFloatingLyrics()
                                Toast.makeText(this@MainActivity, "歌词源已切换为：${option.title}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                ))
                LyricsSettingsStore.sourceOptions.forEach { option ->
                    addView(smallHint("${option.title}：${option.description}"))
                }
            }
        )

        container.addView(
            card {
                addView(bigText("自动下载到本地"))
                addView(normalText(if (autoSave) "开启后，联网找到歌词会自动保存成 .lrc，下次优先读取本地文件。" else "关闭后，联网找到歌词只用于本次显示，不写入本地目录。"))
                val autoSaveButton = actionButton(if (autoSave) "自动保存：开启" else "自动保存：关闭") { }
                autoSaveButton.setOnClickListener {
                    val enabled = !LyricsSettingsStore.isAutoSaveLocalEnabled(this@MainActivity)
                    LyricsSettingsStore.setAutoSaveLocalEnabled(this@MainActivity, enabled)
                    autoSaveButton.text = if (enabled) "自动保存：开启" else "自动保存：关闭"
                    Toast.makeText(this@MainActivity, if (enabled) "已开启自动保存" else "已关闭自动保存", Toast.LENGTH_SHORT).show()
                }
                addView(autoSaveButton)
            }
        )

        container.addView(
            card {
                addView(bigText("歌词文件夹"))
                addView(normalText("保存目录：${LyricsStorage.getLyricsDirDisplayPath(this@MainActivity)}"))
                addView(actionButton("选择歌词保存目录") {
                    selectLyricsDirLauncher.launch(null)
                })
                addView(actionButton("导入本地歌词") {
                    importLyricsLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                })
                addView(actionButton("复制歌词保存目录") {
                    showLyricsDir()
                })
            }
        )

        container.addView(
            card {
                addView(bigText("最近下载的歌词"))
                if (recentLyrics.isEmpty()) {
                    addView(normalText("还没有保存过歌词。播放歌曲并成功匹配后，这里会出现最近的 .lrc 文件。"))
                } else {
                    recentLyrics.forEach { item ->
                        addView(localLyricsRow(item))
                    }
                }
                addView(actionButton("刷新列表") {
                    renderCurrentPage()
                })
            }
        )

        return scroll(container)
    }

    private fun createAboutSettingsPage(): View {
        val container = pageContainer()
        container.addView(settingsBackHeader("关于", "一些和 AirLyrics 有关的小纸条。"))

        container.addView(
            card {
                addView(bigText("AirLyrics"))
                addView(normalText("版本号：${getAppVersionName()}"))
                addView(normalText("包名：$packageName"))
                addView(actionButton("打开项目地址") {
                    openUrl("https://github.com/AndSi-327/android-floating-lyrics")
                })
            }
        )

        container.addView(
            card {
                addView(bigText("更改日志"))
                addView(changelogItem("设置页分级", "系统与权限、本地歌词、关于，进入后再展示具体选项。"))
                addView(changelogItem("歌词源设置", "新增网易云歌词 / 仅本地歌词的来源选择。"))
                addView(changelogItem("本地歌词管理", "新增自动保存开关、保存目录展示和最近下载歌词列表。"))
                addView(changelogItem("轻飘飘视觉", "整体颜色改成奶油底、淡粉蓝按钮和柔软卡片。"))
            }
        )

        return scroll(container)
    }

    private fun settingsHomeHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "设置"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextStrong)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(themeToggleButton())
            })

            addView(TextView(this@MainActivity).apply {
                text = "把选项收进轻飘飘的小抽屉里，需要时再打开。"
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun settingsBackHeader(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "‹ 设置"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                    setPadding(0, 0, 0, dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    enableSoftPressFeedback(0.94f)
                    setOnClickListener {
                        settingsSubPage = SettingsSubPage.HOME
                        renderCurrentPage()
                    }
                })
                addView(themeToggleButton())
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun themeToggleButton(): TextView {
        return TextView(this).apply {
            text = if (isDarkTheme()) "☀" else "☾"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colorAccent)
            contentDescription = if (isDarkTheme()) "切换到白天模式" else "切换到暗黑模式"
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                setMargins(dp(10), 0, 0, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSurfaceLight)
                setStroke(dp(1), colorStroke)
            }
            elevation = dp(2).toFloat()
            enableSoftPressFeedback(0.9f)
            setOnClickListener {
                toggleThemeMode()
            }
        }
    }

    private fun settingsCategoryCard(
        title: String,
        subtitle: String,
        status: String,
        accent: Int,
        onClick: () -> Unit
    ): View {
        return card {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            enableSoftPressFeedback(0.985f)
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = "✦"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    setMargins(0, 0, dp(14), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(bigText(title))
                addView(normalText(subtitle))
                addView(smallHint(status))
            })

            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
            })
        }
    }

    private fun localLyricsRow(item: LyricsStorage.LocalLyricsItem): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(colorSurfaceLight)
                setStroke(dp(1), colorStroke)
            }
            addView(TextView(this@MainActivity).apply {
                text = item.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = LyricsStorage.formatLocalLyricsItem(item)
                textSize = 12f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun changelogItem(title: String, body: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = body
                textSize = 13f
                setTextColor(colorTextMuted)
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun permissionSummary(): String {
        val opened = listOf(
            Settings.canDrawOverlays(this),
            hasNotificationPermission()
        ).count { it }
        return "已开启 $opened / 2 项基础权限"
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadFloatingLyrics() {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_RELOAD_LYRICS
        }
        startLyricsService(intent)
    }

    private data class OptionItem(
        val title: String,
        val selected: Boolean,
        val action: () -> Unit
    )

    private data class KeyedOptionItem(
        val key: String,
        val title: String,
        val selected: Boolean,
        val action: () -> Unit
    )

    private data class FloatingSettingTile(
        val title: String,
        val subtitle: String,
        val mark: String,
        val onClick: (View) -> Unit
    )

    private fun floatingPreviewSummary(style: FloatingLyricsStyle): String {
        return "当前：${FloatingLyricsStyleStore.getPresetTitle(style.presetName)} · ${style.textSizeSp.toInt()}sp · ${FloatingLyricsStyleStore.getGravityTitle(style.gravity)} · 宽度 ${style.maxWidthPercent}%"
    }

    private fun floatingDisplaySummary(): String {
        val lockedText = if (FloatingLyricsStyleStore.isLocked(this)) "锁定" else "可拖动"
        val clickThroughText = if (FloatingLyricsStyleStore.isClickThrough(this)) "穿透" else "可点击"
        return "$lockedText · $clickThroughText"
    }

    private fun floatingLockButtonText(): String {
        return if (FloatingLyricsStyleStore.isLocked(this)) "拖动锁定：开启" else "拖动锁定：关闭"
    }

    private fun floatingClickThroughButtonText(): String {
        return if (FloatingLyricsStyleStore.isClickThrough(this)) "点击穿透：开启" else "点击穿透：关闭"
    }

    private fun floatingPreviewText(text: String, style: FloatingLyricsStyle): TextView {
        return TextView(this).apply {
            this.text = text
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, dp(4))
            layoutParams = params
            applyFloatingPreviewStyle(style)
        }
    }

    private fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle) {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = style.gravity
        setTextColor(style.textColor)
        setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
        setPadding(
            dp(style.paddingHorizontalDp.coerceAtMost(24)),
            dp(style.paddingVerticalDp.coerceAtMost(12)),
            dp(style.paddingHorizontalDp.coerceAtMost(24)),
            dp(style.paddingVerticalDp.coerceAtMost(12))
        )
        background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp.coerceAtMost(24)).toFloat()
                setColor(withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else {
            null
        }
    }

    private fun optionGrid(items: List<OptionItem>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            items.chunked(2).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, item ->
                        addView(optionButton(item).apply {
                            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            params.setMargins(
                                if (index == 0) 0 else dp(6),
                                dp(10),
                                if (index == rowItems.lastIndex) 0 else dp(6),
                                0
                            )
                            layoutParams = params
                        })
                    }
                    if (rowItems.size == 1) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(6), 0, 0, 0)
                            }
                        })
                    }
                })
            }
        }
    }

    private fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val buttons = mutableListOf<Pair<KeyedOptionItem, TextView>>()

            fun refreshSelection(selectedKey: String) {
                buttons.forEach { (item, button) ->
                    applyOptionButtonState(button, item.title, item.key == selectedKey)
                }
            }

            items.chunked(2).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, item ->
                        val button = TextView(this@MainActivity).apply {
                            gravity = Gravity.CENTER
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(12), dp(11), dp(12), dp(11))
                            applyOptionButtonState(this, item.title, item.selected)
                            enableSoftPressFeedback(0.96f)
                            setOnClickListener {
                                item.action()
                                refreshSelection(item.key)
                                playTinyPulse(this)
                            }
                        }
                        buttons.add(item to button)
                        addView(button.apply {
                            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            params.setMargins(
                                if (index == 0) 0 else dp(6),
                                dp(10),
                                if (index == rowItems.lastIndex) 0 else dp(6),
                                0
                            )
                            layoutParams = params
                        })
                    }
                    if (rowItems.size == 1) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(6), 0, 0, 0)
                            }
                        })
                    }
                })
            }
        }
    }

    private fun optionButton(item: OptionItem): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(11), dp(12), dp(11))
            applyOptionButtonState(this, item.title, item.selected)
            enableSoftPressFeedback(0.96f)
            setOnClickListener {
                item.action()
                playTinyPulse(this)
            }
        }
    }

    private fun applyOptionButtonState(button: TextView, title: String, selected: Boolean) {
        button.text = if (selected) "✓ $title" else title
        button.setTextColor(if (selected) Color.WHITE else colorText)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) colorAccent else colorSurfaceLight)
            setStroke(dp(1), if (selected) colorAccentLight else colorStroke)
        }
    }

    private fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val safeValue = value.coerceIn(min, max)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))

            val valueText = TextView(this@MainActivity).apply {
                text = "$title：$safeValue$suffix"
                textSize = 14f
                setTextColor(colorText)
                setPadding(0, 0, 0, dp(6))
            }
            addView(valueText)

            addView(SeekBar(this@MainActivity).apply {
                this.max = max - min
                progress = safeValue - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val newValue = min + progress
                        valueText.text = "$title：$newValue$suffix"
                        if (fromUser) onChanged(newValue)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }
    }

    private fun colorControl(
        title: String,
        color: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val initialRed = Color.red(color)
        val initialGreen = Color.green(color)
        val initialBlue = Color.blue(color)
        val initialAlpha = Color.alpha(color)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)

            val preview = TextView(this@MainActivity).apply {
                text = "$title：R$initialRed G$initialGreen B$initialBlue A$initialAlpha"
                textSize = 14f
                setTextColor(colorText)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = colorPreviewBackground(color)
                setOnClickListener {
                    showColorPickerDialog(title, currentColor = colorFromSummaryText(text.toString(), color)) { pickedColor ->
                        text = "$title：${FloatingLyricsStyleStore.colorSummary(pickedColor)}"
                        background = colorPreviewBackground(pickedColor)
                        onChanged(pickedColor)
                    }
                }
            }
            addView(preview)
            addView(smallHint("点击上方颜色条可打开调色板，也可以用下面的滑条精细调整。"))

            var red = initialRed
            var green = initialGreen
            var blue = initialBlue
            var alpha = initialAlpha

            fun updateColor() {
                val newColor = Color.argb(alpha, red, green, blue)
                preview.text = "$title：${FloatingLyricsStyleStore.colorSummary(newColor)}"
                preview.background = colorPreviewBackground(newColor)
                onChanged(newColor)
            }

            addView(sliderRow("R", red, 0, 255, "") { value ->
                red = value
                updateColor()
            })
            addView(sliderRow("G", green, 0, 255, "") { value ->
                green = value
                updateColor()
            })
            addView(sliderRow("B", blue, 0, 255, "") { value ->
                blue = value
                updateColor()
            })
            addView(sliderRow("不透明度", alpha, 0, 255, "") { value ->
                alpha = value
                updateColor()
            })
        }
    }

    private fun showColorPickerDialog(
        title: String,
        currentColor: Int,
        onPicked: (Int) -> Unit
    ) {
        var red = Color.red(currentColor)
        var green = Color.green(currentColor)
        var blue = Color.blue(currentColor)
        var alpha = Color.alpha(currentColor)
        var selectedColor = currentColor

        val dialogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        val preview = TextView(this).apply {
            text = "${FloatingLyricsStyleStore.colorSummary(selectedColor)}"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = colorPreviewBackground(selectedColor)
        }
        dialogContainer.addView(preview)

        fun refreshPreview() {
            selectedColor = Color.argb(alpha, red, green, blue)
            preview.text = FloatingLyricsStyleStore.colorSummary(selectedColor)
            preview.background = colorPreviewBackground(selectedColor)
        }

        dialogContainer.addView(smallHint("基础色板"))
        dialogContainer.addView(colorPaletteGrid { picked ->
            red = Color.red(picked)
            green = Color.green(picked)
            blue = Color.blue(picked)
            alpha = Color.alpha(picked)
            refreshPreview()
        })

        dialogContainer.addView(sliderRow("R", red, 0, 255, "") { value ->
            red = value
            refreshPreview()
        })
        dialogContainer.addView(sliderRow("G", green, 0, 255, "") { value ->
            green = value
            refreshPreview()
        })
        dialogContainer.addView(sliderRow("B", blue, 0, 255, "") { value ->
            blue = value
            refreshPreview()
        })
        dialogContainer.addView(sliderRow("不透明度", alpha, 0, 255, "") { value ->
            alpha = value
            refreshPreview()
        })

        val dialogScroll = ScrollView(this).apply {
            addView(dialogContainer)
        }

        AlertDialog.Builder(this)
            .setTitle("选择${title}颜色")
            .setView(dialogScroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                onPicked(selectedColor)
            }
            .show()
    }

    private fun colorPaletteGrid(onPicked: (Int) -> Unit): LinearLayout {
        val colors = listOf(
            Color.WHITE, Color.LTGRAY, Color.GRAY, Color.DKGRAY, Color.BLACK,
            Color.rgb(255, 88, 88), Color.rgb(255, 159, 67), Color.rgb(255, 221, 89), Color.rgb(46, 213, 115), Color.rgb(112, 161, 255),
            Color.rgb(83, 82, 237), Color.rgb(223, 108, 255), Color.rgb(176, 226, 255), Color.rgb(10, 14, 24), Color.TRANSPARENT
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            colors.chunked(5).forEach { rowColors ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowColors.forEachIndexed { index, swatchColor ->
                        addView(View(this@MainActivity).apply {
                            background = colorPreviewBackground(swatchColor)
                            enableSoftPressFeedback(0.9f)
                            setOnClickListener {
                                onPicked(swatchColor)
                                playTinyPulse(this)
                            }
                            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                                setMargins(
                                    if (index == 0) 0 else dp(4),
                                    dp(8),
                                    if (index == rowColors.lastIndex) 0 else dp(4),
                                    0
                                )
                            }
                        })
                    }
                })
            }
        }
    }

    private fun colorPreviewBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
            setStroke(dp(1), colorStroke)
        }
    }

    private fun colorFromSummaryText(text: String, fallback: Int): Int {
        val regex = Regex("R(\\d+)\\s+G(\\d+)\\s+B(\\d+)\\s+A(\\d+)")
        val match = regex.find(text) ?: return fallback
        val (r, g, b, a) = match.destructured
        return Color.argb(
            a.toIntOrNull()?.coerceIn(0, 255) ?: Color.alpha(fallback),
            r.toIntOrNull()?.coerceIn(0, 255) ?: Color.red(fallback),
            g.toIntOrNull()?.coerceIn(0, 255) ?: Color.green(fallback),
            b.toIntOrNull()?.coerceIn(0, 255) ?: Color.blue(fallback)
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun mediaSourceCard(controller: MediaController, selected: Boolean): View {
        return card {
            val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                .orEmpty()
                .ifBlank { "未知歌曲" }
            val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "未知艺术家"
            val appName = getAppName(controller.packageName)
            val state = getPlaybackStateText(controller.playbackState?.state)

            addView(label(if (selected) "已连接" else "可选择", if (selected) colorAccentLight else colorTextMuted).apply {
                tag = "media_source_status:${controller.packageName}"
            })
            addView(bigText(appName))
            addView(normalText("$title - $artist"))
            addView(smallHint(state))
            enableSoftPressFeedback(0.985f)
            setOnClickListener {
                MediaSourceStore.saveSelectedPackage(this@MainActivity, controller.packageName)
                notifyFloatingServiceSourceChanged(controller.packageName)
                updateMediaSourceSelectionVisuals(controller.packageName)
                playTinyPulse(this)
            }
        }
    }

    private fun pageContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = softLayoutTransition()
            setPadding(dp(20), dp(6), dp(20), dp(24))
        }
    }

    private fun scroll(child: View): ScrollView {
        return ScrollView(this).apply {
            isFillViewport = false
            addView(child)
            post { animateChildrenCascade(child) }
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun card(content: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dp(12))
            layoutParams = params
            elevation = dp(2).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(colorCard)
                setStroke(dp(1), colorStroke)
            }
            content()
        }
    }

    private fun floatingStatusPreviewCard(content: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dp(8))
            layoutParams = params
            elevation = dp(4).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(colorCard)
                setStroke(dp(1), colorAccentSoft)
            }
            content()
        }
    }

    private fun settingGrid(vararg items: FloatingSettingTile): LinearLayout {
        val tileItems = items.toList()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tileItems.chunked(2).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, item ->
                        addView(floatingTile(item).apply {
                            val params = LinearLayout.LayoutParams(0, dp(132), 1f)
                            params.setMargins(
                                if (index == 0) 0 else dp(6),
                                0,
                                if (index == rowItems.lastIndex) 0 else dp(6),
                                dp(12)
                            )
                            layoutParams = params
                        })
                    }
                    if (rowItems.size == 1) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(6), 0, 0, 0)
                            }
                        })
                    }
                })
            }
        }
    }

    private fun floatingTile(item: FloatingSettingTile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(colorCard)
                setStroke(dp(1), colorStroke)
            }

            addView(TextView(this@MainActivity).apply {
                text = item.mark
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(0, 0, 0, dp(10))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorAccent)
                }
            })

            addView(TextView(this@MainActivity).apply {
                text = item.title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })

            addView(TextView(this@MainActivity).apply {
                text = item.subtitle
                textSize = 12f
                setTextColor(colorTextMuted)
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
            })

            enableSoftPressFeedback(0.975f)
            setOnClickListener { item.onClick(this) }
        }
    }

    private fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(colorBubble)
                setStroke(dp(1), colorAccentSoft)
            }
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels - dp(72)).coerceAtMost(dp(360)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(dp(18), dp(18), dp(18), dp(18))
            }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 20f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorTextStrong)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 13f
                        setTextColor(colorTextMuted)
                        setPadding(0, dp(4), 0, 0)
                    })
                })
                addView(TextView(this@MainActivity).apply {
                    text = "×"
                    gravity = Gravity.CENTER
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextMuted)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        setMargins(dp(10), 0, 0, 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorSurfaceLight)
                    }
                    enableSoftPressFeedback(0.9f)
                    setOnClickListener { onClose() }
                })
            })
            addView(spacer(8))
            content()
        }
    }

    private fun showFloatingSettingPanel(
        title: String,
        subtitle: String,
        content: LinearLayout.() -> Unit
    ) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(28).toFloat(), dp(28).toFloat(),
                    dp(28).toFloat(), dp(28).toFloat(),
                    0f, 0f,
                    0f, 0f
                )
                setColor(colorSurface)
                setStroke(dp(1), colorStroke)
            }

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, dp(8))
            })
            content()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(panel) })
            .create()

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.setDimAmount(0.08f)
                window.setGravity(Gravity.BOTTOM)
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(colorAccent)
            }
            enableSoftPressFeedback(0.97f)
            setOnClickListener {
                onClick()
                playTinyPulse(this)
            }
        }
    }


    private fun animatePageEnter(view: View, fromRight: Boolean) {
        val distance = dp(26).toFloat() * if (fromRight) 1f else -1f
        view.alpha = 0f
        view.translationX = distance
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(230L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateChildrenCascade(root: View) {
        val parent = root as? ViewGroup ?: return
        val delayStep = 24L
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            child.alpha = 0f
            child.translationY = dp(12).toFloat()
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index.coerceAtMost(8)) * delayStep)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun softLayoutTransition(): LayoutTransition {
        return LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(170L)
        }
    }

    private fun View.enableSoftPressFeedback(pressedScale: Float = 0.97f) {
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .alpha(0.88f)
                        .setDuration(70L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(150L)
                        .setInterpolator(OvershootInterpolator(0.52f))
                        .start()
                }
            }
            false
        }
    }

    private fun playTinyPulse(view: View) {
        view.animate()
            .scaleX(1.025f)
            .scaleY(1.025f)
            .setDuration(80L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(OvershootInterpolator(0.48f))
                    .start()
            }
            .start()
    }

    private fun refreshMediaButton(): View {
        lateinit var row: LinearLayout
        lateinit var labelView: TextView
        var progressView: ProgressBar? = null

        fun applyButtonState(animateDone: Boolean = false) {
            val buttonText = when (mediaRefreshState) {
                RefreshState.IDLE -> "刷新媒体状态"
                RefreshState.REFRESHING -> "刷新中"
                RefreshState.DONE -> "已刷新"
            }
            val buttonColor = when (mediaRefreshState) {
                RefreshState.IDLE -> colorAccent
                RefreshState.REFRESHING -> colorSurfaceLight
                RefreshState.DONE -> colorAccentSoft
            }

            row.isEnabled = mediaRefreshState != RefreshState.REFRESHING
            row.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(buttonColor)
            }
            labelView.text = buttonText
            labelView.setTextColor(if (mediaRefreshState == RefreshState.REFRESHING) colorText else Color.WHITE)

            if (mediaRefreshState == RefreshState.REFRESHING && progressView == null) {
                progressView = ProgressBar(this@MainActivity).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        setMargins(0, 0, dp(10), 0)
                    }
                }
                row.addView(progressView, 0)
            } else if (mediaRefreshState != RefreshState.REFRESHING && progressView != null) {
                row.removeView(progressView)
                progressView = null
            }

            if (animateDone && mediaRefreshState == RefreshState.DONE) {
                row.rotation = -2f
                row.animate()
                    .rotation(0f)
                    .scaleX(1.018f)
                    .scaleY(1.018f)
                    .setDuration(150L)
                    .setInterpolator(OvershootInterpolator(0.65f))
                    .withEndAction {
                        row.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(130L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
        }

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, 0)
            layoutParams = params
            enableSoftPressFeedback(0.97f)
        }

        labelView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(labelView)

        applyButtonState()
        row.setOnClickListener {
            if (mediaRefreshState == RefreshState.REFRESHING) return@setOnClickListener
            playTinyPulse(row)
            startMediaRefreshFeedback { applyButtonState(animateDone = true) }
            applyButtonState()
        }
        return row
    }

    private fun startMediaRefreshFeedback(onStateChanged: () -> Unit) {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        mediaRefreshState = RefreshState.REFRESHING
        onStateChanged()

        mediaRefreshHandler.postDelayed({
            mediaRefreshState = RefreshState.DONE
            onStateChanged()
        }, 650)
    }

    private fun updateMediaSourceSelectionVisuals(selectedPackage: String) {
        val root = contentContainer ?: return
        fun visit(view: View) {
            if (view is TextView) {
                val tagText = view.tag as? String
                if (tagText?.startsWith("media_source_status:") == true) {
                    val packageName = tagText.removePrefix("media_source_status:")
                    val selected = packageName == selectedPackage
                    view.text = if (selected) "已连接" else "可选择"
                    view.setTextColor(if (selected) colorAccentLight else colorTextMuted)
                    if (selected) playTinyPulse(view)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    private fun horizontalButtons(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { index, pair ->
                addView(TextView(this@MainActivity).apply {
                    text = pair.first
                    gravity = Gravity.CENTER
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    params.setMargins(
                        if (index == 0) 0 else dp(6),
                        dp(10),
                        if (index == buttons.lastIndex) 0 else dp(6),
                        0
                    )
                    layoutParams = params
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(colorAccent)
                    }
                    enableSoftPressFeedback(0.96f)
                    setOnClickListener {
                        pair.second()
                        playTinyPulse(this)
                    }
                })
            }
        }
    }

    private fun settingRow(name: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))

            addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 15f
                setTextColor(colorTextStrong)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            })

            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 13f
                setTextColor(colorTextMuted)
            })
        }
    }

    private fun statusPill(text: String, playing: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (playing) Color.WHITE else colorTextMuted)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(12), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(if (playing) colorAccent else colorSurfaceLight)
            }
        }
    }

    private fun label(text: String, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun bigText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        }
    }

    private fun normalText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(colorText)
            setPadding(0, dp(5), 0, 0)
        }
    }

    private fun smallHint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(colorTextMuted)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun spacer(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }
    }

    private fun showFloatingLyrics(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return false
        }

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_SHOW
        }
        startLyricsService(intent)
        setQuickFloatingVisible(true)
        updateTabs()
        return true
    }

    private fun hideFloatingLyrics(): Boolean {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_HIDE
        }
        startLyricsService(intent)
        setQuickFloatingVisible(false)
        updateTabs()
        return true
    }

    private fun toggleFloatingFromNav() {
        val selectedTab = tabViews[Page.FLOATING]
        selectedTab?.animate()
            ?.scaleX(0.92f)
            ?.scaleY(0.92f)
            ?.setDuration(70L)
            ?.withEndAction {
                selectedTab.animate()
                    .scaleX(1.14f)
                    .scaleY(1.14f)
                    .setDuration(180L)
                    .setInterpolator(OvershootInterpolator(1.45f))
                    .start()
            }
            ?.start()

        if (quickFloatingVisible) {
            hideFloatingLyrics()
        } else {
            showFloatingLyrics()
        }
    }

    private fun isQuickFloatingVisible(): Boolean {
        return getSharedPreferences("floating_quick_control", Context.MODE_PRIVATE)
            .getBoolean("visible", false)
    }

    private fun setQuickFloatingVisible(visible: Boolean) {
        quickFloatingVisible = visible
        getSharedPreferences("floating_quick_control", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("visible", visible)
            .apply()
    }

    private fun toggleLock() {
        locked = !FloatingLyricsStyleStore.isLocked(this)
        FloatingLyricsStyleStore.setLocked(this, locked)
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = if (locked) FloatingLyricsService.ACTION_LOCK else FloatingLyricsService.ACTION_UNLOCK
        }
        startLyricsService(intent)
    }

    private fun toggleClickThrough() {
        clickThrough = !FloatingLyricsStyleStore.isClickThrough(this)
        FloatingLyricsStyleStore.setClickThrough(this, clickThrough)
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = if (clickThrough) {
                FloatingLyricsService.ACTION_CLICK_THROUGH_ON
            } else {
                FloatingLyricsService.ACTION_CLICK_THROUGH_OFF
            }
        }
        startLyricsService(intent)
    }

    private fun applyFloatingPreset(preset: String) {
        FloatingLyricsStyleStore.applyPreset(this, preset)
        notifyFloatingStyleChanged()
    }

    private fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextSize(this, textSizeSp)
        notifyFloatingStyleChanged()
        if (refreshPage) renderCurrentPage()
    }

    private fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextColor(this, color)
        notifyFloatingStyleChanged()
        if (refreshPage) renderCurrentPage()
    }

    private fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setBackgroundColor(this, color)
        notifyFloatingStyleChanged()
        if (refreshPage) renderCurrentPage()
    }

    private fun applyFloatingGravity(gravity: Int) {
        FloatingLyricsStyleStore.setGravity(this, gravity)
        notifyFloatingStyleChanged()
    }

    private fun notifyFloatingStyleChanged() {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_APPLY_STYLE
        }
        startLyricsService(intent)
    }

    private fun isDarkTheme(): Boolean {
        return getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .getBoolean("dark_mode", false)
    }

    private fun setDarkTheme(enabled: Boolean) {
        getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_mode", enabled)
            .apply()
    }

    private fun toggleThemeMode() {
        val nextDark = !isDarkTheme()
        setDarkTheme(nextDark)
        applySystemBarsTheme()
        val oldContainer = contentContainer
        oldContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(90L)
            ?.withEndAction {
                setContentView(createMainView())
                renderCurrentPage()
                contentContainer?.alpha = 0f
                contentContainer?.animate()
                    ?.alpha(1f)
                    ?.setDuration(180L)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
            }
            ?.start()
            ?: run {
                setContentView(createMainView())
                renderCurrentPage()
            }
    }

    private fun applySystemBarsTheme() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = colorSurface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val lightFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (isDarkTheme()) {
                window.decorView.systemUiVisibility and lightFlag.inv()
            } else {
                window.decorView.systemUiVisibility or lightFlag
            }
        }
    }

    private fun getPlaybackStateText(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "暂停中"
            PlaybackState.STATE_STOPPED -> "已停止"
            PlaybackState.STATE_BUFFERING -> "缓冲中"
            PlaybackState.STATE_CONNECTING -> "连接中"
            PlaybackState.STATE_FAST_FORWARDING -> "快进中"
            PlaybackState.STATE_REWINDING -> "快退中"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "切到下一首"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "切到上一首"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "切换队列"
            PlaybackState.STATE_NONE -> "无播放状态"
            PlaybackState.STATE_ERROR -> "播放异常"
            else -> "状态未知"
        }
    }

    private fun autoSelectMediaSourceOnceIfNeeded() {
        if (MediaSourceStore.getSelectedPackage(this) != null) return

        val controllers = getActiveMediaControllers()
            .filter { it.metadata != null || it.playbackState != null }

        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return

        val packageName = controller.packageName
        MediaSourceStore.saveSelectedPackage(this, packageName)
        notifyFloatingServiceSourceChanged(packageName)

    }

    private fun getActiveMediaControllers(): List<MediaController> {
        return try {
            val mediaSessionManager =
                getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            mediaSessionManager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Toast.makeText(this, "需要先开启通知访问权限", Toast.LENGTH_LONG).show()
            emptyList()
        } catch (e: Exception) {
            Toast.makeText(this, "读取媒体来源失败", Toast.LENGTH_LONG).show()
            emptyList()
        }
    }

    private fun notifyFloatingServiceSourceChanged(packageName: String?) {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_SELECT_MEDIA_SOURCE
            putExtra(FloatingLyricsService.EXTRA_SOURCE_PACKAGE, packageName)
        }
        startLyricsService(intent)
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun showLyricsDir() {
        val path = LyricsStorage.getLyricsDirRawPath(this)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("歌词保存目录", path))

        Toast.makeText(this, "歌词保存目录已复制", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "当前系统不需要单独开启通知权限", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasNotificationPermission()) {
            Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show()
            return
        }

        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startLyricsService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private class WaterTabHighlightView(
        context: Context,
        private val accentColor: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            alpha = 54
        }
        private val rect = RectF()
        private var animator: ValueAnimator? = null

        private var currentLeft = 0f
        private var currentTop = 0f
        private var currentWidth = 0f
        private var currentHeight = 0f
        private var stretch = 0f
        var hasPosition = false
            private set

        fun moveTo(
            targetCenterX: Float,
            targetCenterY: Float,
            targetWidth: Float,
            targetHeight: Float,
            animate: Boolean
        ) {
            if (targetWidth <= 0f || targetHeight <= 0f) return
            animator?.cancel()

            if (width <= 0) return
            val safeInset = resources.displayMetrics.density * 8f
            val halfWidth = targetWidth / 2f
            val clampedTargetCenterX = targetCenterX.coerceIn(
                safeInset + halfWidth,
                width - safeInset - halfWidth
            )
            val targetLeft = clampedTargetCenterX - halfWidth
            val targetTop = targetCenterY - targetHeight / 2f

            if (!hasPosition || !animate) {
                currentLeft = targetLeft
                currentTop = targetTop
                currentWidth = targetWidth
                currentHeight = targetHeight
                stretch = 0f
                hasPosition = true
                invalidate()
                return
            }

            val startLeft = currentLeft
            val startTop = currentTop
            val startWidth = currentWidth
            val startHeight = currentHeight
            val startCenter = startLeft + startWidth / 2f
            val targetCenter = targetLeft + targetWidth / 2f
            val travel = kotlin.math.abs(targetCenter - startCenter)

            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (260L + (travel / resources.displayMetrics.density * 1.2f).toLong()).coerceAtMost(430L)
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    val t = animation.animatedValue as Float
                    val eased = 0.5f - kotlin.math.cos((t * PI).toFloat()) / 2f
                    currentLeft = lerp(startLeft, targetLeft, eased)
                    currentTop = lerp(startTop, targetTop, eased)
                    currentWidth = lerp(startWidth, targetWidth, eased)
                    currentHeight = lerp(startHeight, targetHeight, eased)
                    stretch = sin((t * PI).toFloat()) * travel * 0.26f
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!hasPosition) return

            val centerX = currentLeft + currentWidth / 2f
            val centerY = currentTop + currentHeight / 2f
            val safeInset = resources.displayMetrics.density * 8f
            val wantedHalfWidth = currentWidth / 2f + stretch
            val halfHeight = currentHeight / 2f
            val radius = halfHeight.coerceAtLeast(1f)

            rect.set(
                centerX - wantedHalfWidth,
                centerY - halfHeight,
                centerX + wantedHalfWidth,
                centerY + halfHeight
            )
            if (rect.left < safeInset) {
                rect.offset(safeInset - rect.left, 0f)
            }
            if (rect.right > width - safeInset) {
                rect.offset(width - safeInset - rect.right, 0f)
            }
            canvas.drawRoundRect(rect, radius, radius, glowPaint)

            val inset = resources.displayMetrics.density * 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, (radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f), paint)
        }

        private fun lerp(start: Float, end: Float, amount: Float): Float {
            return start + (end - start) * amount
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val colorBackground: Int
        get() = if (isDarkTheme()) Color.rgb(27, 23, 30) else Color.rgb(255, 249, 243)
    private val colorSurface: Int
        get() = if (isDarkTheme()) Color.rgb(36, 30, 39) else Color.rgb(255, 244, 236)
    private val colorSurfaceLight: Int
        get() = if (isDarkTheme()) Color.rgb(49, 40, 53) else Color.rgb(255, 250, 246)
    private val colorCard: Int
        get() = if (isDarkTheme()) Color.rgb(43, 35, 47) else Color.rgb(255, 255, 255)
    private val colorBubble: Int
        get() = if (isDarkTheme()) Color.argb(248, 43, 35, 47) else Color.argb(246, 255, 252, 248)
    private val colorStroke: Int
        get() = if (isDarkTheme()) Color.rgb(75, 58, 70) else Color.rgb(245, 221, 215)
    private val colorAccent: Int
        get() = if (isDarkTheme()) Color.rgb(246, 138, 171) else Color.rgb(241, 143, 169)
    private val colorAccentLight: Int
        get() = if (isDarkTheme()) Color.rgb(255, 179, 202) else Color.rgb(255, 184, 202)
    private val colorAccentSoft: Int
        get() = if (isDarkTheme()) Color.rgb(111, 191, 184) else Color.rgb(159, 214, 203)
    private val colorAccentPink: Int
        get() = if (isDarkTheme()) Color.rgb(236, 126, 164) else Color.rgb(255, 177, 197)
    private val colorAccentMint: Int
        get() = if (isDarkTheme()) Color.rgb(105, 190, 182) else Color.rgb(150, 211, 203)
    private val colorTextStrong: Int
        get() = if (isDarkTheme()) Color.rgb(247, 229, 237) else Color.rgb(91, 67, 76)
    private val colorText: Int
        get() = if (isDarkTheme()) Color.rgb(224, 199, 211) else Color.rgb(122, 94, 105)
    private val colorTextMuted: Int
        get() = if (isDarkTheme()) Color.rgb(178, 148, 164) else Color.rgb(166, 132, 142)
}
