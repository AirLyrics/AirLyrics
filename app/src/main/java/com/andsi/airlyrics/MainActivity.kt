package com.andsi.airlyrics

import android.animation.LayoutTransition
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.core.settings.FloatingLyricsStyleStore
import com.andsi.airlyrics.core.settings.QuickFloatingStore
import com.andsi.airlyrics.core.settings.ThemeSettingsStore
import com.andsi.airlyrics.core.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.animateChildrenCascade
import com.andsi.airlyrics.ui.components.animatePageEnter
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingStatusPreviewCard
import com.andsi.airlyrics.ui.components.horizontalButtons
import com.andsi.airlyrics.ui.components.label
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.components.sectionTitle
import com.andsi.airlyrics.ui.components.settingRow
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.components.softLayoutTransition
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.pages.createFloatingPage
import com.andsi.airlyrics.ui.pages.createMediaPage
import com.andsi.airlyrics.ui.pages.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBackground
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

class MainActivity : AppCompatActivity() {
    internal enum class Page { MEDIA, FLOATING, SETTINGS }
    internal enum class SettingsSubPage { HOME, SYSTEM, THEME, FLOATING, LYRICS, ABOUT }

    internal var locked = false
    internal var clickThrough = false
    internal var currentPage = Page.MEDIA
    internal var settingsSubPage = SettingsSubPage.HOME
    internal var contentContainer: FrameLayout? = null
    internal val tabViews = mutableMapOf<Page, TextView>()
    internal var tabRow: LinearLayout? = null
    internal var tabHighlight: WaterTabHighlightView? = null
    internal var quickFloatingVisible = false
    internal val pageScrollY = mutableMapOf<Page, Int>()
    internal var renderedPage = Page.MEDIA
    internal var renderedSettingsSubPage = SettingsSubPage.HOME
    internal var floatingPanelBackHandler: (() -> Boolean)? = null

    internal enum class RefreshState { IDLE, REFRESHING, DONE }

    internal val mediaRefreshHandler = Handler(Looper.getMainLooper())
    internal var mediaRefreshState = RefreshState.IDLE
    internal var mediaPageRefreshScheduled = false

