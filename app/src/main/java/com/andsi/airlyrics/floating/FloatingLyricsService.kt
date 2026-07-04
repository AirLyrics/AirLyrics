package com.andsi.airlyrics.floating

import com.andsi.airlyrics.R

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.LyricsLookupRunner
import com.andsi.airlyrics.lyrics.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.MediaNotificationListenerService
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.i18n.localizedLyricsSourceTitle
import com.andsi.airlyrics.i18n.localizedLyricsLookupMessage

class FloatingLyricsService : Service() {
    private lateinit var windowController: FloatingLyricsWindow

    private val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    private val renderer = FloatingLyricsRenderer(
        textViewProvider = { lyricsView },
        contentModeProvider = { LyricsSettingsStore.getContentDisplayMode(this) },
        lineModeProvider = { LyricsSettingsStore.getLineDisplayMode(this) },
        switchAnimationModeProvider = { LyricsSettingsStore.getSwitchAnimationMode(this) },
        karaokeEnabledProvider = { LyricsSettingsStore.isKaraokeLyricsEnabled(this) },
        karaokeHighlightColorProvider = { FloatingLyricsStyleStore.getStyle(this).karaokeHighlightColor },
        noTranslationTextProvider = { getString(R.string.ui_no_translation_for_this_lyric) }
    )
    private val syncHandler = Handler(Looper.getMainLooper())
    private val lyricsLookupRunner = LyricsLookupRunner(threadNamePrefix = "AirLyrics-LyricsRepository")

    private var currentMedia: CurrentMediaInfo = CurrentMediaInfo.Empty
    private var lastPlaybackLyricsKey: PlaybackLyricsKey? = null
    private var activeLyricsLookupRequestKey: LyricsLookupRequestKey? = null
    private var selectedSourcePackage: String? = null
    private var mediaRestoreAttempt = 0
    private val mediaSnapshotGate = MediaSnapshotGate()

    private val syncRunnable = object : Runnable {
        override fun run() {
            renderer.tick()
            syncHandler.postDelayed(this, if (renderer.isKaraokeActive()) 80L else 300L)
        }
    }

    private val mediaRestoreRunnable = Runnable {
        restoreCurrentMediaOrRetry()
    }

