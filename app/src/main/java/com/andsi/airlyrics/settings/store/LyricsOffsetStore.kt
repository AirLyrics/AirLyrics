package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.core.content.edit
import com.andsi.airlyrics.lyrics.model.SongIdentity
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import java.util.Locale

/**
 * Per-song lyric timing offset. The raw lyric files stay untouched; this store only
 * remembers how much the playback position should be shifted when rendering lyrics.
 */
object LyricsOffsetStore {
    private const val PREFS_NAME = "lyrics_offset_store"
    private const val KEY_PREFIX = "song_offset_ms_"
    private const val MAX_OFFSET_MS = 30_000L

    fun getOffsetMs(context: Context, media: CurrentMediaInfo): Long {
        if (media.title.isBlank()) return 0L
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val identity = media.toSongIdentity()
        val exactKey = offsetKey(identity)
        val weakKey = weakOffsetKey(identity)

        if (prefs.contains(exactKey)) {
            val offset = prefs.getLong(exactKey, 0L)
            if (!prefs.contains(weakKey)) {
                prefs.edit {
                    putLong(weakKey, offset)
                }
            }
            return offset
        }

        nearbyDurationKeys(identity).firstOrNull { prefs.contains(it) }?.let { nearbyKey ->
            val offset = prefs.getLong(nearbyKey, 0L)
            prefs.edit {
                putLong(exactKey, offset)
                putLong(weakKey, offset)
            }
            return offset
        }

        if (prefs.contains(weakKey)) {
            val offset = prefs.getLong(weakKey, 0L)
            prefs.edit {
                putLong(exactKey, offset)
            }
            return offset
        }

        return 0L
    }

    fun setOffsetMs(context: Context, media: CurrentMediaInfo, offsetMs: Long): Long {
        if (media.title.isBlank()) return 0L
        val safeOffset = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        val identity = media.toSongIdentity()
        val exactKey = offsetKey(identity)
        val weakKey = weakOffsetKey(identity)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(exactKey, safeOffset)
            putLong(weakKey, safeOffset)
        }
        return safeOffset
    }

    fun adjustOffsetMs(context: Context, media: CurrentMediaInfo, deltaMs: Long): Long {
        val current = getOffsetMs(context, media)
        return setOffsetMs(context, media, current + deltaMs)
    }

    fun resetOffset(context: Context, media: CurrentMediaInfo) {
        if (media.title.isBlank()) return
        val identity = media.toSongIdentity()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(offsetKey(identity))
            remove(weakOffsetKey(identity))
            nearbyDurationKeys(identity).forEach { remove(it) }
        }
    }

    fun formatOffset(offsetMs: Long): String {
        if (offsetMs == 0L) return "0.00s"
        val sign = if (offsetMs > 0L) "+" else "-"
        return "$sign${"%.2f".format(Locale.getDefault(), kotlin.math.abs(offsetMs) / 1000f)}s"
    }

    private fun nearbyDurationKeys(identity: SongIdentity): List<String> {
        if (identity.durationMs <= 0L) return emptyList()
        val durationSeconds = identity.durationSeconds
        return (-5L..5L)
            .asSequence()
            .filter { it != 0L }
            .map { durationSeconds + it }
            .filter { it > 0L }
            .map { offsetKeyByDurationSeconds(identity, it) }
            .toList()
    }

    private fun offsetKeyByDurationSeconds(identity: SongIdentity, durationSeconds: Long): String {
        return KEY_PREFIX + identity.storageKeyAtDurationSeconds(durationSeconds)
    }

    private fun offsetKey(identity: SongIdentity): String {
        return KEY_PREFIX + identity.storageKey()
    }

    private fun weakOffsetKey(identity: SongIdentity): String {
        return KEY_PREFIX + identity.storageKeyAtDurationSeconds(0L)
    }
}