    internal val mediaStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BroadcastActions.MEDIA_UPDATE) return
            scheduleMediaPageRefresh()
        }
    }

    internal val importLyricsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val media = getCurrentMediaSnapshot()
            if (media == null || media.title.isBlank()) {
                Toast.makeText(this, "请先播放并选择一首歌，再为当前音乐导入歌词", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val exists = LyricsStorage.hasLocalLyrics(
                context = this,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )

            if (exists) {
                AlertDialog.Builder(this)
                    .setTitle("当前音乐已有歌词")
                    .setMessage("${media.displayText}\n\n要覆盖已有本地歌词吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("覆盖") { _, _ ->
                        importLyricsForCurrentMedia(uri = uri, media = media, overwrite = true)
                    }
                    .show()
            } else {
                importLyricsForCurrentMedia(uri = uri, media = media, overwrite = false)
            }
        }

    internal val selectLyricsDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            LyricsStorage.saveLyricsDirUri(this, uri)
            Toast.makeText(this, "已设置歌词保存目录", Toast.LENGTH_LONG).show()
            renderCurrentPage(animateContent = false, animateTabs = false)
        }

    internal val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                "通知权限已开启"
            } else {
                "通知权限未开启，前台服务通知可能无法显示"
            }

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            renderCurrentPage()
        }

    internal val floatingWindowStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BroadcastActions.WINDOW_VISIBILITY_CHANGED) return

            val visible = intent.getBooleanExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, false)
            updateQuickFloatingVisible(visible)
            updateTabs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = isQuickFloatingVisible()
        applySystemBarsTheme()
        setContentView(createMainView())
        registerFloatingWindowStateReceiver()
        registerMediaStatusReceiver()
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

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (handleBackNavigation()) return
        super.onBackPressed()
    }

    internal fun handleBackNavigation(): Boolean {
        if (currentPage == Page.FLOATING && floatingPanelBackHandler?.invoke() == true) {
            return true
        }

        if (currentPage == Page.SETTINGS && settingsSubPage != SettingsSubPage.HOME) {
            settingsSubPage = SettingsSubPage.HOME
            renderCurrentPage()
            return true
        }

        return false
    }

    internal fun createMainView(): View {
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

    internal fun createBottomTabs(): View {
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

    internal fun addTab(parent: LinearLayout, page: Page, title: String) {
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

    internal fun renderCurrentPage(animateContent: Boolean = true, animateTabs: Boolean = true) {
        val container = contentContainer ?: return
        (container.getChildAt(0) as? ScrollView)?.let { scrollView ->
            pageScrollY[renderedPage] = scrollView.scrollY
        }

        val oldPage = renderedPage
        val oldSubPage = renderedSettingsSubPage
        val shouldAnimate = animateContent && container.childCount > 0 && (currentPage != oldPage || settingsSubPage != oldSubPage)
        val slideFromRight = when {
            currentPage != oldPage -> currentPage.ordinal > oldPage.ordinal
            currentPage == Page.SETTINGS -> settingsSubPage.ordinal > oldSubPage.ordinal
            else -> true
        }

        container.removeAllViews()
        updateTabs(animate = animateTabs)
        if (currentPage != Page.FLOATING) {
            floatingPanelBackHandler = null
        }

        val pageView = when (currentPage) {
            Page.MEDIA -> createMediaPage(animateContent = animateContent)
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

    internal fun quickFloatingTabText(visible: Boolean): SpannableString {
        val icon = if (visible) "×" else "♪"
        val label = if (visible) "隐藏" else "显示"
        return SpannableString("$icon\n$label").apply {
            setSpan(AbsoluteSizeSpan(24, true), 0, icon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(10, true), icon.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    internal fun measureTabTextWidth(tab: TextView): Float {
        val lines = tab.text.toString().split('\n')
        return lines.maxOfOrNull { tab.paint.measureText(it) } ?: tab.paint.measureText(tab.text.toString())
    }

    internal fun updateTabs(animate: Boolean = true) {
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
            val targetScale = if (quickControlSelected) 1.14f else if (selected) 1.02f else 1f
            val targetAlpha = if (selected) 1f else 0.86f
            if (animate) {
                view.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .alpha(targetAlpha)
                    .setDuration(190L)
                    .setInterpolator(OvershootInterpolator(1.08f))
                    .start()
            } else {
                view.animate().cancel()
                view.scaleX = targetScale
                view.scaleY = targetScale
                view.alpha = targetAlpha
            }
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















    internal fun settingsHomeHeader(): View {
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

    internal fun settingsBackHeader(title: String, subtitle: String): View {
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
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    enableSoftPressFeedback(0.94f)
                    setOnClickListener {
                        settingsSubPage = SettingsSubPage.HOME
                        renderCurrentPage()
                    }
                })
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

    internal fun themeToggleButton(): TextView {
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

    internal fun settingsCategoryCard(
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

    internal fun localLyricsRow(item: LyricsStorage.LocalLyricsItem): View {
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
                text = item.displayTitle
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = "${item.displaySubtitle} · ${LyricsStorage.formatLocalLyricsItem(item)}"
                textSize = 12f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    internal fun changelogItem(title: String, body: String): View {
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

    internal fun permissionSummary(): String {
        val opened = listOf(
            Settings.canDrawOverlays(this),
            hasNotificationPermission(),
            hasNotificationListenerAccess()
        ).count { it }
        return "已开启 $opened / 3 项基础权限"
    }

    internal fun getAppVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    internal fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun reloadFloatingLyrics() {
        if (!quickFloatingVisible) return

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.RELOAD_LYRICS
        }
        startLyricsService(intent)
    }

    internal fun reloadFloatingLyricsFromOnline() {
        if (!quickFloatingVisible) {
            Toast.makeText(this, "请先显示悬浮窗，再重新联网搜索歌词", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.RELOAD_ONLINE_LYRICS
        }
        startLyricsService(intent)
        Toast.makeText(this, "正在重新联网搜索歌词", Toast.LENGTH_SHORT).show()
    }

    internal fun importLyricsForCurrentMedia(uri: Uri, media: CurrentMediaInfo, overwrite: Boolean) {
        val imported = LyricsStorage.importLyricsFromUri(
            context = this,
            uri = uri,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs,
            album = media.album,
            overwrite = overwrite
        )

        if (imported) {
            Toast.makeText(this, "已为当前音乐导入歌词", Toast.LENGTH_LONG).show()
            reloadFloatingLyrics()
            renderCurrentPage(animateContent = false, animateTabs = false)
        } else {
            Toast.makeText(this, "导入失败，可能不是可读取的歌词文件", Toast.LENGTH_LONG).show()
        }
    }

    internal fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo) {
        val deleted = LyricsStorage.deleteLocalLyrics(
            context = this,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        )

        if (deleted) {
            Toast.makeText(this, "已移除当前音乐的本地歌词", Toast.LENGTH_LONG).show()
            reloadFloatingLyrics()
            renderCurrentPage(animateContent = false, animateTabs = false)
        } else {
            Toast.makeText(this, "当前音乐没有可移除的本地歌词", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun getCurrentMediaSnapshot(): CurrentMediaInfo? {
        val selectedPackage = MediaSourceStore.getSelectedPackage(this)
        val controllers = getActiveMediaControllers().filter { it.metadata != null || it.playbackState != null }
        val controller = controllers.firstOrNull { it.packageName == selectedPackage }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
            ?: return null

        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        if (title.isBlank()) return null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val state = controller.playbackState

        return CurrentMediaInfo(
            sourcePackage = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L
        )
    }

    internal data class OptionItem(
        val title: String,
        val selected: Boolean,
        val action: () -> Unit
    )

    internal data class KeyedOptionItem(
        val key: String,
        val title: String,
        val selected: Boolean,
        val action: () -> Unit
    )

    internal data class FloatingSettingTile(
        val title: String,
        val subtitle: String,
        val mark: String,
        val onClick: (View) -> Unit
    )

    internal fun floatingPreviewSummary(style: FloatingLyricsStyle): String {
        return "当前：${FloatingLyricsStyleStore.getPresetTitle(style.presetName)} · ${style.textSizeSp.toInt()}sp · ${FloatingLyricsStyleStore.getGravityTitle(style.gravity)} · 宽度 ${style.maxWidthPercent}%"
    }

    internal fun floatingDisplaySummary(): String {
        val lockedText = if (FloatingLyricsStyleStore.isLocked(this)) "锁定" else "可拖动"
        val clickThroughText = if (FloatingLyricsStyleStore.isClickThrough(this)) "穿透" else "可点击"
        return "$lockedText · $clickThroughText"
    }

    internal fun floatingLockButtonText(): String {
        return if (FloatingLyricsStyleStore.isLocked(this)) "拖动锁定：开启" else "拖动锁定：关闭"
    }

    internal fun floatingClickThroughButtonText(): String {
        return if (FloatingLyricsStyleStore.isClickThrough(this)) "点击穿透：开启" else "点击穿透：关闭"
    }

    internal fun floatingPreviewText(text: String, style: FloatingLyricsStyle): TextView {
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

    internal fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle) {
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

    internal fun optionGrid(items: List<OptionItem>): LinearLayout {
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

    internal fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout {
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

    internal fun optionButton(item: OptionItem): TextView {
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

    internal fun applyOptionButtonState(button: TextView, title: String, selected: Boolean) {
        button.text = if (selected) "✓ $title" else title
        button.setTextColor(if (selected) Color.WHITE else colorText)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) colorAccent else colorSurfaceLight)
            setStroke(dp(1), if (selected) colorAccentLight else colorStroke)
        }
    }

    internal fun sliderRow(
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

    internal fun colorControl(
        title: String,
        color: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        var red = Color.red(color)
        var green = Color.green(color)
        var blue = Color.blue(color)
        var alpha = Color.alpha(color)
        var rgbExpanded = false

        val standardColors = listOf(
            "蓝" to Color.rgb(66, 165, 245),
            "紫" to Color.rgb(126, 87, 194),
            "粉" to Color.rgb(236, 64, 122),
            "青" to Color.rgb(38, 198, 218),
            "绿" to Color.rgb(102, 187, 106),
            "橙" to Color.rgb(255, 167, 38),
            "红" to Color.rgb(239, 83, 80),
            "白" to Color.WHITE
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)

            val preview = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(colorText)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(preview)
            addView(smallHint("点击标准色会立即应用；改动 RGB 后会进入自定义颜色状态。"))

            val swatchViews = mutableListOf<Pair<Int?, TextView>>()
            val swatchGrid = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(swatchGrid)

            val fineTuneButton = actionButton("展开 RGB 细调") { }
            val rgbPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            addView(fineTuneButton)
            addView(rgbPanel)

            fun currentColor(): Int = Color.argb(alpha, red, green, blue)

            fun selectedStandardColor(): Int? {
                val current = currentColor()
                return standardColors.firstOrNull { (_, swatchColor) ->
                    Color.red(current) == Color.red(swatchColor) &&
                        Color.green(current) == Color.green(swatchColor) &&
                        Color.blue(current) == Color.blue(swatchColor) &&
                        Color.alpha(current) == Color.alpha(swatchColor)
                }?.second
            }

            fun swatchBackground(swatchColor: Int, selected: Boolean): GradientDrawable {
                return GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(swatchColor)
                    setStroke(dp(if (selected) 3 else 1), if (selected) colorAccent else colorStroke)
                }
            }

            fun refreshSwatches() {
                val current = currentColor()
                val selectedPreset = selectedStandardColor()
                swatchViews.forEach { (presetColor, view) ->
                    val selected = if (presetColor == null) {
                        selectedPreset == null
                    } else {
                        selectedPreset != null &&
                            Color.red(selectedPreset) == Color.red(presetColor) &&
                            Color.green(selectedPreset) == Color.green(presetColor) &&
                            Color.blue(selectedPreset) == Color.blue(presetColor)
                    }
                    val displayColor = presetColor ?: current
                    view.background = swatchBackground(displayColor, selected)
                    view.setTextColor(if (isDarkColor(displayColor)) Color.WHITE else Color.rgb(28, 34, 46))
                }
            }

            fun refreshPreview(dispatch: Boolean) {
                val newColor = currentColor()
                preview.text = "$title：${FloatingLyricsStyleStore.colorSummary(newColor)}"
                preview.background = colorPreviewBackground(newColor)
                refreshSwatches()
                if (dispatch) onChanged(newColor)
            }

            fun colorSliderRow(
                sliderTitle: String,
                initialValue: Int,
                onValueChanged: (Int) -> Unit
            ): Pair<SeekBar, TextView> {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(4))
                }
                val valueText = TextView(this@MainActivity).apply {
                    text = "$sliderTitle：$initialValue"
                    textSize = 14f
                    setTextColor(colorText)
                    setPadding(0, 0, 0, dp(6))
                }
                row.addView(valueText)
                val seekBar = SeekBar(this@MainActivity).apply {
                    max = 255
                    progress = initialValue.coerceIn(0, 255)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            valueText.text = "$sliderTitle：$progress"
                            if (fromUser) {
                                onValueChanged(progress)
                                refreshPreview(dispatch = true)
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                row.addView(seekBar)
                rgbPanel.addView(row)
                return seekBar to valueText
            }

            val redSlider = colorSliderRow("R", red) { red = it }
            val greenSlider = colorSliderRow("G", green) { green = it }
            val blueSlider = colorSliderRow("B", blue) { blue = it }
            val alphaSlider = colorSliderRow("不透明度", alpha) { alpha = it }

            fun setSlider(pair: Pair<SeekBar, TextView>, titleText: String, value: Int) {
                pair.first.progress = value.coerceIn(0, 255)
                pair.second.text = "$titleText：$value"
            }

            fun syncSliders() {
                setSlider(redSlider, "R", red)
                setSlider(greenSlider, "G", green)
                setSlider(blueSlider, "B", blue)
                setSlider(alphaSlider, "不透明度", alpha)
            }

            fun makeSwatch(label: String, presetColor: Int?, onClick: () -> Unit): TextView {
                return TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                    enableSoftPressFeedback(0.9f)
                    setOnClickListener {
                        onClick()
                        playTinyPulse(this)
                    }
                    swatchViews.add(presetColor to this)
                }
            }

            val swatches = standardColors.map { (label, swatchColor) ->
                Pair(label, swatchColor as Int?)
            } + listOf("自定义" to null)

            swatches.chunked(3).forEach { rowItems ->
                swatchGrid.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, (label, presetColor) ->
                        val button = makeSwatch(label, presetColor) {
                            if (presetColor == null) {
                                rgbExpanded = true
                                rgbPanel.visibility = View.VISIBLE
                                fineTuneButton.text = "收起 RGB 细调"
                            } else {
                                red = Color.red(presetColor)
                                green = Color.green(presetColor)
                                blue = Color.blue(presetColor)
                                syncSliders()
                                refreshPreview(dispatch = true)
                            }
                        }
                        addView(button.apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                                setMargins(
                                    if (index == 0) 0 else dp(5),
                                    dp(8),
                                    if (index == rowItems.lastIndex) 0 else dp(5),
                                    0
                                )
                            }
                        })
                    }
                    repeat(3 - rowItems.size) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(5), 0, 0, 0)
                            }
                        })
                    }
                })
            }

            fineTuneButton.setOnClickListener {
                rgbExpanded = !rgbExpanded
                rgbPanel.visibility = if (rgbExpanded) View.VISIBLE else View.GONE
                fineTuneButton.text = if (rgbExpanded) "收起 RGB 细调" else "展开 RGB 细调"
                playTinyPulse(fineTuneButton)
            }

            syncSliders()
            refreshPreview(dispatch = false)
        }
    }

    internal fun colorPreviewBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(withAlpha(color, 42))
            setStroke(dp(1), withAlpha(color, 190))
        }
    }

    internal fun isDarkColor(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        return luminance < 150
    }

    internal fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    internal fun mediaSourceCard(controller: MediaController, selected: Boolean): View {
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
                notifyFloatingServiceSourceChangedIfVisible(controller.packageName)
                updateMediaSourceSelectionVisuals(controller.packageName)
                playTinyPulse(this)
            }
        }
    }

    internal fun settingGrid(vararg items: FloatingSettingTile): LinearLayout {
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

    internal fun floatingTile(item: FloatingSettingTile): LinearLayout {
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

    internal fun floatingFocusBubble(
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

    internal fun showFloatingSettingPanel(
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

    internal fun refreshMediaButton(): View {
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

    internal fun startMediaRefreshFeedback(onStateChanged: () -> Unit) {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        mediaRefreshState = RefreshState.REFRESHING
        onStateChanged()

        mediaRefreshHandler.postDelayed({
            mediaRefreshState = RefreshState.DONE
            onStateChanged()
            mediaRefreshHandler.postDelayed({
                if (currentPage == Page.MEDIA) {
                    renderCurrentPage(animateContent = false, animateTabs = false)
                }
            }, 260L)
        }, 650L)
    }

    internal fun updateMediaSourceSelectionVisuals(selectedPackage: String) {
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

    internal fun showFloatingLyrics(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return false
        }

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.SHOW
        }
        startLyricsService(intent)
        updateQuickFloatingVisible(true)
        updateTabs()
        return true
    }

    internal fun hideFloatingLyrics(): Boolean {
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.HIDE
        }
        startLyricsService(intent)
        updateQuickFloatingVisible(false)
        updateTabs()
        return true
    }

    internal fun toggleFloatingFromNav() {
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

    internal fun isQuickFloatingVisible(): Boolean {
        return QuickFloatingStore.isVisible(this)
    }

    internal fun updateQuickFloatingVisible(visible: Boolean) {
        quickFloatingVisible = visible
        QuickFloatingStore.setVisible(this, visible)
    }

    internal fun toggleLock() {
        locked = !FloatingLyricsStyleStore.isLocked(this)
        FloatingLyricsStyleStore.setLocked(this, locked)
        if (!quickFloatingVisible) return

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = if (locked) BroadcastActions.LOCK else BroadcastActions.UNLOCK
        }
        startLyricsService(intent)
    }

    internal fun toggleClickThrough() {
        clickThrough = !FloatingLyricsStyleStore.isClickThrough(this)
        FloatingLyricsStyleStore.setClickThrough(this, clickThrough)
        if (!quickFloatingVisible) return

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = if (clickThrough) {
                BroadcastActions.CLICK_THROUGH_ON
            } else {
                BroadcastActions.CLICK_THROUGH_OFF
            }
        }
        startLyricsService(intent)
    }

    internal fun applyFloatingPreset(preset: String) {
        FloatingLyricsStyleStore.applyPreset(this, preset)
        notifyFloatingStyleChanged()
    }

    internal fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextSize(this, textSizeSp)
        notifyFloatingStyleChanged()
        if (refreshPage) renderCurrentPage()
    }

    internal fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextColor(this, color)
        notifyFloatingStyleChanged()
        if (refreshPage) renderCurrentPage()
    }

    internal fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setBackgroundColor(this, color)
        notifyFloatingStyleChanged()
        if (refreshPage) renderCurrentPage()
    }

    internal fun applyFloatingGravity(gravity: Int) {
        FloatingLyricsStyleStore.setGravity(this, gravity)
        notifyFloatingStyleChanged()
    }

    internal fun notifyFloatingStyleChanged() {
        if (!quickFloatingVisible) return

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.APPLY_STYLE
        }
        startLyricsService(intent)
    }

    internal fun isDarkTheme(): Boolean {
        return ThemeSettingsStore.isDark(this)
    }

    internal fun setDarkTheme(enabled: Boolean) {
        ThemeSettingsStore.setDark(this, enabled)
    }

    internal fun toggleThemeMode() {
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

    internal fun applySystemBarsTheme() {
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

    internal fun getPlaybackStateText(state: Int?): String {
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

    internal fun autoSelectMediaSourceOnceIfNeeded() {
        if (MediaSourceStore.getSelectedPackage(this) != null) return

        val controllers = getActiveMediaControllers()
            .filter { it.metadata != null || it.playbackState != null }

        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return

        val packageName = controller.packageName
        MediaSourceStore.saveSelectedPackage(this, packageName)

    }

    internal fun getActiveMediaControllers(): List<MediaController> {
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

    internal fun notifyFloatingServiceSourceChangedIfVisible(packageName: String?) {
        if (!quickFloatingVisible) return

        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.SELECT_MEDIA_SOURCE
            putExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE, packageName)
        }
        startLyricsService(intent)
    }

    internal fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    internal fun showLyricsDir() {
        val path = LyricsStorage.getLyricsDirRawPath(this)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("歌词保存目录", path))

        Toast.makeText(this, "歌词保存目录已复制", Toast.LENGTH_LONG).show()
    }

    internal fun requestOverlayPermission() {
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

    internal fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }


    internal fun hasNotificationListenerAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabledListeners.split(':').any { item ->
            item.contains(packageName, ignoreCase = true)
        }
    }

    internal fun requestNotificationPermissionIfNeeded() {
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


    internal fun scheduleMediaPageRefresh() {
        if (currentPage != Page.MEDIA) return
        if (mediaPageRefreshScheduled) return

        mediaPageRefreshScheduled = true
        mediaRefreshHandler.postDelayed({
            mediaPageRefreshScheduled = false
            if (currentPage == Page.MEDIA) {
                renderCurrentPage(animateContent = false, animateTabs = false)
            }
        }, 120L)
    }

    internal fun registerFloatingWindowStateReceiver() {
        val filter = IntentFilter(BroadcastActions.WINDOW_VISIBILITY_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatingWindowStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(floatingWindowStateReceiver, filter)
        }
    }

    internal fun registerMediaStatusReceiver() {
        val filter = IntentFilter(BroadcastActions.MEDIA_UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaStatusReceiver, filter)
        }
    }

    internal fun startLyricsService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    internal fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(floatingWindowStateReceiver) }
        runCatching { unregisterReceiver(mediaStatusReceiver) }
        super.onDestroy()
    }


}
