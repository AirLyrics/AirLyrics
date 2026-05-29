package com.andsi.airlyrics.app

import com.andsi.airlyrics.lyrics.storage.LyricsStorage

import android.animation.LayoutTransition
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
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
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.app.AppNavigator
import com.andsi.airlyrics.app.PermissionController
import com.andsi.airlyrics.app.controller.AppMediaController
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.LyricsController
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
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.createFloatingPage
import com.andsi.airlyrics.ui.pages.createMediaPage
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.OptionItem
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
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

class MainActivity : AppCompatActivity() {
    private val activityState = MainActivityState()
    private val viewRefs = MainActivityViewRefs()

    internal var locked by activityState::locked
    internal var clickThrough by activityState::clickThrough
    internal var currentPage by activityState::currentPage
    internal var settingsSubPage by activityState::settingsSubPage
    internal var quickFloatingVisible by activityState::quickFloatingVisible
    internal val pageScrollY by activityState::pageScrollY
    internal var renderedPage by activityState::renderedPage
    internal var renderedSettingsSubPage by activityState::renderedSettingsSubPage
    internal var mediaRefreshState by activityState::mediaRefreshState
    internal var mediaPageRefreshScheduled by activityState::mediaPageRefreshScheduled

    internal var contentContainer by viewRefs::contentContainer
    internal val tabViews by viewRefs::tabViews
    internal var tabRow by viewRefs::tabRow
    internal var tabHighlight by viewRefs::tabHighlight
    internal var floatingPanelBackHandler by viewRefs::floatingPanelBackHandler

    internal val uiActions: MainUiActions by lazy { createMainUiActions() }
    private val floatingController: FloatingController by lazy { FloatingController(this) }
    internal val appMediaController: AppMediaController by lazy { AppMediaController(this) }
    private val lyricsController: LyricsController by lazy { LyricsController(this) }

    internal val mediaRefreshHandler = Handler(Looper.getMainLooper())

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
            updateTabs(this@MainActivity)
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
        renderCurrentPage(animateContent = false, animateTabs = false)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (handleBackNavigation()) return
        super.onBackPressed()
    }

    internal fun handleBackNavigation(): Boolean {
        return AppNavigator.handleBackNavigation(this)
    }

    internal fun reloadFloatingLyrics() {
        floatingController.reloadLyrics()
    }

    internal fun reloadFloatingLyricsFromOnline() {
        floatingController.reloadLyricsFromOnline()
    }

    internal fun importLyricsForCurrentMedia(uri: Uri, media: CurrentMediaInfo, overwrite: Boolean) {
        lyricsController.importLyricsForCurrentMedia(uri, media, overwrite)
    }

    internal fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo) {
        lyricsController.deleteLyricsForCurrentMedia(media)
    }

    internal fun getCurrentMediaSnapshot(): CurrentMediaInfo? {
        return lyricsController.getCurrentMediaSnapshot()
    }

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

    internal fun showFloatingLyrics(): Boolean {
        return floatingController.showLyrics()
    }

    internal fun hideFloatingLyrics(): Boolean {
        return floatingController.hideLyrics()
    }

    internal fun toggleFloatingFromNav() {
        floatingController.toggleFromNav()
    }

    internal fun isQuickFloatingVisible(): Boolean {
        return floatingController.isQuickFloatingVisible()
    }

    internal fun updateQuickFloatingVisible(visible: Boolean) {
        floatingController.updateQuickFloatingVisible(visible)
    }

    internal fun toggleLock() {
        floatingController.toggleLock()
    }

    internal fun toggleClickThrough() {
        floatingController.toggleClickThrough()
    }

    internal fun applyFloatingPreset(preset: String) {
        floatingController.applyPreset(preset)
    }

    internal fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        floatingController.applyTextSize(textSizeSp, refreshPage)
    }

    internal fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true) {
        floatingController.applyTextColor(color, refreshPage)
    }

    internal fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true) {
        floatingController.applyBackgroundColor(color, refreshPage)
    }

    internal fun applyFloatingGravity(gravity: Int) {
        floatingController.applyGravity(gravity)
    }

    internal fun notifyFloatingStyleChanged() {
        floatingController.notifyStyleChanged()
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
        return appMediaController.getPlaybackStateText(state)
    }

    internal fun autoSelectMediaSourceOnceIfNeeded() {
        appMediaController.autoSelectSourceOnceIfNeeded()
    }

    internal fun getActiveMediaControllers(): List<MediaController> {
        return appMediaController.getActiveControllers()
    }

    internal fun notifyFloatingServiceSourceChangedIfVisible(packageName: String?) {
        floatingController.notifySourceChangedIfVisible(packageName)
    }

    internal fun getAppName(packageName: String): String {
        return appMediaController.getAppName(packageName)
    }

    internal fun showLyricsDir() {
        lyricsController.showLyricsDir()
    }

    internal fun requestOverlayPermission() {
        PermissionController.requestOverlayPermission(this)
    }

    internal fun hasNotificationPermission(): Boolean {
        return PermissionController.hasNotificationPermission(this)
    }


    internal fun hasNotificationListenerAccess(): Boolean {
        return PermissionController.hasNotificationListenerAccess(this)
    }

    internal fun requestNotificationPermissionIfNeeded() {
        PermissionController.requestNotificationPermissionIfNeeded(this)
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
