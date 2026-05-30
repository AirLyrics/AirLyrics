package com.andsi.airlyrics.app

import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.i18n.tr

import android.animation.LayoutTransition
import android.app.Dialog
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
import android.provider.OpenableColumns
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
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
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
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
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

    private var pendingImportAsWordByWord = false

    private val lyricsDocumentMimeTypes = arrayOf("*/*", "application/x-lrc", "application/lrc", "text/lrc", "text/plain", "text/*", "application/octet-stream")
    internal val importLyricsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val media = getCurrentMediaSnapshot()
            if (media == null || media.title.isBlank()) {
                Toast.makeText(this, tr("请先播放并选择一首歌，再为当前音乐导入歌词", "Please play and select a song before importing lyrics"), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val importAsWordByWord = pendingImportAsWordByWord
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (!isLikelyLyricsDocument(uri)) {
                val message = if (importAsWordByWord) {
                    tr("请选择 .lrc 逐字歌词文件", "Please choose a word-by-word .lrc file")
                } else {
                    tr("请选择 .lrc 普通歌词文件", "Please choose a plain .lrc lyrics file")
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val exists = if (importAsWordByWord) {
                LyricsStorage.hasKaraokeLyrics(
                    context = this,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs
                )
            } else {
                LyricsStorage.hasLocalLyrics(
                    context = this,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs
                )
            }

            if (exists) {
                val overwriteMessage = if (importAsWordByWord) {
                    "${media.displayText}\n\n这首歌已经有本地逐字歌词。覆盖后只替换逐字歌词缓存，普通歌词会继续保留。"
                } else {
                    "${media.displayText}\n\n这首歌已经有普通歌词。覆盖后只替换普通 LRC；如果已经导入逐字歌词，会继续保留。"
                }
                showAirConfirmDialog(
                    title = if (importAsWordByWord) tr("覆盖本地逐字歌词？", "Overwrite local word-by-word lyrics?") else tr("覆盖普通歌词？", "Overwrite plain lyrics?"),
                    message = overwriteMessage,
                    positiveText = tr("覆盖", "Overwrite")
                ) {
                    importLyricsForCurrentMedia(
                        uri = uri,
                        media = media,
                        overwrite = true,
                        importAsWordByWord = importAsWordByWord
                    )
                }
            } else {
                importLyricsForCurrentMedia(
                    uri = uri,
                    media = media,
                    overwrite = false,
                    importAsWordByWord = importAsWordByWord
                )
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
            Toast.makeText(this, tr("已设置歌词保存目录", "Lyrics save folder set"), Toast.LENGTH_LONG).show()
            renderCurrentPage(animateContent = false, animateTabs = false)
        }

    internal val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                tr("通知权限已开启", "Notification permission enabled")
            } else {
                tr("通知权限未开启，前台服务通知可能无法显示", "Notification permission is off; the foreground service notification may not show")
            }

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            renderCurrentPage()
        }

    internal val floatingWindowStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != BroadcastActions.WINDOW_VISIBILITY_CHANGED &&
                action != BroadcastActions.QUICK_CONTROL_CHANGED
            ) return

            val visible = intent.getBooleanExtra(
                BroadcastActions.EXTRA_WINDOW_VISIBLE,
                isQuickFloatingVisible()
            )
            locked = intent.getBooleanExtra(
                BroadcastActions.EXTRA_LOCKED,
                FloatingLyricsStyleStore.isLocked(this@MainActivity)
            )
            clickThrough = intent.getBooleanExtra(
                BroadcastActions.EXTRA_CLICK_THROUGH,
                FloatingLyricsStyleStore.isClickThrough(this@MainActivity)
            )
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
        restoreFloatingLyricsIfNeeded()
        renderCurrentPage()
    }

    override fun onResume() {
        super.onResume()
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = isQuickFloatingVisible()
        restoreFloatingLyricsIfNeeded()
        renderCurrentPage()
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

    internal fun currentLyricsOffsetMs(): Long? {
        val media = getCurrentMediaSnapshot() ?: return null
        return LyricsOffsetStore.getOffsetMs(this, media)
    }

    internal fun currentLyricsOffsetSummary(): String {
        val media = getCurrentMediaSnapshot() ?: return tr("等待当前音乐", "Waiting for current song")
        return LyricsOffsetStore.description(LyricsOffsetStore.getOffsetMs(this, media))
    }

    internal fun adjustLyricsOffsetForCurrentMedia(deltaMs: Long): Long? {
        val media = getCurrentMediaSnapshot() ?: return null
        val offset = LyricsOffsetStore.adjustOffsetMs(this, media, deltaMs)
        floatingController.applyLyricsOffset(offset)
        return offset
    }

    internal fun resetLyricsOffsetForCurrentMedia(): Boolean {
        val media = getCurrentMediaSnapshot() ?: return false
        LyricsOffsetStore.resetOffset(this, media)
        floatingController.applyLyricsOffset(0L)
        return true
    }

    internal fun showImportLyricsDialog() {
        val media = getCurrentMediaSnapshot()
        if (media == null || media.title.isBlank()) {
            Toast.makeText(this, tr("请先播放并选择一首歌，再为当前音乐导入歌词", "Please play and select a song before importing lyrics"), Toast.LENGTH_LONG).show()
            return
        }

        var dialog: Dialog? = null

        fun launchImport(asWordByWord: Boolean) {
            pendingImportAsWordByWord = asWordByWord
            dialog?.dismiss()
            importLyricsLauncher.launch(lyricsDocumentMimeTypes)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))

            addView(TextView(this@MainActivity).apply {
                text = media.displayText
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                setPadding(0, 0, 0, dp(8))
            })

            addView(TextView(this@MainActivity).apply {
                text = localizeText("建议使用 AirLyrics 统一 LRC 格式。普通歌词：[00:12.34]歌词；逐字歌词：[00:12.34]<00:12.34>字。")
                textSize = 13f
                setTextColor(colorTextMuted)
                setPadding(0, 0, 0, dp(10))
            })

            addView(importLyricsChoiceRow(
                title = tr("普通歌词（.lrc）", "Plain lyrics (.lrc)"),
                subtitle = tr("推荐格式：[00:12.34]这是一行歌词。导入后会保存为统一格式。", "Recommended: [00:12.34]This is a lyric line. It will be saved in normalized format."),
                primary = true
            ) { launchImport(false) })

            addView(importLyricsChoiceRow(
                title = tr("逐字歌词（增强 .lrc）", "Word-by-word lyrics (enhanced .lrc)"),
                subtitle = tr("推荐格式：[00:12.34]<00:12.34>这<00:12.50>是，用于逐字高亮。", "Recommended: [00:12.34]<00:12.34>T<00:12.50>ext for word highlighting."),
                primary = false
            ) { launchImport(true) })

            addView(importLyricsChoiceRow(
                title = tr("歌词格式说明", "Lyrics format guide"),
                subtitle = tr("查看普通 LRC 和 enhanced LRC 的示例。", "View examples for plain LRC and enhanced LRC."),
                primary = false
            ) { showLyricsFormatGuideDialog() })
        }

        dialog = showAirDialog(
            title = tr("选择导入类型", "Choose import type"),
            positiveText = null,
            negativeText = tr("取消", "Cancel"),
            body = {
                addView(content)
            }
        )
    }

    private fun importLyricsChoiceRow(
        title: String,
        subtitle: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = "$title\n$subtitle"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else colorTextStrong)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(8), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (primary) {
                    setColor(colorAccent)
                } else {
                    setColor(colorSurfaceLight)
                    setStroke(dp(1), colorStroke)
                }
            }
            enableSoftPressFeedback(0.97f)
            setOnClickListener {
                onClick()
                playTinyPulse(this)
            }
        }
    }

    private fun showLyricsFormatGuideDialog() {
        showAirInfoDialog(
            title = tr("歌词格式说明", "Lyrics format guide"),
            message = tr(
                "普通歌词推荐格式：\n[00:12.34]这是一行歌词\n[00:15.60]This is a lyric preview\n\n逐字歌词推荐 enhanced LRC：\n[00:12.34]<00:12.34>这<00:12.50>是<00:12.70>逐字歌词\n\n普通歌词导入后会保存为统一的 [mm:ss.xx]歌词 格式。逐字歌词只支持本地导入。",
                "Plain lyrics recommended format:\n[00:12.34]This is a lyric line\n[00:15.60]This is a lyric preview\n\nWord-by-word enhanced LRC:\n[00:12.34]<00:12.34>T<00:12.50>ext\n\nPlain lyrics are normalized to [mm:ss.xx]lyric format. Word-by-word lyrics only support local import."
            )
        )
    }



    private fun isLikelyLyricsDocument(uri: Uri): Boolean {
        val fileName = getDocumentDisplayName(uri).lowercase()
        val path = uri.lastPathSegment.orEmpty().lowercase()
        val mimeType = contentResolver.getType(uri).orEmpty().lowercase()

        return fileName.endsWith(".lrc") ||
            path.endsWith(".lrc") ||
            path.contains(".lrc") ||
            mimeType.isBlank() ||
            mimeType.startsWith("text/") ||
            mimeType == "application/x-lrc" ||
            mimeType == "application/lrc" ||
            mimeType == "application/octet-stream"
    }

    private fun getDocumentDisplayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
    }

    internal fun importLyricsForCurrentMedia(
        uri: Uri,
        media: CurrentMediaInfo,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
    ) {
        lyricsController.importLyricsForCurrentMedia(uri, media, overwrite, importAsWordByWord)
    }

    internal fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode) {
        lyricsController.deleteLyricsForCurrentMedia(media, mode)
    }

    internal fun getCurrentMediaSnapshot(): CurrentMediaInfo? {
        return lyricsController.getCurrentMediaSnapshot()
    }

    internal fun floatingPreviewSummary(style: FloatingLyricsStyle): String {
        return tr("当前：", "Current: ") + localizeText(FloatingLyricsStyleStore.getPresetTitle(style.presetName)) + " · ${style.textSizeSp.toInt()}sp · " + localizeText(FloatingLyricsStyleStore.getGravityTitle(style.gravity)) + " · " + tr("宽度", "Width") + " ${style.maxWidthPercent}%"
    }

    internal fun floatingDisplaySummary(): String {
        val lockedText = if (FloatingLyricsStyleStore.isLocked(this)) tr("锁定", "Locked") else tr("可拖动", "Draggable")
        val clickThroughText = if (FloatingLyricsStyleStore.isClickThrough(this)) tr("穿透", "Click-through") else tr("可点击", "Clickable")
        return "$lockedText · $clickThroughText"
    }

    internal fun floatingLockButtonText(): String {
        return if (FloatingLyricsStyleStore.isLocked(this)) tr("拖动锁定：开启", "Drag lock: on") else tr("拖动锁定：关闭", "Drag lock: off")
    }

    internal fun floatingClickThroughButtonText(): String {
        return if (FloatingLyricsStyleStore.isClickThrough(this)) tr("点击穿透：开启", "Click-through: on") else tr("点击穿透：关闭", "Click-through: off")
    }

    internal fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView {
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

    internal fun restoreFloatingLyricsIfNeeded() {
        floatingController.restoreVisibleWindowIfNeeded()
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

    internal fun notifyFloatingLyricsOffsetChanged(offsetMs: Long) {
        floatingController.applyLyricsOffset(offsetMs)
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
        val filter = IntentFilter().apply {
            addAction(BroadcastActions.WINDOW_VISIBILITY_CHANGED)
            addAction(BroadcastActions.QUICK_CONTROL_CHANGED)
        }

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
