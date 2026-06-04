package com.andsi.airlyrics.app

import com.andsi.airlyrics.R

import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.importer.LyricsImportValidator
import com.andsi.airlyrics.i18n.localizedFloatingGravityTitle
import com.andsi.airlyrics.i18n.localizedFloatingPresetTitle
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.i18n.localizedAssetText

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
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
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
import java.util.concurrent.Executors

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
    private val appIoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "airlyrics-app-io").apply { isDaemon = true }
    }

    internal val mediaRefreshHandler = Handler(Looper.getMainLooper())
    internal var currentLyricsLoadGeneration = 0
    internal var recentLyricsLoadGeneration = 0

    internal val mediaStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BroadcastActions.MEDIA_UPDATE,
                BroadcastActions.MEDIA_SOURCE_LOST -> scheduleMediaPageRefresh()
            }
        }
    }

    private var pendingImportAsWordByWord = false

    private val lyricsDocumentMimeTypes = arrayOf("*/*", "application/x-lrc", "application/lrc", "text/lrc", "text/plain", "text/*", "application/octet-stream")
    internal val importLyricsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val media = getCurrentMediaSnapshot()
            if (media == null || media.title.isBlank()) {
                Toast.makeText(this, getString(R.string.ui_select_song_before_importing), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val importAsWordByWord = pendingImportAsWordByWord

            if (!LyricsImportValidator.isLikelyLyricsDocument(this, uri)) {
                val message = if (importAsWordByWord) {
                    getString(R.string.ui_please_choose_an_enhanced_lrc_file)
                } else {
                    getString(R.string.ui_please_choose_a_plain_lrc_lyrics_file)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            if (LyricsImportValidator.isLyricsDocumentTooLarge(this, uri)) {
                Toast.makeText(this, getString(R.string.ui_lrc_file_too_large), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            importLyricsForCurrentMedia(
                uri = uri,
                media = media,
                overwrite = false,
                importAsWordByWord = importAsWordByWord
            )
        }

    internal val selectLyricsDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            val permissionGranted = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.isSuccess

            if (!permissionGranted) {
                Toast.makeText(this, getString(R.string.ui_lyrics_folder_permission_failed), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            if (!LyricsStorage.validateLyricsDir(this, uri)) {
                Toast.makeText(this, getString(R.string.ui_lyrics_folder_write_failed), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            LyricsStorage.saveLyricsDirUri(this, uri)
            Toast.makeText(this, getString(R.string.ui_lyrics_save_folder_set), Toast.LENGTH_LONG).show()
            renderCurrentPage(animateContent = false, animateTabs = false)
        }

    internal val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                getString(R.string.ui_notification_permission_enabled)
            } else {
                getString(R.string.ui_notif_permission_off_warning)
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
                quickFloatingVisible
            )
            locked = intent.getBooleanExtra(
                BroadcastActions.EXTRA_LOCKED,
                FloatingLyricsStyleStore.isLocked(this@MainActivity)
            )
            clickThrough = intent.getBooleanExtra(
                BroadcastActions.EXTRA_CLICK_THROUGH,
                FloatingLyricsStyleStore.isClickThrough(this@MainActivity)
            )
            updateQuickFloatingActualVisible(visible)
            updateTabs(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageSettingsStore.applyAppLocale(this)
        super.onCreate(savedInstanceState)
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = false
        applySystemBarsTheme()
        registerBackNavigationCallback()
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
        restoreFloatingLyricsIfNeeded()
        renderCurrentPage()
    }

    private fun registerBackNavigationCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBackNavigation()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
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
        val media = getCurrentMediaSnapshot() ?: return getString(R.string.ui_waiting_for_current_song)
        return localizedOffsetDescription(LyricsOffsetStore.getOffsetMs(this, media))
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
            Toast.makeText(this, getString(R.string.ui_select_song_before_importing), Toast.LENGTH_LONG).show()
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
            setPadding(dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.Sm))

            addView(TextView(this@MainActivity).apply {
                text = media.displayText
                textSize = AirUiTokens.TextSize.Button
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl))
            })

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.ui_plain_and_enhanced_lrc_examples_short)
                textSize = AirUiTokens.TextSize.BodySmall
                setTextColor(colorTextMuted)
                setPadding(0, 0, 0, dp(AirUiTokens.Space.Xxl))
            })

            addView(importLyricsChoiceRow(
                title = getString(R.string.ui_plain_lyrics_lrc),
                subtitle = getString(R.string.ui_plain_lrc_recommended_format_hint),
                primary = true
            ) { launchImport(false) })

            addView(importLyricsChoiceRow(
                title = getString(R.string.ui_enhanced_lrc_lyrics_enhanced_lrc),
                subtitle = getString(R.string.ui_recommended_enhanced_lrc_format),
                primary = false
            ) { launchImport(true) })

            addView(importLyricsChoiceRow(
                title = getString(R.string.ui_lyrics_format_guide),
                subtitle = getString(R.string.ui_view_lrc_examples_hint),
                primary = false
            ) { showLyricsFormatGuideDialog() })
        }

        dialog = showAirDialog(
            title = getString(R.string.ui_choose_import_type),
            positiveText = null,
            negativeText = getString(R.string.ui_cancel),
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
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else colorTextStrong)
            setLineSpacing(dp(AirUiTokens.Space.Xxs).toFloat(), 1f)
            setPadding(dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(AirUiTokens.Space.Xl), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
                if (primary) {
                    setColor(colorAccent)
                } else {
                    setColor(colorSurfaceLight)
                    setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
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
            title = getString(R.string.ui_lyrics_format_guide),
            message = localizedAssetText(
                baseName = "help/lyrics_format",
                fallback = getString(R.string.ui_lyrics_format_guide_body)
            )
        )
    }



    internal fun runOnAppIo(block: () -> Unit) {
        appIoExecutor.execute(block)
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            runOnUiThread {
                if (!isDestroyed) block()
            }
        }
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
        return getString(
            R.string.floating_preview_summary,
            localizedFloatingPresetTitle(style.presetName),
            style.textSizeSp.toInt(),
            localizedFloatingGravityTitle(style.gravity),
            style.maxWidthPercent
        )
    }

    internal fun floatingDisplaySummary(): String {
        val lockedText = if (FloatingLyricsStyleStore.isLocked(this)) getString(R.string.ui_locked) else getString(R.string.ui_draggable)
        val clickThroughText = if (FloatingLyricsStyleStore.isClickThrough(this)) getString(R.string.ui_click_through) else getString(R.string.ui_clickable)
        return "$lockedText · $clickThroughText"
    }

    internal fun floatingLockButtonText(): String {
        return if (FloatingLyricsStyleStore.isLocked(this)) getString(R.string.ui_drag_lock_on) else getString(R.string.ui_drag_lock_off)
    }

    internal fun floatingClickThroughButtonText(): String {
        return if (FloatingLyricsStyleStore.isClickThrough(this)) getString(R.string.ui_click_through_on) else getString(R.string.ui_click_through_off)
    }

    internal fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView {
        return TextView(this).apply {
            this.text = text
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Sm))
            layoutParams = params
            applyFloatingPreviewStyle(style)
        }
    }

    internal fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle) {
        textSize = AirUiTokens.TextSize.Title
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

    internal fun updateQuickFloatingActualVisible(visible: Boolean) {
        floatingController.updateQuickFloatingActualVisible(visible)
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
            ?.setDuration(AirUiTokens.Layout.FastFadeMs)
            ?.withEndAction {
                setContentView(createMainView())
                renderCurrentPage()
                contentContainer?.alpha = 0f
                contentContainer?.animate()
                    ?.alpha(1f)
                    ?.setDuration(AirUiTokens.Layout.RestoreFadeMs)
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
        val lightTheme = !isDarkTheme()
        enableEdgeToEdge(
            statusBarStyle = if (lightTheme) {
                SystemBarStyle.light(Color.BLACK, Color.BLACK)
            } else {
                SystemBarStyle.dark(Color.BLACK)
            },
            navigationBarStyle = if (lightTheme) {
                SystemBarStyle.light(colorSurface, colorSurface)
            } else {
                SystemBarStyle.dark(colorSurface)
            }
        )
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


    // Coalesces frequent media updates into a single lightweight page refresh.
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
        ContextCompat.registerReceiver(
            this,
            floatingWindowStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    internal fun registerMediaStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(BroadcastActions.MEDIA_UPDATE)
            addAction(BroadcastActions.MEDIA_SOURCE_LOST)
        }
        ContextCompat.registerReceiver(
            this,
            mediaStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    internal fun startLyricsServiceSafely(intent: Intent): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.isSuccess
    }

    internal fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        appIoExecutor.shutdownNow()
        runCatching { unregisterReceiver(floatingWindowStateReceiver) }
        runCatching { unregisterReceiver(mediaStatusReceiver) }
        super.onDestroy()
    }

}
