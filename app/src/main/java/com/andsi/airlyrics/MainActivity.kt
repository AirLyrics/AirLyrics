package com.andsi.airlyrics

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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private enum class Page { MEDIA, FLOATING, SETTINGS }

    private var locked = false
    private var currentPage = Page.MEDIA
    private var contentContainer: FrameLayout? = null
    private val tabViews = mutableMapOf<Page, TextView>()

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
        setContentView(createMainView())
        autoSelectMediaSourceOnceIfNeeded()
        renderCurrentPage()
    }

    override fun onResume() {
        super.onResume()
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
        contentContainer?.removeAllViews()
        updateTabs()

        val pageView = when (currentPage) {
            Page.MEDIA -> createMediaPage()
            Page.FLOATING -> createFloatingPage()
            Page.SETTINGS -> createSettingsPage()
        }

        contentContainer?.addView(pageView)
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

        container.addView(
            sectionTitle(
                "悬浮窗",
                "这里先做成未来开发页，之后专门放歌词窗口外观和行为设置。"
            )
        )

        container.addView(
            card {
                addView(label("预览", colorTextMuted))
                addView(bigText("夜に駆ける"))
                addView(normalText("YOASOBI · 当前歌词预览"))
                addView(smallHint("未来这里可以实时预览字体、圆角、透明度和高亮颜色。"))
            }
        )

        container.addView(
            card {
                addView(bigText("显示控制"))
                addView(normalText("快速显示、隐藏或锁定悬浮歌词。"))
                addView(horizontalButtons(
                    "显示" to { showFloatingLyrics() },
                    "隐藏" to { hideFloatingLyrics() }
                ))
                addView(actionButton(if (locked) "锁定穿透：开启" else "锁定穿透：关闭") {
                    toggleLock()
                })
            }
        )

        container.addView(
            card {
                addView(bigText("外观设置"))
                addView(settingRow("字体大小", "开发中"))
                addView(settingRow("背景透明度", "开发中"))
                addView(settingRow("圆角大小", "开发中"))
                addView(settingRow("歌词高亮色", "开发中"))
            }
        )

        container.addView(
            card {
                addView(bigText("行为设置"))
                addView(settingRow("点击穿透", "开发中"))
                addView(settingRow("自动隐藏", "开发中"))
                addView(settingRow("横屏模式", "开发中"))
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
        locked = !locked
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = if (locked) FloatingLyricsService.ACTION_LOCK else FloatingLyricsService.ACTION_UNLOCK
        }
        startLyricsService(intent)
        renderCurrentPage()
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
