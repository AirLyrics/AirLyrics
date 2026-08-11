package com.andsi.airlyrics.lyrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andsi.airlyrics.core.model.SongIdentity

internal enum class LyricsChangeKind {
    UPDATED,
    DELETED
}

internal data class LyricsChange(
    val target: SongIdentity?,
    val kind: LyricsChangeKind
) {
    init {
        require(target != null || kind == LyricsChangeKind.DELETED)
    }

    companion object {
        fun updated(target: SongIdentity): LyricsChange {
            return LyricsChange(target = target, kind = LyricsChangeKind.UPDATED)
        }

        fun deleted(target: SongIdentity? = null): LyricsChange {
            return LyricsChange(target = target, kind = LyricsChangeKind.DELETED)
        }
    }
}

/**
 * Stateless app-local protocol announcing that durable lyrics changed for one song or the library.
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
    private const val EXTRA_CHANGE_KIND = "changeKind"

    fun lyricsChangedFilter(): IntentFilter = IntentFilter(ACTION_LYRICS_CHANGED)

    fun lyricsChangedIntent(
        context: Context,
        target: SongIdentity,
        kind: LyricsChangeKind = LyricsChangeKind.UPDATED
    ): Intent? {
        return lyricsChangedIntent(context, LyricsChange(target = target, kind = kind))
    }

    fun lyricsChangedIntent(context: Context, change: LyricsChange): Intent? {
        val target = change.target
        if (target != null && (target.title.isBlank() || target.durationMs < 0L)) return null

        return Intent(ACTION_LYRICS_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CHANGE_KIND, change.kind.name)
            target?.let {
                putExtra(EXTRA_TITLE, it.title)
                putExtra(EXTRA_ARTIST, it.artist)
                putExtra(EXTRA_ALBUM, it.album)
                putExtra(EXTRA_DURATION_MS, it.durationMs)
            }
        }
    }

    fun readChange(intent: Intent?): LyricsChange? {
        if (intent?.action != ACTION_LYRICS_CHANGED) return null
        val kind = if (intent.hasExtra(EXTRA_CHANGE_KIND)) {
            intent.getStringExtra(EXTRA_CHANGE_KIND)?.let { rawKind ->
                runCatching { LyricsChangeKind.valueOf(rawKind) }.getOrNull()
            } ?: return null
        } else {
            LyricsChangeKind.UPDATED
        }
        val identityExtras = listOf(
            EXTRA_TITLE,
            EXTRA_ARTIST,
            EXTRA_ALBUM,
            EXTRA_DURATION_MS
        )
        val presentIdentityExtras = identityExtras.count(intent::hasExtra)
        if (presentIdentityExtras == 0) {
            return if (kind == LyricsChangeKind.DELETED) LyricsChange.deleted() else null
        }
        if (presentIdentityExtras != identityExtras.size) return null

        val target = runCatching {
            SongIdentity(
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, -1L)
            )
        }.getOrNull()?.takeIf { target ->
            target.title.isNotBlank() && target.durationMs >= 0L
        } ?: return null
        return LyricsChange(target = target, kind = kind)
    }

    fun readTarget(intent: Intent?): SongIdentity? = readChange(intent)?.target
}

internal fun interface LyricsChangedPublisher {
    fun publish(change: LyricsChange)

    fun publish(target: SongIdentity) {
        publish(LyricsChange.updated(target))
    }

    fun publishDeleted(target: SongIdentity? = null) {
        publish(LyricsChange.deleted(target))
    }
}

internal class BroadcastLyricsChangedPublisher(context: Context) : LyricsChangedPublisher {
    private val applicationContext = context.applicationContext

    override fun publish(change: LyricsChange) {
        LyricsChangedBroadcast.lyricsChangedIntent(applicationContext, change)?.let { intent ->
            applicationContext.sendBroadcast(intent)
        }
    }
}
