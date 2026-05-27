package com.andsi.airlyrics

/** Reads lyrics from the user-selected local lyrics directory or the app fallback directory. */
object LocalLyricsProvider : LyricsProvider {
    override val id: String = "local"
    override val name: String = "本地歌词"

    override fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?> {
        return runCatching {
            val lyrics = LyricsStorage.readLocalLyrics(
                context = request.context,
                title = request.title,
                artist = request.artist,
                duration = request.durationMs
            ) ?: return@runCatching null

            LyricsProviderResult(
                providerId = id,
                providerName = name,
                lyrics = lyrics,
                matchedTitle = request.title,
                matchedArtist = request.artist,
                matchedAlbum = request.album,
                matchedDurationMs = request.durationMs
            )
        }
    }
}
