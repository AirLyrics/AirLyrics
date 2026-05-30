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
        if (prefs.contains(exactKey)) return prefs.getLong(exactKey, 0L)

        val weakKey = offsetKey(media.title, media.artist, 0L)
        return prefs.getLong(weakKey, 0L)
    }

    fun setOffsetMs(context: Context, media: CurrentMediaInfo, offsetMs: Long): Long {
        if (media.title.isBlank()) return 0L
        val safeOffset = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        val key = offsetKey(media.title, media.artist, media.durationMs)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, safeOffset)
            .apply()
        return safeOffset
    }

    fun adjustOffsetMs(context: Context, media: CurrentMediaInfo, deltaMs: Long): Long {
        val current = getOffsetMs(context, media)
        return setOffsetMs(context, media, current + deltaMs)
    }

    fun resetOffset(context: Context, media: CurrentMediaInfo) {
        if (media.title.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(offsetKey(media.title, media.artist, media.durationMs))
            .remove(offsetKey(media.title, media.artist, 0L))
            .apply()
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

    private fun offsetKey(title: String, artist: String, durationMs: Long): String {
        val durationSeconds = if (durationMs > 0L) durationMs / 1000L else 0L
        val raw = "${normalize(title)}|${normalize(artist)}|$durationSeconds"
        return KEY_PREFIX + sha1(raw)
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
    }

    private fun sha1(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
