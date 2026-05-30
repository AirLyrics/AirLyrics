package com.andsi.airlyrics.lyrics

import android.content.Context

/** Compatibility wrapper for older call sites. Prefer LyricsRepository for new code. */
object LyricsFetcher {
    private val runner = LyricsLookupRunner(threadNamePrefix = "AirLyrics-LyricsFetch")

    fun fetchSyncedLyrics(
        context: Context,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        callback: (Result<String?>) -> Unit
    ): LyricsLookupHandle {
        return runner.submit(
            requestKey = "$title|$artist|$album|$durationMs",
            lookup = { token ->
                LyricsRepository.findLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    cancellationToken = token
                ).map { providerResult -> providerResult?.lyrics }
            },
            callback = { _, result -> callback(result) }
        )
    }

    fun cancelActive() {
        runner.cancelActive()
    }
}

