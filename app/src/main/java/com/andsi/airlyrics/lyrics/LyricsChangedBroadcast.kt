package com.andsi.airlyrics.lyrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andsi.airlyrics.core.model.SongIdentity

/**
 * Stateless app-local protocol announcing that durable lyrics changed for a song.
 *
 * The broadcast is deliberately non-sticky. Receivers that start after an event
 * catch up by reading durable storage during their normal initial render.
 */
internal object LyricsChangedBroadcast {
    private const val ACTION_LYRICS_CHANGED = "com.andsi.airlyrics.LYRICS_CHANGED"

    private const val EXTRA_TITLE = "title"
    private const val EXTRA_ARTIST = "artist"
    private const val EXTRA_ALBUM = "album"
    private const val EXTRA_DURATION_MS = "duration"

    fun lyricsChangedFilter(): IntentFilter = IntentFilter(ACTION_LYRICS_CHANGED)

    fun lyricsChangedIntent(context: Context, target: SongIdentity): Intent? {
        if (target.title.isBlank() || target.durationMs < 0L) return null

        return Intent(ACTION_LYRICS_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TITLE, target.title)
            putExtra(EXTRA_ARTIST, target.artist)
            putExtra(EXTRA_ALBUM, target.album)
            putExtra(EXTRA_DURATION_MS, target.durationMs)
        }
    }

    fun readTarget(intent: Intent?): SongIdentity? {
        if (intent?.action != ACTION_LYRICS_CHANGED) return null
        if (!intent.hasExtra(EXTRA_TITLE) ||
            !intent.hasExtra(EXTRA_ARTIST) ||
            !intent.hasExtra(EXTRA_ALBUM) ||
            !intent.hasExtra(EXTRA_DURATION_MS)
        ) {
            return null
        }

        return runCatching {
            SongIdentity(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, -1L)
            )
        }.getOrNull()?.takeIf { target ->
            target.title.isNotBlank() && target.durationMs >= 0L
        }
    }
}

internal fun interface LyricsChangedPublisher {
    fun publish(target: SongIdentity)
}

internal class BroadcastLyricsChangedPublisher(context: Context) : LyricsChangedPublisher {
    private val applicationContext = context.applicationContext

    override fun publish(target: SongIdentity) {
        LyricsChangedBroadcast.lyricsChangedIntent(applicationContext, target)?.let { intent ->
            applicationContext.sendBroadcast(intent)
        }
    }
}
