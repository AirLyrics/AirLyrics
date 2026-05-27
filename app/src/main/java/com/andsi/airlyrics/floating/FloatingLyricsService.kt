package com.andsi.airlyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class FloatingLyricsService : Service() {
    private lateinit var windowController: FloatingWindowController

    private val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    private var lastLyricsKey: String? = null
    private var activeLyricsRequestKey: String? = null

    private var currentLyrics: List<LrcLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentDuration: Long = 0L
    private var selectedSourcePackage: String? = null

    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val syncRunnable = object : Runnable {
        override fun run() {
            updateCurrentLyricLine()

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
            if (!shouldAcceptMediaUpdate(sourcePackage, isPlaying)) return

            currentTitle = title
            currentArtist = artist
            currentAlbum = album
            currentDuration = duration
            currentIsPlaying = isPlaying
            currentPositionMs = position
            lastPositionUpdateUptimeMs = SystemClock.uptimeMillis()

            val lyricsKey = "$sourcePackage|$title|$artist|$album|${duration / 1000L}"

            if (lyricsKey == lastLyricsKey) {
                return
            }

            lastLyricsKey = lyricsKey

            activeLyricsRequestKey = lyricsKey

            loadLyricsForSong(
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                isPlaying = isPlaying,
                requestKey = lyricsKey
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
        windowController = FloatingWindowController(this) { visible ->
            broadcastWindowVisibility(visible)
        }
        startForeground(1, createNotification())

        val filter = IntentFilter(BroadcastActions.MEDIA_UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }

        syncHandler.post(syncRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BroadcastActions.SHOW -> showLyrics()
            BroadcastActions.HIDE -> {
                hideLyrics()
                stopSelf()
            }
            BroadcastActions.LOCK -> setLocked(true)
            BroadcastActions.UNLOCK -> setLocked(false)
            BroadcastActions.CLICK_THROUGH_ON -> setClickThrough(true)
            BroadcastActions.CLICK_THROUGH_OFF -> setClickThrough(false)
            BroadcastActions.APPLY_STYLE -> applyStyleToCurrentWindow()
            BroadcastActions.RELOAD_LYRICS -> reloadCurrentLyrics()
            BroadcastActions.SELECT_MEDIA_SOURCE -> selectMediaSource(
                intent.getStringExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE)
            )

            BroadcastActions.IMPORT_LYRICS -> {
                val uri = intent.data
                if (uri != null) {
                    importLyrics(uri)
                }
            }
        }

        return START_STICKY
    }



    private fun reloadCurrentLyrics() {
        if (currentTitle.isBlank()) {
            lastLyricsKey = null
            activeLyricsRequestKey = null
            currentLyrics = emptyList()
            lyricsView?.text = "♪ 等待媒体信息..."
            return
        }

        val sourcePackage = selectedSourcePackage.orEmpty()
        val requestKey = "$sourcePackage|$currentTitle|$currentArtist|$currentAlbum|${currentDuration / 1000L}|reload|${SystemClock.uptimeMillis()}"
        lastLyricsKey = requestKey
        activeLyricsRequestKey = requestKey

        loadLyricsForSong(
            title = currentTitle,
            artist = currentArtist,
            album = currentAlbum,
            duration = currentDuration,
            isPlaying = currentIsPlaying,
            requestKey = requestKey
        )
    }

    private fun selectMediaSource(packageName: String?) {
        selectedSourcePackage = packageName
        MediaSourceStore.saveSelectedPackage(this, packageName)
        lastLyricsKey = null
        activeLyricsRequestKey = null
        currentLyrics = emptyList()
        currentPositionMs = 0L
        lyricsView?.text = if (packageName == null) {
            "♪ 尚未选择媒体来源，请进入 App 选择歌词来源"
        } else {
            "♪ 已选择媒体来源，等待该播放器更新..."
        }
    }

    private fun shouldAcceptMediaUpdate(sourcePackage: String, isPlaying: Boolean): Boolean {
        if (sourcePackage.isBlank()) return false

        val selectedPackage = selectedSourcePackage ?: return false
        return sourcePackage == selectedPackage
    }

    private fun loadLyricsForSong(
        title: String,
        artist: String,
        album: String,
        duration: Long,
        isPlaying: Boolean,
        requestKey: String
    ) {
        val mediaText = if (artist.isNotBlank()) {
            "♪ $title - $artist"
        } else {
            "♪ $title"
        }

        val localLyrics = LyricsStorage.readLocalLyrics(
            context = this,
            title = title,
            artist = artist,
            duration = duration
        )

        if (localLyrics != null) {
            if (activeLyricsRequestKey != requestKey) return

            currentLyrics = LrcParser.parse(localLyrics)

            lyricsView?.text = if (currentLyrics.isNotEmpty()) {
                LrcParser.findCurrentLine(currentLyrics, getEstimatedPositionMs())?.text
                    ?: currentLyrics.first().text
            } else {
                "♪ 本地歌词为空\n$mediaText"
            }

            return
        }

        if (LyricsSettingsStore.getLyricsSource(this) == LyricsSettingsStore.SOURCE_LOCAL_ONLY) {
            currentLyrics = emptyList()
            lyricsView?.text = "♪ 仅使用本地歌词\n$mediaText\n未找到本地文件"
            return
        }

        lyricsView?.text = if (isPlaying) {
            "♪ 正在查找歌词...\n$mediaText"
        } else {
            "Ⅱ 暂停中\n$mediaText"
        }

        LyricsFetcher.fetchSyncedLyrics(this, title, artist, album, duration) { result ->
            val lyricText = result.getOrNull()

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (activeLyricsRequestKey != requestKey) {
                    return@post
                }

                if (lyricText != null) {
                    if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) {
                        LyricsStorage.saveLyrics(
                            context = this,
                            title = title,
                            artist = artist,
                            duration = duration,
                            lyrics = lyricText
                        )
                    }

                    currentLyrics = LrcParser.parse(lyricText)

                    lyricsView?.text = if (currentLyrics.isNotEmpty()) {
                        LrcParser.findCurrentLine(currentLyrics, getEstimatedPositionMs())?.text
                            ?: currentLyrics.first().text
                    } else {
                        "♪ 歌词解析为空\n$mediaText"
                    }
                } else {
                    currentLyrics = emptyList()

                    lyricsView?.text = if (artist.isNotBlank()) {
                        "♪ $title - $artist\n未找到歌词"
                    } else {
                        "♪ $title\n未找到歌词"
                    }
                }
            }
        }
    }

    private fun importLyrics(uri: Uri) {
        val title = currentTitle
        val artist = currentArtist
        val duration = currentDuration

        if (title.isBlank()) {
            lyricsView?.text = "♪ 当前没有正在播放的歌曲，无法绑定歌词"
            return
        }

        val imported = LyricsStorage.importLyricsFromUri(
            context = this,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration
        )

        if (!imported) {
            lyricsView?.text = "♪ 导入歌词失败"
            return
        }

        val localLyrics = LyricsStorage.readLocalLyrics(
            context = this,
            title = title,
            artist = artist,
            duration = duration
        )

        if (localLyrics != null) {
            currentLyrics = LrcParser.parse(localLyrics)
            val sourcePackage = selectedSourcePackage.orEmpty()
            lastLyricsKey = "$sourcePackage|$title|$artist|$currentAlbum|${duration / 1000L}"
            activeLyricsRequestKey = lastLyricsKey

            lyricsView?.text = if (currentLyrics.isNotEmpty()) {
                LrcParser.findCurrentLine(currentLyrics, getEstimatedPositionMs())?.text
                    ?: currentLyrics.first().text
            } else {
                "♪ 已导入歌词，但内容为空"
            }
        } else {
            lyricsView?.text = "♪ 导入歌词失败"
        }
    }

    private fun showLyrics() {
        windowController.show()
    }

    private fun applyStyleToCurrentWindow() {
        windowController.applyStyle()
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

    private fun setLocked(locked: Boolean) {
        windowController.setLocked(locked)
    }

    private fun setClickThrough(clickThrough: Boolean) {
        windowController.setClickThrough(clickThrough)
    }

    private fun createNotification(): Notification {
        val channelId = "floating_lyrics"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Lyrics",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("悬浮歌词正在运行")
            .setContentText("透明歌词窗口已启动")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun getEstimatedPositionMs(): Long {
        if (!currentIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return currentPositionMs
        }

        val elapsedMs = SystemClock.uptimeMillis() - lastPositionUpdateUptimeMs
        return currentPositionMs + elapsedMs.coerceAtLeast(0L)
    }

    private fun updateCurrentLyricLine() {
        if (currentLyrics.isEmpty()) return

        val line = LrcParser.findCurrentLine(currentLyrics, getEstimatedPositionMs()) ?: return
        lyricsView?.text = line.text
    }

    override fun onDestroy() {
        syncHandler.removeCallbacks(syncRunnable)
        runCatching { unregisterReceiver(mediaReceiver) }
        hideLyrics()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}