package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import java.security.MessageDigest
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
        val exactKey = offsetKey(media.title, media.artist, media.durationMs)
        val weakKey = offsetKey(media.title, media.artist, 0L)

        if (prefs.contains(exactKey)) {
            val offset = prefs.getLong(exactKey, 0L)
            if (!prefs.contains(weakKey)) {
                prefs.edit().putLong(weakKey, offset).apply()
            }
            return offset
        }

        nearbyDurationKeys(media).firstOrNull { prefs.contains(it) }?.let { nearbyKey ->
            val offset = prefs.getLong(nearbyKey, 0L)
            prefs.edit()
                .putLong(exactKey, offset)
                .putLong(weakKey, offset)
                .apply()
            return offset
        }

        if (prefs.contains(weakKey)) {
            val offset = prefs.getLong(weakKey, 0L)
            prefs.edit().putLong(exactKey, offset).apply()
            return offset
        }

        return 0L
    }

    fun setOffsetMs(context: Context, media: CurrentMediaInfo, offsetMs: Long): Long {
        if (media.title.isBlank()) return 0L
        val safeOffset = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        val exactKey = offsetKey(media.title, media.artist, media.durationMs)
        val weakKey = offsetKey(media.title, media.artist, 0L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(exactKey, safeOffset)
            .putLong(weakKey, safeOffset)
            .apply()
        return safeOffset
    }

    fun adjustOffsetMs(context: Context, media: CurrentMediaInfo, deltaMs: Long): Long {
        val current = getOffsetMs(context, media)
        return setOffsetMs(context, media, current + deltaMs)
    }

    fun resetOffset(context: Context, media: CurrentMediaInfo) {
        if (media.title.isBlank()) return
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.remove(offsetKey(media.title, media.artist, media.durationMs))
        editor.remove(offsetKey(media.title, media.artist, 0L))
        nearbyDurationKeys(media).forEach { editor.remove(it) }
        editor.apply()
    }

    fun formatOffset(offsetMs: Long): String {
        if (offsetMs == 0L) return "0.00s"
        val sign = if (offsetMs > 0L) "+" else "-"
        return "$sign${"%.2f".format(Locale.getDefault(), kotlin.math.abs(offsetMs) / 1000f)}s"
    }

    fun description(offsetMs: Long): String {
        return when {
            offsetMs > 0L -> "歌词提前 ${formatOffset(offsetMs).removePrefix("+")}"
            offsetMs < 0L -> "歌词延后 ${formatOffset(offsetMs).removePrefix("-")}"
            else -> "未偏移"
        }
    }


    private fun nearbyDurationKeys(media: CurrentMediaInfo): List<String> {
        if (media.durationMs <= 0L) return emptyList()
        val durationSeconds = media.durationMs / 1000L
        return (-5L..5L)
            .asSequence()
            .filter { it != 0L }
            .map { durationSeconds + it }
            .filter { it > 0L }
            .map { offsetKeyByDurationSeconds(media.title, media.artist, it) }
            .toList()
    }

    private fun offsetKeyByDurationSeconds(title: String, artist: String, durationSeconds: Long): String {
        val raw = "${normalize(title)}|${normalize(artist)}|$durationSeconds"
        return KEY_PREFIX + sha1(raw)
    }

    private fun offsetKey(title: String, artist: String, durationMs: Long): String {
        val durationSeconds = if (durationMs > 0L) durationMs / 1000L else 0L
        return offsetKeyByDurationSeconds(title, artist, durationSeconds)
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
    }

    private fun sha1(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
