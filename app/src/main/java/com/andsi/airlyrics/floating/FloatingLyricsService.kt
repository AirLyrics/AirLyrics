package com.andsi.airlyrics.floating

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.LyricsLookupRunner
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.i18n.localizedLyricsSourceTitle
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.i18n.tr
import com.andsi.airlyrics.i18n.localizedLyricsLookupMessage

class FloatingLyricsService : Service() {
    private lateinit var windowController: FloatingWindowController

    private val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    private val renderer = FloatingLyricsRenderer(
        textViewProvider = { lyricsView },
        contentModeProvider = { LyricsSettingsStore.getContentDisplayMode(this) },
        lineModeProvider = { LyricsSettingsStore.getLineDisplayMode(this) },
        switchAnimationModeProvider = { LyricsSettingsStore.getSwitchAnimationMode(this) },
        karaokeEnabledProvider = { LyricsSettingsStore.isKaraokeLyricsEnabled(this) },
        karaokeHighlightColorProvider = { FloatingLyricsStyleStore.getStyle(this).karaokeHighlightColor },
        noTranslationTextProvider = { tr("当前歌词没有翻译", "No translation for this lyric").toString() }
    )
    private val syncHandler = Handler(Looper.getMainLooper())
    private val lyricsLookupRunner = LyricsLookupRunner(threadNamePrefix = "AirLyrics-LyricsRepository")

