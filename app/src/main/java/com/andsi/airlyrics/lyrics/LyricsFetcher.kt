package com.andsi.airlyrics.lyrics

import android.content.Context

/** Compatibility wrapper for older call sites. Prefer LyricsRepository for new code. */
object LyricsFetcher {
    fun fetchSyncedLyrics(
        context: Context,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        callback: (Result<String?>) -> Unit
    ) {
        Thread({
            val result = LyricsRepository.findLyrics(
                context = context,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs
            ).map { providerResult -> providerResult?.lyrics }

            callback(result)
        }, "AirLyrics-LyricsFetch").start()
    }
}