    private val currentMediaRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSelectedCurrentMediaInfo()
            if (shouldObserveSelectedMedia()) {
                syncHandler.postDelayed(this, CURRENT_MEDIA_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sourcePackage = intent.getStringExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE).orEmpty()

            if (action == BroadcastActions.MEDIA_SOURCE_LOST) {
                handleMediaSourceLost(sourcePackage)
                return
            }

            if (action != BroadcastActions.MEDIA_UPDATE) return

            val title = intent.getStringExtra("title").orEmpty()
            val artist = intent.getStringExtra("artist").orEmpty()
            val album = intent.getStringExtra("album").orEmpty()
            val isPlaying = intent.getBooleanExtra("isPlaying", false)
            val duration = intent.getLongExtra("duration", 0L)
            val position = intent.getLongExtra("position", 0L)
            val snapshotSequence = intent.getLongExtra(
                BroadcastActions.EXTRA_MEDIA_SNAPSHOT_SEQUENCE,
                CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE
            )

            applyCurrentMediaInfo(
                CurrentMediaInfo(
                    sourcePackage = sourcePackage,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    isPlaying = isPlaying,
                    positionMs = position,
                    snapshotSequence = snapshotSequence
                )
            )
        }
    }

    override fun onCreate() {
        LanguageSettingsStore.applyAppLocale(this)
        super.onCreate()

        selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
        windowController = FloatingLyricsWindow(this) { visible ->
            broadcastWindowVisibility(visible)
        }

        startForeground(FloatingServiceNotification.NOTIFICATION_ID, FloatingServiceNotification.create(this, currentQuickControlState()))
        registerMediaReceiver()
        syncHandler.post(syncRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            handleCommand(intent, startId)
        }.onFailure {
            // Keep the overlay's current truth intact for non-window command failures.
            // WindowManager operations already hide/broadcast from FloatingLyricsWindow.
            refreshQuickControls(getString(R.string.ui_overlay_update_failed))
        }

        return START_STICKY
    }

    private fun handleCommand(intent: Intent?, startId: Int) {
        when (intent?.action) {
            null -> restoreFromDesiredState()
            BroadcastActions.SHOW -> showLyrics(feedback = null)
            BroadcastActions.HIDE -> {
                hideLyrics(feedback = null)
                stopSelf(startId)
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
                val applied = windowController.applyStyle()
                if (applied) renderer.refresh()
                refreshQuickControls(if (applied) null else getString(R.string.ui_overlay_update_failed))
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
    }

    private fun registerMediaReceiver() {
        val filter = IntentFilter().apply {
            addAction(BroadcastActions.MEDIA_UPDATE)
            addAction(BroadcastActions.MEDIA_SOURCE_LOST)
        }
        ContextCompat.registerReceiver(
            this,
            mediaReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun shouldObserveSelectedMedia(): Boolean {
        return ::windowController.isInitialized &&
            windowController.isVisible &&
            !selectedSourcePackage.isNullOrBlank()
    }

    private fun startSelectedMediaObservation() {
        if (!shouldObserveSelectedMedia()) {
            stopSelectedMediaObservation()
            return
        }

        refreshSelectedCurrentMediaInfo()
        scheduleSelectedCurrentMediaInfoRefresh()
    }

    private fun stopSelectedMediaObservation() {
        syncHandler.removeCallbacks(currentMediaRefreshRunnable)
    }

    private fun scheduleSelectedCurrentMediaInfoRefresh() {
        syncHandler.removeCallbacks(currentMediaRefreshRunnable)
        if (shouldObserveSelectedMedia()) {
            syncHandler.postDelayed(
                currentMediaRefreshRunnable,
                CURRENT_MEDIA_REFRESH_INTERVAL_MS
            )
        }
    }

    private fun refreshSelectedCurrentMediaInfo() {
        if (!shouldObserveSelectedMedia()) return

        readSelectedCurrentMediaInfo()?.let(::applyCurrentMediaInfo)
    }

    private fun applyCurrentMediaInfo(media: CurrentMediaInfo): Boolean {
        if (media.title.isBlank()) return false
        if (!shouldAcceptMediaUpdate(media.sourcePackage)) return false
        if (!mediaSnapshotGate.markAcceptedIfFresh(media)) return false

        currentMedia = media

        syncHandler.removeCallbacks(mediaRestoreRunnable)
        mediaRestoreAttempt = 0

        renderer.updatePlayback(
            positionMs = media.positionMs,
            isPlaying = media.isPlaying
        )
        renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media))

        val playbackLyricsKey = media.playbackLyricsKey()
        if (playbackLyricsKey == lastPlaybackLyricsKey) {
            renderer.tick()
            return true
        }

        val lookupRequestKey = media.lyricsLookupRequestKey()
        lastPlaybackLyricsKey = playbackLyricsKey
        activeLyricsLookupRequestKey = lookupRequestKey
        loadLyricsForSong(media = media, lookupRequestKey = lookupRequestKey)

        return true
    }

    private fun CurrentMediaInfo.playbackLyricsKey(): PlaybackLyricsKey {
        val songKey = toSongIdentity().storageKey()
        val normalizedAlbum = SongIdentity.normalizeText(album)
        return PlaybackLyricsKey("$sourcePackage|$songKey|$normalizedAlbum")
    }

    private fun CurrentMediaInfo.lyricsLookupRequestKey(nonce: String? = null): LyricsLookupRequestKey {
        val playbackKey = playbackLyricsKey()
        return LyricsLookupRequestKey(
            if (nonce == null) playbackKey.value else "${playbackKey.value}|$nonce"
        )
    }

    private fun scheduleCurrentMediaRestore() {
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        mediaRestoreAttempt = 0
        syncHandler.post(mediaRestoreRunnable)
    }

    private fun restoreCurrentMediaOrRetry() {
        if (!currentMedia.isEmpty) return
        if (!QuickFloatingStore.isDesiredVisible(this)) return
        if (!::windowController.isInitialized || !windowController.isVisible) return
        if (selectedSourcePackage.isNullOrBlank()) return

        val restored = readSelectedCurrentMediaInfo()
            ?.let(::applyCurrentMediaInfo)
            ?: false

        if (restored) return

        if (mediaRestoreAttempt == 0) {
            requestNotificationListenerRebind()
        }

        val delay = MEDIA_RESTORE_RETRY_DELAYS_MS
            .getOrNull(mediaRestoreAttempt++)
            ?: return

        syncHandler.postDelayed(mediaRestoreRunnable, delay)
    }

    private fun readSelectedCurrentMediaInfo(): CurrentMediaInfo? {
        return CurrentMediaReader.readSelectedCurrentMedia(
            context = this,
            selectedPackage = selectedSourcePackage
        )
    }

    private fun requestNotificationListenerRebind() {
        val component = ComponentName(
            this,
            MediaNotificationListenerService::class.java
        )

        runCatching {
            NotificationListenerService.requestRebind(component)
        }
    }

    private fun handleMediaSourceLost(sourcePackage: String) {
        if (sourcePackage.isBlank()) return
        if (sourcePackage != selectedSourcePackage) return
        if (currentMedia.isEmpty || currentMedia.sourcePackage != sourcePackage) return

        val pausedPosition = renderer.getEstimatedPositionMs()
        currentMedia = currentMedia.copy(
            isPlaying = false,
            positionMs = pausedPosition
        )
        renderer.updatePlayback(positionMs = pausedPosition, isPlaying = false)
        renderer.refresh()
    }

    private fun applyLyricsOffset(offsetMs: Long) {
        if (renderer.setLyricsOffset(offsetMs)) {
            renderer.refresh()
        }
    }

    private fun reloadCurrentLyrics(
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false
    ) {
        if (currentMedia.isEmpty) {
            clearLyricsState("♪ " + getString(R.string.ui_waiting_for_media) + "...")
            return
        }

        val playbackLyricsKey = currentMedia.playbackLyricsKey()
        val lookupRequestKey = currentMedia.lyricsLookupRequestKey(
            nonce = "reload|${SystemClock.uptimeMillis()}"
        )
        lastPlaybackLyricsKey = playbackLyricsKey
        activeLyricsLookupRequestKey = lookupRequestKey
        loadLyricsForSong(
            media = currentMedia,
            lookupRequestKey = lookupRequestKey,
            bypassLocal = bypassLocal,
            forceSaveOnline = forceSaveOnline,
            ignoreAutoSearchSetting = ignoreAutoSearchSetting
        )
    }

    private fun selectMediaSource(packageName: String?) {
        selectedSourcePackage = packageName
        MediaSourceStore.saveSelectedPackage(this, packageName)
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        mediaRestoreAttempt = 0

        clearLyricsState(
            if (packageName == null) {
                "♪ " + getString(R.string.ui_no_media_source_status)
            } else {
                "♪ " + getString(R.string.ui_media_source_waiting_status) + "..."
            }
        )

        if (packageName == null) {
            stopSelectedMediaObservation()
            return
        }

        if (::windowController.isInitialized && windowController.isVisible) {
            startSelectedMediaObservation()
            if (currentMedia.isEmpty) {
                scheduleCurrentMediaRestore()
            }
        }
    }

    private fun clearLyricsState(message: String) {
        lastPlaybackLyricsKey = null
        activeLyricsLookupRequestKey = null
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
        lookupRequestKey: LyricsLookupRequestKey,
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false
    ) {
        renderer.show(
            if (media.isPlaying) {
                "♪ ${getString(R.string.ui_searching_lyrics)}...\n${media.displayText}"
            } else {
                "Ⅱ ${getString(R.string.ui_paused)}\n${media.displayText}"
            }
        )

        lyricsLookupRunner.submit(
            requestKey = lookupRequestKey.value,
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
            callback = { completedLookupRequestKey, result ->
                if (activeLyricsLookupRequestKey == LyricsLookupRequestKey(completedLookupRequestKey)) {
                    activeLyricsLookupRequestKey = null
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
                emptyText = "♪ " + getString(R.string.ui_parsed_lyrics_are_empty) + "\n" + media.displayText
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
            "♪ ${getString(R.string.ui_using_local_lyrics_only)}\n${media.displayText}\n${getString(R.string.ui_local_file_not_found)}"
        } else {
            val sourceTitle = localizedLyricsSourceTitle(LyricsSettingsStore.getLyricsSearchSource(this))
            "♪ ${media.displayText}\n${getString(R.string.ui_source)}$sourceTitle\n${getString(R.string.ui_lyrics_not_found)}"
        }
    }

    private fun importLyrics(uri: Uri, overwrite: Boolean) {
        lyricsLookupRunner.cancelActive()
        activeLyricsLookupRequestKey = null
        val media = currentMedia

        if (media.title.isBlank()) {
            renderer.show("♪ " + getString(R.string.ui_no_song_for_lyrics_binding))
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
            renderer.show("♪ " + getString(R.string.ui_lyrics_import_failed))
            return
        }

        val localLyrics = LyricsStorage.readLocalLyrics(
            context = this,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        )

        if (localLyrics != null) {
            lastPlaybackLyricsKey = media.playbackLyricsKey()
            activeLyricsLookupRequestKey = null
            renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media))
            renderer.parseAndShow(
                lyrics = localLyrics,
                emptyText = "♪ " + getString(R.string.ui_lyrics_import_empty_error)
            )
        } else {
            renderer.show("♪ " + getString(R.string.ui_lyrics_import_failed))
        }
    }

    private fun restoreFromDesiredState() {
        selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
        if (QuickFloatingStore.isDesiredVisible(this)) {
            showLyrics(feedback = null, updateDesiredVisible = false)
        } else {
            stopSelectedMediaObservation()
            broadcastWindowVisibility(false)
            refreshQuickControls()
        }
    }

    private fun showLyrics(
        feedback: String? = null,
        updateDesiredVisible: Boolean = true
    ): Boolean {
        if (updateDesiredVisible) {
            QuickFloatingStore.setDesiredVisible(this, true)
        }
        val shown = runCatching { windowController.show() }.getOrElse {
            windowController.hide()
            false
        }
        if (!shown) {
            broadcastWindowVisibility(false)
        } else {
            startSelectedMediaObservation()
            if (currentMedia.isEmpty) {
                scheduleCurrentMediaRestore()
            }
        }
        refreshQuickControls(if (shown) feedback else null)
        return shown
    }

    private fun hideLyrics(feedback: String? = null) {
        QuickFloatingStore.setDesiredVisible(this, false)
        val hidden = if (::windowController.isInitialized) {
            runCatching { windowController.hide() }.getOrDefault(false)
        } else {
            true
        }
        val stillVisible = ::windowController.isInitialized && windowController.isVisible
        if (hidden || !stillVisible) {
            syncHandler.removeCallbacks(mediaRestoreRunnable)
            mediaRestoreAttempt = 0
            stopSelectedMediaObservation()
            broadcastWindowVisibility(false)
        }
        refreshQuickControls(feedback)
    }

    private fun setLocked(locked: Boolean, feedback: String? = null): Boolean {
        val updated = windowController.setLocked(locked)
        refreshQuickControls(if (updated) feedback else getString(R.string.ui_overlay_update_failed))
        return updated
    }

    private fun setClickThrough(clickThrough: Boolean, feedback: String? = null): Boolean {
        val updated = windowController.setClickThrough(clickThrough)
        refreshQuickControls(if (updated) feedback else getString(R.string.ui_overlay_update_failed))
        return updated
    }

    private fun toggleVisibleFromNotification() {
        val nextVisible = !windowController.isVisible
        if (nextVisible) {
            if (!showLyrics(feedback = getString(R.string.ui_shown))) {
                refreshQuickControls(getString(R.string.ui_overlay_permission_required))
                showQuickFeedback(getString(R.string.ui_enable_overlay_permission_first))
            }
        } else {
            hideLyrics(feedback = getString(R.string.ui_hidden))
        }
    }

    private fun toggleLockFromNotification() {
        val nextLocked = !FloatingLyricsStyleStore.isLocked(this)
        setLocked(locked = nextLocked, feedback = if (nextLocked) getString(R.string.ui_locked) else getString(R.string.ui_unlocked))
    }

    private fun toggleClickThroughFromNotification() {
        val nextClickThrough = !FloatingLyricsStyleStore.isClickThrough(this)
        setClickThrough(
            clickThrough = nextClickThrough,
            feedback = if (nextClickThrough) getString(R.string.ui_click_through_enabled_feedback) else getString(R.string.ui_touchable)
        )
    }

    private fun toggleAdjustModeFromNotification() {
        val currentlyEditing = !FloatingLyricsStyleStore.isLocked(this) &&
            !FloatingLyricsStyleStore.isClickThrough(this)
        val nextEditing = !currentlyEditing

        val lockedUpdated = windowController.setLocked(!nextEditing)
        val clickThroughUpdated = windowController.setClickThrough(!nextEditing)

        if (!lockedUpdated || !clickThroughUpdated) {
            refreshQuickControls(getString(R.string.ui_overlay_update_failed))
        } else if (nextEditing) {
            refreshQuickControls(getString(R.string.ui_draggable))
        } else {
            refreshQuickControls(getString(R.string.ui_locked_click_through))
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
        val actualVisible = ::windowController.isInitialized && windowController.isVisible
        val intent = Intent(BroadcastActions.QUICK_CONTROL_CHANGED).apply {
            setPackage(packageName)
            putExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, actualVisible)
            putExtra(BroadcastActions.EXTRA_LOCKED, FloatingLyricsStyleStore.isLocked(this@FloatingLyricsService))
            putExtra(BroadcastActions.EXTRA_CLICK_THROUGH, FloatingLyricsStyleStore.isClickThrough(this@FloatingLyricsService))
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        stopSelectedMediaObservation()
        lyricsLookupRunner.shutdown()
        runCatching { unregisterReceiver(mediaReceiver) }
        if (::windowController.isInitialized) {
            windowController.hide(notifyVisibilityChanged = false)
        }
        broadcastWindowVisibility(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @JvmInline
    private value class PlaybackLyricsKey(val value: String)

    @JvmInline
    private value class LyricsLookupRequestKey(val value: String)

    companion object {
        private const val CURRENT_MEDIA_REFRESH_INTERVAL_MS = 1_000L
        private val MEDIA_RESTORE_RETRY_DELAYS_MS = longArrayOf(
            250L,
            750L,
            2_000L,
            5_000L
        )
    }
}