    private var currentMedia: CurrentMediaInfo = CurrentMediaInfo.Empty
    private var lastLyricsKey: String? = null
    private var activeLyricsRequestKey: String? = null
    private var selectedSourcePackage: String? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            renderer.tick()
            syncHandler.postDelayed(this, if (renderer.isKaraokeActive()) 80L else 300L)
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BroadcastActions.MEDIA_UPDATE) return

            val sourcePackage = intent.getStringExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE).orEmpty()
            val title = intent.getStringExtra("title").orEmpty()
            val artist = intent.getStringExtra("artist").orEmpty()
            val album = intent.getStringExtra("album").orEmpty()
            val isPlaying = intent.getBooleanExtra("isPlaying", false)
            val duration = intent.getLongExtra("duration", 0L)
            val position = intent.getLongExtra("position", 0L)

            if (title.isBlank()) return
            if (!shouldAcceptMediaUpdate(sourcePackage)) return

            val media = CurrentMediaInfo(
                sourcePackage = sourcePackage,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                isPlaying = isPlaying,
                positionMs = position
            )

            currentMedia = media
            renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this@FloatingLyricsService, media))
            renderer.updatePlayback(positionMs = position, isPlaying = isPlaying)

            val lyricsKey = media.lyricsKey()
            if (lyricsKey == lastLyricsKey) return

            lastLyricsKey = lyricsKey
            activeLyricsRequestKey = lyricsKey
            loadLyricsForSong(media = media, requestKey = lyricsKey)
        }
    }

    override fun onCreate() {
        super.onCreate()

        selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
        windowController = FloatingWindowController(this) { visible ->
            broadcastWindowVisibility(visible)
        }

        startForeground(FloatingServiceNotification.NOTIFICATION_ID, FloatingServiceNotification.create(this, currentQuickControlState()))
        registerMediaReceiver()
        syncHandler.post(syncRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BroadcastActions.SHOW -> showLyrics(feedback = null)
            BroadcastActions.HIDE -> {
                hideLyrics(feedback = null)
                stopSelf()
            }
            BroadcastActions.LOCK -> setLocked(locked = true, feedback = null)
            BroadcastActions.UNLOCK -> setLocked(locked = false, feedback = null)
            BroadcastActions.CLICK_THROUGH_ON -> setClickThrough(clickThrough = true, feedback = null)
            BroadcastActions.CLICK_THROUGH_OFF -> setClickThrough(clickThrough = false, feedback = null)
            BroadcastActions.NOTIFICATION_TOGGLE_VISIBLE -> toggleVisibleFromNotification()
            BroadcastActions.NOTIFICATION_TOGGLE_LOCK -> toggleLockFromNotification()
            BroadcastActions.NOTIFICATION_TOGGLE_CLICK_THROUGH -> toggleClickThroughFromNotification()
            BroadcastActions.NOTIFICATION_TOGGLE_ADJUST_MODE -> toggleAdjustModeFromNotification()
            BroadcastActions.APPLY_STYLE -> {
                windowController.applyStyle()
                renderer.refresh()
            }
            BroadcastActions.RELOAD_LYRICS -> reloadCurrentLyrics()
            BroadcastActions.APPLY_LYRICS_OFFSET -> applyLyricsOffset(
                intent.getLongExtra(BroadcastActions.EXTRA_LYRICS_OFFSET_MS, 0L)
            )
            BroadcastActions.RELOAD_ONLINE_LYRICS -> reloadCurrentLyrics(
                bypassLocal = true,
                forceSaveOnline = true,
                ignoreAutoSearchSetting = true
            )
            BroadcastActions.SELECT_MEDIA_SOURCE -> selectMediaSource(
                intent.getStringExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE)
            )
            BroadcastActions.IMPORT_LYRICS -> intent.data?.let { uri ->
                importLyrics(uri = uri, overwrite = intent.getBooleanExtra(BroadcastActions.EXTRA_OVERWRITE_LYRICS, true))
            }
        }

        return START_STICKY
    }

    private fun registerMediaReceiver() {
        val filter = IntentFilter(BroadcastActions.MEDIA_UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }
    }

    private fun applyLyricsOffset(offsetMs: Long) {
        renderer.setLyricsOffset(offsetMs)
        renderer.refresh()
    }

    private fun reloadCurrentLyrics(
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false
    ) {
        if (currentMedia.isEmpty) {
            clearLyricsState("♪ " + tr("等待媒体信息", "Waiting for media") + "...")
            return
        }

        val requestKey = currentMedia.lyricsKey(
            extra = "reload|${SystemClock.uptimeMillis()}"
        )
        lastLyricsKey = requestKey
        activeLyricsRequestKey = requestKey
        loadLyricsForSong(
            media = currentMedia,
            requestKey = requestKey,
            bypassLocal = bypassLocal,
            forceSaveOnline = forceSaveOnline,
            ignoreAutoSearchSetting = ignoreAutoSearchSetting
        )
    }

    private fun selectMediaSource(packageName: String?) {
        selectedSourcePackage = packageName
        MediaSourceStore.saveSelectedPackage(this, packageName)
        clearLyricsState(
            if (packageName == null) {
                "♪ " + tr("尚未选择媒体来源，请进入 App 选择歌词来源", "No media source selected. Open the app and choose one.")
            } else {
                "♪ " + tr("已选择媒体来源，等待该播放器更新", "Media source selected. Waiting for that player to update") + "..."
            }
        )
    }

    private fun clearLyricsState(message: String) {
        lastLyricsKey = null
        activeLyricsRequestKey = null
        lyricsLookupRunner.cancelActive()
        currentMedia = CurrentMediaInfo.Empty
        renderer.setLyricsOffset(0L)
        renderer.clear()
        renderer.show(message)
    }

    private fun shouldAcceptMediaUpdate(sourcePackage: String): Boolean {
        if (sourcePackage.isBlank()) return false

        val selectedPackage = selectedSourcePackage ?: return false
        return sourcePackage == selectedPackage
    }

    private fun loadLyricsForSong(
        media: CurrentMediaInfo,
        requestKey: String,
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false
    ) {
        renderer.show(
            if (media.isPlaying) {
                "♪ ${tr("正在查找歌词", "Searching lyrics")}...\n${media.displayText}"
            } else {
                "Ⅱ ${tr("暂停中", "Paused")}\n${media.displayText}"
            }
        )

        lyricsLookupRunner.submit(
            requestKey = requestKey,
            lookup = { token ->
                LyricsRepository.findLyrics(
                    context = this,
                    title = media.title,
                    artist = media.artist,
                    album = media.album,
                    durationMs = media.durationMs,
                    bypassLocal = bypassLocal,
                    forceSaveOnline = forceSaveOnline,
                    ignoreAutoSearchSetting = ignoreAutoSearchSetting,
                    cancellationToken = token
                )
            },
            callback = { completedRequestKey, result ->
                if (activeLyricsRequestKey == completedRequestKey) {
                    applyLyricsResult(result = result, media = media)
                }
            }
        )
    }

    private fun applyLyricsResult(result: Result<LyricsProviderResult?>, media: CurrentMediaInfo) {
        val lyricsResult = result.getOrNull()
        val lyricText = lyricsResult?.lyrics

        if (lyricText != null) {
            renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media))
            renderer.parseAndShow(
                lyrics = lyricText,
                translatedLyrics = lyricsResult.translatedLyrics,
                karaokeLines = lyricsResult.karaokeLines,
                emptyText = "♪ " + tr("歌词解析为空", "Parsed lyrics are empty") + "\n" + media.displayText
            )
            return
        }

        renderer.clear()
        renderer.show(lookupFailureText(result.exceptionOrNull(), media))
    }

    private fun lookupFailureText(error: Throwable?, media: CurrentMediaInfo): String {
        val lookupError = error as? LyricsLookupException
        return if (lookupError != null) {
            "♪ ${media.displayText}\n${localizedLyricsLookupMessage(lookupError)}"
        } else {
            notFoundText(media)
        }
    }

    private fun notFoundText(media: CurrentMediaInfo): String {
        return if (!LyricsSettingsStore.isAutoSearchOnlineEnabled(this)) {
            "♪ ${tr("仅使用本地歌词", "Using local lyrics only")}\n${media.displayText}\n${tr("未找到本地文件", "Local file not found")}"
        } else {
            val sourceTitle = localizedLyricsSourceTitle(LyricsSettingsStore.getLyricsSearchSource(this))
            "♪ ${media.displayText}\n${tr("当前来源：", "Source: ")}$sourceTitle\n${tr("未找到歌词", "Lyrics not found")}"
        }
    }

    private fun importLyrics(uri: Uri, overwrite: Boolean) {
        lyricsLookupRunner.cancelActive()
        activeLyricsRequestKey = null
        val media = currentMedia

        if (media.title.isBlank()) {
            renderer.show("♪ " + tr("当前没有正在播放的歌曲，无法绑定歌词", "No song is playing, so lyrics cannot be bound"))
            return
        }

        val imported = LyricsStorage.importLyricsFromUri(
            context = this,
            uri = uri,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs,
            album = media.album,
            overwrite = overwrite
        )

        if (!imported) {
            renderer.show("♪ " + tr("导入歌词失败", "Lyrics import failed"))
            return
        }

        val localLyrics = LyricsStorage.readLocalLyrics(
            context = this,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        )

        if (localLyrics != null) {
            lastLyricsKey = media.lyricsKey()
            activeLyricsRequestKey = lastLyricsKey
            renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media))
            renderer.parseAndShow(
                lyrics = localLyrics,
                emptyText = "♪ " + tr("已导入歌词，但内容为空", "Lyrics imported, but the content is empty")
            )
        } else {
            renderer.show("♪ " + tr("导入歌词失败", "Lyrics import failed"))
        }
    }

    private fun showLyrics(feedback: String? = null): Boolean {
        val shown = windowController.show()
        QuickFloatingStore.setVisible(this, shown)
        refreshQuickControls(feedback)
        return shown
    }

    private fun hideLyrics(feedback: String? = null) {
        if (::windowController.isInitialized) {
            windowController.hide()
        }
        QuickFloatingStore.setVisible(this, false)
        refreshQuickControls(feedback)
    }

    private fun setLocked(locked: Boolean, feedback: String? = null) {
        windowController.setLocked(locked)
        refreshQuickControls(feedback)
    }

    private fun setClickThrough(clickThrough: Boolean, feedback: String? = null) {
        windowController.setClickThrough(clickThrough)
        refreshQuickControls(feedback)
    }

    private fun toggleVisibleFromNotification() {
        val nextVisible = !windowController.isVisible
        if (nextVisible) {
            if (!showLyrics(feedback = tr("已显示", "Shown"))) {
                refreshQuickControls(tr("需要悬浮窗权限", "Overlay permission required"))
                showQuickFeedback(tr("请先开启悬浮窗权限", "Please enable overlay permission first"))
            }
        } else {
            hideLyrics(feedback = tr("已隐藏", "Hidden"))
        }
    }

    private fun toggleLockFromNotification() {
        val nextLocked = !FloatingLyricsStyleStore.isLocked(this)
        setLocked(locked = nextLocked, feedback = if (nextLocked) tr("已锁定", "Locked") else tr("已解锁", "Unlocked"))
    }

    private fun toggleClickThroughFromNotification() {
        val nextClickThrough = !FloatingLyricsStyleStore.isClickThrough(this)
        setClickThrough(
            clickThrough = nextClickThrough,
            feedback = if (nextClickThrough) tr("已穿透", "Click-through on") else tr("可触摸", "Touchable")
        )
    }

    private fun toggleAdjustModeFromNotification() {
        val currentlyEditing = !FloatingLyricsStyleStore.isLocked(this) &&
            !FloatingLyricsStyleStore.isClickThrough(this)
        val nextEditing = !currentlyEditing

        windowController.setLocked(!nextEditing)
        windowController.setClickThrough(!nextEditing)

        if (nextEditing) {
            refreshQuickControls(tr("可拖动", "Draggable"))
        } else {
            refreshQuickControls(tr("已锁定并穿透", "Locked + click-through"))
        }
    }

    private fun refreshQuickControls(feedback: String? = null) {
        val notification = FloatingServiceNotification.create(this, currentQuickControlState(feedback))
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FloatingServiceNotification.NOTIFICATION_ID, notification)
        broadcastQuickControlState()
    }

    private fun currentQuickControlState(feedback: String? = null): FloatingServiceNotification.QuickControlState {
        return FloatingServiceNotification.QuickControlState(
            visible = ::windowController.isInitialized && windowController.isVisible,
            locked = FloatingLyricsStyleStore.isLocked(this),
            clickThrough = FloatingLyricsStyleStore.isClickThrough(this),
            feedback = feedback
        )
    }

    private fun showQuickFeedback(message: String) {
        Toast.makeText(this, localizeText(message), Toast.LENGTH_SHORT).show()
    }

    private fun broadcastWindowVisibility(visible: Boolean) {
        val intent = Intent(BroadcastActions.WINDOW_VISIBILITY_CHANGED).apply {
            setPackage(packageName)
            putExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, visible)
            putExtra(BroadcastActions.EXTRA_LOCKED, FloatingLyricsStyleStore.isLocked(this@FloatingLyricsService))
            putExtra(BroadcastActions.EXTRA_CLICK_THROUGH, FloatingLyricsStyleStore.isClickThrough(this@FloatingLyricsService))
        }
        sendBroadcast(intent)
    }

    private fun broadcastQuickControlState() {
        val intent = Intent(BroadcastActions.QUICK_CONTROL_CHANGED).apply {
            setPackage(packageName)
            putExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, ::windowController.isInitialized && windowController.isVisible)
            putExtra(BroadcastActions.EXTRA_LOCKED, FloatingLyricsStyleStore.isLocked(this@FloatingLyricsService))
            putExtra(BroadcastActions.EXTRA_CLICK_THROUGH, FloatingLyricsStyleStore.isClickThrough(this@FloatingLyricsService))
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        syncHandler.removeCallbacks(syncRunnable)
        lyricsLookupRunner.shutdown()
        runCatching { unregisterReceiver(mediaReceiver) }
        if (::windowController.isInitialized) {
            windowController.hide(notifyVisibilityChanged = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
