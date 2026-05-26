package com.andsi.airlyrics

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

    private var locked = false
    private var clickThrough = false
    private var currentPage = Page.MEDIA
    private var contentContainer: FrameLayout? = null
    private val tabViews = mutableMapOf<Page, TextView>()
    private val pageScrollY = mutableMapOf<Page, Int>()
    private var renderedPage = Page.MEDIA

    private enum class RefreshState { IDLE, REFRESHING, DONE }

    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private var mediaRefreshState = RefreshState.IDLE

    private val resetRefreshStateRunnable = Runnable {
        mediaRefreshState = RefreshState.IDLE
        if (currentPage == Page.MEDIA) renderCurrentPage()
    }

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
        setContentView(createMainView())
        autoSelectMediaSourceOnceIfNeeded()
        renderCurrentPage()
    }

    override fun onResume() {
        super.onResume()
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
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
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(12))
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

        addTab(bar, Page.MEDIA, "媒体流")
        addTab(bar, Page.FLOATING, "悬浮窗")
        addTab(bar, Page.SETTINGS, "设置")
        return bar
    }

    private fun addTab(parent: LinearLayout, page: Page, title: String) {
        val tab = TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                currentPage = page
                renderCurrentPage()
            }
        }

        tabViews[page] = tab
        parent.addView(tab)
    }

    private fun renderCurrentPage() {
        val container = contentContainer ?: return
        (container.getChildAt(0) as? ScrollView)?.let { scrollView ->
            pageScrollY[renderedPage] = scrollView.scrollY
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
        renderedPage = currentPage

        (pageView as? ScrollView)?.let { scrollView ->
            scrollView.scrollTo(0, restoreY)
            scrollView.post {
                scrollView.scrollTo(0, restoreY)
            }
        }
    }

    private fun updateTabs() {
        tabViews.forEach { (page, view) ->
            val selected = page == currentPage
            view.setTextColor(if (selected) Color.WHITE else colorTextMuted)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (selected) colorAccent else Color.TRANSPARENT)
            }
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
        val container = pageContainer()
        val style = FloatingLyricsStyleStore.getStyle(this)
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        var previewTextView: TextView? = null
        var summaryTextView: TextView? = null
        var gravityStatusText: TextView? = null
        var lockStatusText: TextView? = null
        var clickThroughStatusText: TextView? = null

        fun refreshFloatingPreview() {
            val latestStyle = FloatingLyricsStyleStore.getStyle(this)
            previewTextView?.applyFloatingPreviewStyle(latestStyle)
            summaryTextView?.text = "当前：${FloatingLyricsStyleStore.getPresetTitle(latestStyle.presetName)} · ${latestStyle.textSizeSp.toInt()}sp · ${FloatingLyricsStyleStore.getGravityTitle(latestStyle.gravity)}"
            gravityStatusText?.text = "当前对齐：${FloatingLyricsStyleStore.getGravityTitle(latestStyle.gravity)}"
            lockStatusText?.text = "拖动锁定：${if (FloatingLyricsStyleStore.isLocked(this)) "已开启" else "已关闭"}"
            clickThroughStatusText?.text = "点击穿透：${if (FloatingLyricsStyleStore.isClickThrough(this)) "已开启" else "已关闭"}"
        }

        container.addView(
            sectionTitle(
                "悬浮窗",
                "调整歌词窗口的大小、颜色、透明度和拖动行为，改完会立即同步到正在显示的悬浮窗。"
            )
        )

        container.addView(
            card {
                addView(label("预览", colorTextMuted))
                previewTextView = floatingPreviewText("夜に駆ける\n奔向夜晚", style)
                summaryTextView = normalText("当前：${FloatingLyricsStyleStore.getPresetTitle(style.presetName)} · ${style.textSizeSp.toInt()}sp · ${FloatingLyricsStyleStore.getGravityTitle(style.gravity)}")
                addView(previewTextView!!)
                addView(summaryTextView!!)
                addView(smallHint("预览包含歌词和翻译两行，对齐方式会同时影响两行文字。"))
            }
        )

        container.addView(
            card {
                addView(bigText("显示控制"))
                addView(normalText("快速显示、隐藏或控制悬浮歌词。锁定只禁止拖动，穿透会让触摸事件落到下面的 App。"))
                addView(horizontalButtons(
                    "显示" to { showFloatingLyrics() },
                    "隐藏" to { hideFloatingLyrics() }
                ))
                val lockButton = actionButton(if (locked) "拖动锁定：开启" else "拖动锁定：关闭") { }
                lockButton.setOnClickListener {
                    toggleLock()
                    lockButton.text = if (FloatingLyricsStyleStore.isLocked(this@MainActivity)) "拖动锁定：开启" else "拖动锁定：关闭"
                    refreshFloatingPreview()
                }
                addView(lockButton)

                val clickThroughButton = actionButton(if (clickThrough) "点击穿透：开启" else "点击穿透：关闭") { }
                clickThroughButton.setOnClickListener {
                    toggleClickThrough()
                    clickThroughButton.text = if (FloatingLyricsStyleStore.isClickThrough(this@MainActivity)) "点击穿透：开启" else "点击穿透：关闭"
                    refreshFloatingPreview()
                }
                addView(clickThroughButton)
            }
        )

        container.addView(
            card {
                addView(bigText("皮肤预设"))
                addView(normalText("预设仍然保留，方便一键恢复到好看的基础样式。"))
                addView(liveOptionGrid(
                    FloatingLyricsStyleStore.presets.map { preset ->
                        KeyedOptionItem(
                            key = preset.key,
                            title = preset.title,
                            selected = preset.key == style.presetName,
                            action = {
                                applyFloatingPreset(preset.key)
                                refreshFloatingPreview()
                            }
                        )
                    }
                ))
            }
        )

        container.addView(
            card {
                addView(bigText("字体大小"))
                addView(sliderRow(
                    title = "大小",
                    value = style.textSizeSp.toInt(),
                    min = 14,
                    max = 56,
                    suffix = "sp"
                ) { value ->
                    applyFloatingTextSize(value.toFloat(), refreshPage = false)
                    refreshFloatingPreview()
                })
            }
        )

        container.addView(
            card {
                addView(bigText("字体颜色"))
                addView(colorControl(
                    title = "文字",
                    color = style.textColor
                ) { color ->
                    applyFloatingTextColor(color, refreshPage = false)
                    refreshFloatingPreview()
                })
            }
        )

        container.addView(
            card {
                val backgroundColor = FloatingLyricsStyleStore.backgroundColorWithAlpha(style)
                addView(bigText("背景颜色"))
                val backgroundButton = actionButton(if (style.backgroundEnabled) "背景：开启" else "背景：关闭") { }
                backgroundButton.setOnClickListener {
                    val enabled = !FloatingLyricsStyleStore.getStyle(this@MainActivity).backgroundEnabled
                    FloatingLyricsStyleStore.setBackgroundEnabled(this@MainActivity, enabled)
                    notifyFloatingStyleChanged()
                    backgroundButton.text = if (enabled) "背景：开启" else "背景：关闭"
                    refreshFloatingPreview()
                }
                addView(backgroundButton)
                addView(colorControl(
                    title = "背景",
                    color = backgroundColor
                ) { color ->
                    applyFloatingBackgroundColor(color, refreshPage = false)
                    refreshFloatingPreview()
                })
            }
        )

        container.addView(
            card {
                addView(bigText("文字对齐"))
                gravityStatusText = normalText("当前对齐：${FloatingLyricsStyleStore.getGravityTitle(style.gravity)}")
                addView(gravityStatusText!!)
                addView(liveOptionGrid(listOf(
                    KeyedOptionItem("left", "左对齐", style.gravity == (Gravity.START or Gravity.CENTER_VERTICAL)) {
                        applyFloatingGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                        refreshFloatingPreview()
                    },
                    KeyedOptionItem("center", "居中", style.gravity == Gravity.CENTER) {
                        applyFloatingGravity(Gravity.CENTER)
                        refreshFloatingPreview()
                    },
                    KeyedOptionItem("right", "右对齐", style.gravity == (Gravity.END or Gravity.CENTER_VERTICAL)) {
                        applyFloatingGravity(Gravity.END or Gravity.CENTER_VERTICAL)
                        refreshFloatingPreview()
                    }
                )))
                addView(smallHint("悬浮窗会使用固定最大宽度，所以换行后的歌词和翻译会一起左 / 中 / 右对齐。"))
            }
        )

        container.addView(
            card {
                addView(bigText("行为设置"))
                addView(settingRow("记住位置", "已开启"))
                addView(settingRow("最大宽度", "屏幕 ${style.maxWidthPercent}%"))
                lockStatusText = normalText("拖动锁定：${if (locked) "已开启" else "已关闭"}")
                clickThroughStatusText = normalText("点击穿透：${if (clickThrough) "已开启" else "已关闭"}")
                addView(lockStatusText!!)
                addView(clickThroughStatusText!!)
                addView(smallHint("锁定后不能拖动，但仍会接收触摸；开启穿透后，悬浮窗不会挡住下面的 App。"))
            }
        )

        return scroll(container)
    }

    private fun createSettingsPage(): View {
        val container = pageContainer()

        container.addView(
            sectionTitle(
                "设置",
                "管理权限、本地歌词和缓存相关配置。"
            )
        )

        container.addView(
            card {
                addView(bigText("权限"))
                addView(settingRow("悬浮窗权限", if (Settings.canDrawOverlays(this@MainActivity)) "已开启" else "未开启"))
                addView(settingRow("通知权限", if (hasNotificationPermission()) "已开启" else "未开启"))
                addView(settingRow("通知访问权限", "点按钮前往系统设置确认"))
                addView(horizontalButtons(
                    "悬浮窗权限" to { requestOverlayPermission() },
                    "通知权限" to { requestNotificationPermissionIfNeeded() },
                    "通知访问" to { startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                ))
            }
        )

        container.addView(
            card {
                addView(bigText("本地歌词"))
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
                addView(bigText("关于"))
                addView(normalText("AirLyrics 1.0"))
                addView(smallHint("主界面已调整为三页式结构：媒体流 / 悬浮窗 / 设置。"))
            }
        )

        return scroll(container)
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
        textSize = style.textSizeSp.coerceAtMost(30f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = style.gravity
        setTextColor(style.textColor)
        setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
        setPadding(
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp),
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp)
        )
        background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp).toFloat()
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
                            setOnClickListener {
                                item.action()
                                refreshSelection(item.key)
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
            setOnClickListener { item.action() }
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
                            setOnClickListener { onPicked(swatchColor) }
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

            addView(label(if (selected) "已连接" else "可选择", if (selected) colorAccentLight else colorTextMuted))
            addView(bigText(appName))
            addView(normalText("$title - $artist"))
            addView(smallHint(state))
            setOnClickListener {
                MediaSourceStore.saveSelectedPackage(this@MainActivity, controller.packageName)
                notifyFloatingServiceSourceChanged(controller.packageName)
                renderCurrentPage()
            }
        }
    }

    private fun pageContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(24))
        }
    }

    private fun scroll(child: View): View {
        return ScrollView(this).apply {
            addView(child)
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
                setTextColor(Color.WHITE)
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
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(colorCard)
                setStroke(dp(1), colorStroke)
            }
            content()
        }
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
            setOnClickListener { onClick() }
        }
    }

    private fun refreshMediaButton(): View {
        val (buttonText, showProgress, buttonColor) = when (mediaRefreshState) {
            RefreshState.IDLE -> Triple("刷新媒体状态", false, colorAccent)
            RefreshState.REFRESHING -> Triple("刷新中", true, colorSurfaceLight)
            RefreshState.DONE -> Triple("已刷新", false, colorAccentSoft)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isEnabled = mediaRefreshState != RefreshState.REFRESHING
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(buttonColor)
            }

            if (showProgress) {
                addView(ProgressBar(this@MainActivity).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        setMargins(0, 0, dp(10), 0)
                    }
                })
            }

            addView(TextView(this@MainActivity).apply {
                text = buttonText
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })

            setOnClickListener {
                if (mediaRefreshState == RefreshState.REFRESHING) return@setOnClickListener
                startMediaRefreshFeedback()
            }
        }
    }

    private fun startMediaRefreshFeedback() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        mediaRefreshState = RefreshState.REFRESHING
        renderCurrentPage()

        mediaRefreshHandler.postDelayed({
            mediaRefreshState = RefreshState.DONE
            if (currentPage == Page.MEDIA) renderCurrentPage()
            mediaRefreshHandler.postDelayed(resetRefreshStateRunnable, 900)
        }, 650)
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
                    setOnClickListener { pair.second() }
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
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            setTextColor(Color.WHITE)
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

    private fun showFloatingLyrics() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_SHOW
        }
        startLyricsService(intent)
    }

    private fun hideFloatingLyrics() {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_HIDE
        }
        startLyricsService(intent)
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val colorBackground = Color.rgb(12, 16, 28)
    private val colorSurface = Color.rgb(18, 24, 40)
    private val colorSurfaceLight = Color.rgb(35, 44, 66)
    private val colorCard = Color.rgb(24, 31, 50)
    private val colorStroke = Color.rgb(48, 58, 84)
    private val colorAccent = Color.rgb(88, 131, 255)
    private val colorAccentLight = Color.rgb(144, 174, 255)
    private val colorAccentSoft = Color.rgb(72, 170, 139)
    private val colorText = Color.rgb(221, 228, 244)
    private val colorTextMuted = Color.rgb(142, 154, 184)
}
