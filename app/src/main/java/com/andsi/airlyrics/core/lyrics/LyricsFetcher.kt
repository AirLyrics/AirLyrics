package com.andsi.airlyrics

import android.content.Context

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
            val result = runCatching {
                when (LyricsSettingsStore.getLyricsSource(context)) {
                    LyricsSettingsStore.SOURCE_NETEASE -> NeteaseLyricsProvider.fetchBestLyrics(
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs
                    ).getOrThrow()?.lrc

                    LyricsSettingsStore.SOURCE_LOCAL_ONLY -> null
                    else -> null
                }
            }

            callback(result)
        }, "AirLyrics-LyricsFetch").start()
    }
}
