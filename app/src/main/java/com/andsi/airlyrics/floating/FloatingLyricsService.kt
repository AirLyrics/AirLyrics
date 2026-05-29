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
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.media.MediaSourceStore

class FloatingLyricsService : Service() {
    private lateinit var windowController: FloatingWindowController

    private val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    private val renderer = FloatingLyricsRenderer(
        textViewProvider = { lyricsView },
        contentModeProvider = { LyricsSettingsStore.getContentDisplayMode(this) },
        lineModeProvider = { LyricsSettingsStore.getLineDisplayMode(this) },
        switchAnimationModeProvider = { LyricsSettingsStore.getSwitchAnimationMode(this) }
    )
    private val syncHandler = Handler(Looper.getMainLooper())

    private var currentMedia: CurrentMediaInfo = CurrentMediaInfo.Empty
    private var lastLyricsKey: String? = null
    private var activeLyricsRequestKey: String? = null
    private var selectedSourcePackage: String? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            renderer.tick()
            syncHandler.postDelayed(this, 300L)
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

    private fun reloadCurrentLyrics(
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false
    ) {
        if (currentMedia.isEmpty) {
            clearLyricsState("♪ 等待媒体信息...")
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
                "♪ 尚未选择媒体来源，请进入 App 选择歌词来源"
            } else {
                "♪ 已选择媒体来源，等待该播放器更新..."
            }
        )
    }

    private fun clearLyricsState(message: String) {
        lastLyricsKey = null
        activeLyricsRequestKey = null
        currentMedia = CurrentMediaInfo.Empty
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
                "♪ 正在查找歌词...\n${media.displayText}"
            } else {
                "Ⅱ 暂停中\n${media.displayText}"
            }
        )

        Thread({
            val result = LyricsRepository.findLyrics(
                context = this,
                title = media.title,
                artist = media.artist,
                album = media.album,
                durationMs = media.durationMs,
                bypassLocal = bypassLocal,
                forceSaveOnline = forceSaveOnline,
                ignoreAutoSearchSetting = ignoreAutoSearchSetting
            )

            Handler(Looper.getMainLooper()).post {
                if (activeLyricsRequestKey != requestKey) return@post
                applyLyricsResult(result = result, media = media)
            }
        }, "AirLyrics-LyricsRepository").start()
    }

    private fun applyLyricsResult(result: Result<LyricsProviderResult?>, media: CurrentMediaInfo) {
        val lyricsResult = result.getOrNull()
        val lyricText = lyricsResult?.lyrics

        if (lyricText != null) {
            renderer.parseAndShow(
                lyrics = lyricText,
                translatedLyrics = lyricsResult.translatedLyrics,
                emptyText = "♪ 歌词解析为空\n${media.displayText}"
            )
            return
        }

        renderer.clear()
        renderer.show(lookupFailureText(result.exceptionOrNull(), media))
    }

    private fun lookupFailureText(error: Throwable?, media: CurrentMediaInfo): String {
        val providerMessage = (error as? LyricsLookupException)?.userMessage()
        return if (providerMessage != null) {
            "♪ ${media.displayText}\n$providerMessage"
        } else {
            notFoundText(media)
        }
    }

    private fun notFoundText(media: CurrentMediaInfo): String {
        return if (!LyricsSettingsStore.isAutoSearchOnlineEnabled(this)) {
            "♪ 仅使用本地歌词\n${media.displayText}\n未找到本地文件"
        } else {
            val sourceTitle = LyricsSettingsStore.getLyricsSourceTitle(this)
            "♪ ${media.displayText}\n当前来源：$sourceTitle\n未找到歌词"
        }
    }

    private fun importLyrics(uri: Uri, overwrite: Boolean) {
        val media = currentMedia

        if (media.title.isBlank()) {
            renderer.show("♪ 当前没有正在播放的歌曲，无法绑定歌词")
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
            renderer.show("♪ 导入歌词失败")
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
            renderer.parseAndShow(
                lyrics = localLyrics,
                emptyText = "♪ 已导入歌词，但内容为空"
            )
        } else {
            renderer.show("♪ 导入歌词失败")
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
            if (!showLyrics(feedback = "已显示")) {
                refreshQuickControls("需要悬浮窗权限")
                showQuickFeedback("请先开启悬浮窗权限")
            }
        } else {
            hideLyrics(feedback = "已隐藏")
        }
    }

    private fun toggleLockFromNotification() {
        val nextLocked = !FloatingLyricsStyleStore.isLocked(this)
        setLocked(locked = nextLocked, feedback = if (nextLocked) "已锁定" else "已解锁")
    }

    private fun toggleClickThroughFromNotification() {
        val nextClickThrough = !FloatingLyricsStyleStore.isClickThrough(this)
        setClickThrough(
            clickThrough = nextClickThrough,
            feedback = if (nextClickThrough) "已穿透" else "可触摸"
        )
    }

    private fun toggleAdjustModeFromNotification() {
        val currentlyEditing = !FloatingLyricsStyleStore.isLocked(this) &&
            !FloatingLyricsStyleStore.isClickThrough(this)
        val nextEditing = !currentlyEditing

        windowController.setLocked(!nextEditing)
        windowController.setClickThrough(!nextEditing)

        if (nextEditing) {
            refreshQuickControls("可拖动")
        } else {
            refreshQuickControls("已锁定并穿透")
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        runCatching { unregisterReceiver(mediaReceiver) }
        if (::windowController.isInitialized) {
            windowController.hide()
        }
        QuickFloatingStore.setVisible(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
