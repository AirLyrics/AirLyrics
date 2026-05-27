package com.andsi.airlyrics

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
import com.andsi.airlyrics.core.settings.LyricsSettingsStore

class FloatingLyricsService : Service() {
    private lateinit var windowController: FloatingWindowController

    private val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    private val renderer = FloatingLyricsRenderer { lyricsView }
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

        startForeground(1, FloatingServiceNotification.create(this))
        registerMediaReceiver()
        syncHandler.post(syncRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BroadcastActions.SHOW -> showLyrics()
            BroadcastActions.HIDE -> {
                hideLyrics()
                stopSelf()
            }
            BroadcastActions.LOCK -> windowController.setLocked(true)
            BroadcastActions.UNLOCK -> windowController.setLocked(false)
            BroadcastActions.CLICK_THROUGH_ON -> windowController.setClickThrough(true)
            BroadcastActions.CLICK_THROUGH_OFF -> windowController.setClickThrough(false)
            BroadcastActions.APPLY_STYLE -> windowController.applyStyle()
            BroadcastActions.RELOAD_LYRICS -> reloadCurrentLyrics()
            BroadcastActions.SELECT_MEDIA_SOURCE -> selectMediaSource(
                intent.getStringExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE)
            )
            BroadcastActions.IMPORT_LYRICS -> intent.data?.let(::importLyrics)
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

    private fun reloadCurrentLyrics() {
        if (currentMedia.isEmpty) {
            clearLyricsState("♪ 等待媒体信息...")
            return
        }

        val requestKey = currentMedia.lyricsKey(
            extra = "reload|${SystemClock.uptimeMillis()}"
        )
        lastLyricsKey = requestKey
        activeLyricsRequestKey = requestKey
        loadLyricsForSong(media = currentMedia, requestKey = requestKey)
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

    private fun loadLyricsForSong(media: CurrentMediaInfo, requestKey: String) {
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
                durationMs = media.durationMs
            )

            Handler(Looper.getMainLooper()).post {
                if (activeLyricsRequestKey != requestKey) return@post
                applyLyricsResult(result = result, media = media)
            }
        }, "AirLyrics-LyricsRepository").start()
    }

    private fun applyLyricsResult(result: Result<LyricsProviderResult?>, media: CurrentMediaInfo) {
        val lyricText = result.getOrNull()?.lyrics

        if (lyricText != null) {
            renderer.parseAndShow(
                lyrics = lyricText,
                emptyText = "♪ 歌词解析为空\n${media.displayText}"
            )
            return
        }

        renderer.clear()
        renderer.show(notFoundText(media))
    }

    private fun notFoundText(media: CurrentMediaInfo): String {
        return if (LyricsSettingsStore.getLyricsSource(this) == LyricsSettingsStore.SOURCE_LOCAL_ONLY) {
            "♪ 仅使用本地歌词\n${media.displayText}\n未找到本地文件"
        } else if (media.artist.isNotBlank()) {
            "♪ ${media.title} - ${media.artist}\n未找到歌词"
        } else {
            "♪ ${media.title}\n未找到歌词"
        }
    }

    private fun importLyrics(uri: Uri) {
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
            duration = media.durationMs
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

    private fun showLyrics() {
        windowController.show()
    }

    private fun hideLyrics() {
        if (::windowController.isInitialized) {
            windowController.hide()
        }
    }

    private fun broadcastWindowVisibility(visible: Boolean) {
        val intent = Intent(BroadcastActions.WINDOW_VISIBILITY_CHANGED).apply {
            setPackage(packageName)
            putExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, visible)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        syncHandler.removeCallbacks(syncRunnable)
        runCatching { unregisterReceiver(mediaReceiver) }
        hideLyrics()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
