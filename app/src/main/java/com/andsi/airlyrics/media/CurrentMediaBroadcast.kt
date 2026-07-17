package com.andsi.airlyrics.media

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andsi.airlyrics.media.model.CurrentMediaInfo

/**
 * Owns the app-local broadcast protocol for media snapshots.
 *
 * Keep media payload field names here so the notification listener, floating service,
 * and main screen cannot drift when CurrentMediaInfo changes.
 */
object CurrentMediaBroadcast {
    private const val ACTION_MEDIA_UPDATE = "com.andsi.airlyrics.MEDIA_UPDATE"
    private const val ACTION_MEDIA_SOURCE_LOST = "com.andsi.airlyrics.MEDIA_SOURCE_LOST"

    private const val EXTRA_TITLE = "title"
    private const val EXTRA_ARTIST = "artist"
    private const val EXTRA_ALBUM = "album"
    private const val EXTRA_DURATION_MS = "duration"
    private const val EXTRA_POSITION_MS = "position"
    private const val EXTRA_IS_PLAYING = "isPlaying"
    private const val EXTRA_SNAPSHOT_SEQUENCE = "mediaSnapshotSequence"
    private const val EXTRA_SOURCE_PACKAGE = "sourcePackage"

    fun mediaStatusFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(ACTION_MEDIA_UPDATE)
            addAction(ACTION_MEDIA_SOURCE_LOST)
        }
    }

    fun isMediaStatusIntent(intent: Intent): Boolean {
        return intent.action == ACTION_MEDIA_UPDATE ||
            intent.action == ACTION_MEDIA_SOURCE_LOST
    }

    fun mediaUpdateIntent(context: Context, media: CurrentMediaInfo): Intent {
        return Intent(ACTION_MEDIA_UPDATE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TITLE, media.title)
            putExtra(EXTRA_ARTIST, media.artist)
            putExtra(EXTRA_ALBUM, media.album)
            putExtra(EXTRA_DURATION_MS, media.durationMs)
            putExtra(EXTRA_POSITION_MS, media.positionMs)
            putExtra(EXTRA_IS_PLAYING, media.isPlaying)
            putExtra(EXTRA_SNAPSHOT_SEQUENCE, media.snapshotSequence)
            putExtra(EXTRA_SOURCE_PACKAGE, media.sourcePackage)
        }
    }

    fun mediaSourceLostIntent(context: Context, sourcePackage: String): Intent? {
        if (sourcePackage.isBlank()) return null

        return Intent(ACTION_MEDIA_SOURCE_LOST).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
    }

    fun readMediaUpdate(intent: Intent?): CurrentMediaInfo? {
        if (intent?.action != ACTION_MEDIA_UPDATE) return null

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (title.isBlank()) return null

        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        if (sourcePackage.isBlank()) return null

        return CurrentMediaInfo(
            sourcePackage = sourcePackage,
            title = title,
            artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
            album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
            durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L),
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false),
            positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L),
            snapshotSequence = intent.getLongExtra(
                EXTRA_SNAPSHOT_SEQUENCE,
                CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE
            )
        )
    }

    fun readMediaSourceLost(intent: Intent?): String? {
        if (intent?.action != ACTION_MEDIA_SOURCE_LOST) return null
        return intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
            ?.takeIf { it.isNotBlank() }
    }
}
