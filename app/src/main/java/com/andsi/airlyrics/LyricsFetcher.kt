package com.andsi.airlyrics

object LyricsFetcher {
    fun fetchSyncedLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        callback: (Result<String?>) -> Unit
    ) {
        Thread {
            val result = NeteaseLyricsProvider.fetchBestLyrics(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs
            ).map { it?.lrc }

            callback(result)
        }.start()
    }
}
