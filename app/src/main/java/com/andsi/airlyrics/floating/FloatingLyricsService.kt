package com.andsi.airlyrics.floating

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.LyricsLookupRunner
import com.andsi.airlyrics.lyrics.LyricsChangedBroadcast
import com.andsi.airlyrics.media.CurrentMediaBroadcast
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

open class FloatingLyricsService : Service() {
    internal lateinit var windowController: FloatingLyricsWindow

    internal val lyricsView
        get() = if (::windowController.isInitialized) windowController.textView else null

    internal fun isWindowControllerReady(): Boolean = ::windowController.isInitialized

    internal val renderer = FloatingLyricsRenderer(
        textViewProvider = { lyricsView },
        contentModeProvider = { LyricsSettingsStore.getContentDisplayMode(this) },
        lineModeProvider = { LyricsSettingsStore.getLineDisplayMode(this) },
        switchAnimationModeProvider = { LyricsSettingsStore.getSwitchAnimationMode(this) },
        karaokeEnabledProvider = { LyricsSettingsStore.isKaraokeLyricsEnabled(this) },
        karaokeHighlightColorProvider = { FloatingLyricsStyleStore.getStyle(this).karaokeHighlightColor },
        noTranslationTextProvider = { getString(R.string.ui_no_translation_for_this_lyric) }
    )
    internal val syncHandler = Handler(Looper.getMainLooper())
    internal val lyricsLookupRunner: LyricsLookupRunner by lazy(LazyThreadSafetyMode.NONE) {
        createLyricsLookupRunner()
    }

    protected open fun createLyricsLookupRunner(): LyricsLookupRunner {
        return LyricsLookupRunner(threadNamePrefix = "AirLyrics-LyricsRepository")
    }

    internal var currentMedia: CurrentMediaInfo = CurrentMediaInfo.Empty
    internal var lastPlaybackLyricsKey: PlaybackLyricsKey? = null
    internal var activeLyricsLookupRequestKey: LyricsLookupRequestKey? = null
    internal var selectedSourcePackage: String? = null
    internal var autoHiddenForPause = false
    internal var pauseAutoHideSuppressedByUser = false
    internal var mediaRestoreAttempt = 0
    internal val mediaSnapshotGate = MediaSnapshotGate()

    internal val syncRunnable = object : Runnable {
        override fun run() {
            if (!shouldSyncLyrics()) return

            renderer.tick()
            if (shouldSyncLyrics()) {
                syncHandler.postDelayed(this, lyricsSyncIntervalMs())
            }
        }
    }

    internal val mediaRestoreRunnable = Runnable {
        restoreCurrentMediaOrRetry()
    }

    internal val pauseAutoHideRunnable = Runnable {
        applyScheduledAutoHideWhenPaused()
    }

    internal val currentMediaRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSelectedCurrentMediaInfo()
            if (shouldObserveSelectedMedia()) {
                syncHandler.postDelayed(this, CURRENT_MEDIA_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            CurrentMediaBroadcast.readMediaSourceLost(intent)?.let { sourcePackage ->
                handleMediaSourceLost(sourcePackage)
                return
            }

            CurrentMediaBroadcast.readMediaUpdate(intent)?.let(::applyCurrentMediaInfo)
        }
    }

    private val lyricsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LyricsChangedBroadcast.readTarget(intent)?.let(::handleLyricsChanged)
        }
    }

    override fun onCreate() {
        LanguageSettingsStore.applyAppLocale(this)
        super.onCreate()

        selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
        windowController = FloatingLyricsWindow(this) { visible ->
            if (!visible) {
                syncHandler.removeCallbacks(mediaRestoreRunnable)
                mediaRestoreAttempt = 0
                stopSelectedMediaObservation()
                stopLyricsSync()
            }
            broadcastWindowVisibility(visible)
        }

        startForeground(FloatingServiceNotification.NOTIFICATION_ID, FloatingServiceNotification.create(this, currentQuickControlState()))
        registerMediaReceiver()
        registerLyricsChangedReceiver()
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

    private fun registerMediaReceiver() {
        ContextCompat.registerReceiver(
            this,
            mediaReceiver,
            CurrentMediaBroadcast.mediaStatusFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerLyricsChangedReceiver() {
        ContextCompat.registerReceiver(
            this,
            lyricsChangedReceiver,
            LyricsChangedBroadcast.lyricsChangedFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        stopLyricsSync()
        syncHandler.removeCallbacks(pauseAutoHideRunnable)
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        stopSelectedMediaObservation()
        lyricsLookupRunner.shutdown()
        runCatching { unregisterReceiver(mediaReceiver) }
        runCatching { unregisterReceiver(lyricsChangedReceiver) }
        if (::windowController.isInitialized) {
            windowController.hide(notifyVisibilityChanged = false)
        }
        broadcastWindowVisibility(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val CURRENT_MEDIA_REFRESH_INTERVAL_MS = 1_000L
        internal val MEDIA_RESTORE_RETRY_DELAYS_MS = longArrayOf(
            250L,
            750L,
            2_000L,
            5_000L
        )
    }
}
