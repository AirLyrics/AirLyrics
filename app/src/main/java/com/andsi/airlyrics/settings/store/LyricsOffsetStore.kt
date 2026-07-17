package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.prefs.prefs

/**
 * Per-song lyric timing offset. The raw lyric files stay untouched; this store only
 * remembers how much the playback position should be shifted when rendering lyrics.
 */
object LyricsOffsetStore {
    private const val PREFS_NAME = "lyrics_offset_store"
    private const val KEY_PREFIX = "song_offset_ms_"
    private const val MAX_OFFSET_MS = 30_000L

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getOffsetMs(context: Context, identity: SongIdentity): Long {
        if (identity.title.isBlank()) return 0L
        val preferences = store(context)
        val exactKey = offsetKey(identity)
        val weakKey = weakOffsetKey(identity)

        if (preferences.contains(exactKey)) {
            val offset = preferences.getLong(exactKey, 0L)
            if (!preferences.contains(weakKey)) {
                preferences.setLong(weakKey, offset)
            }
            return offset
        }

        nearbyDurationKeys(identity).firstOrNull { preferences.contains(it) }?.let { nearbyKey ->
            val offset = preferences.getLong(nearbyKey, 0L)
            preferences.edit {
                putLong(exactKey, offset)
                putLong(weakKey, offset)
            }
            return offset
        }

        if (preferences.contains(weakKey)) {
            val offset = preferences.getLong(weakKey, 0L)
            preferences.setLong(exactKey, offset)
            return offset
        }

        return 0L
    }

    fun setOffsetMs(context: Context, identity: SongIdentity, offsetMs: Long): Long {
        if (identity.title.isBlank()) return 0L
        val safeOffset = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        val exactKey = offsetKey(identity)
        val weakKey = weakOffsetKey(identity)
        store(context).edit {
            putLong(exactKey, safeOffset)
            putLong(weakKey, safeOffset)
        }
        return safeOffset
    }

    fun adjustOffsetMs(context: Context, identity: SongIdentity, deltaMs: Long): Long {
        val current = getOffsetMs(context, identity)
        return setOffsetMs(context, identity, current + deltaMs)
    }

    fun resetOffset(context: Context, identity: SongIdentity) {
        if (identity.title.isBlank()) return
        store(context).edit {
            remove(offsetKey(identity))
            remove(weakOffsetKey(identity))
            nearbyDurationKeys(identity).forEach { remove(it) }
        }
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
