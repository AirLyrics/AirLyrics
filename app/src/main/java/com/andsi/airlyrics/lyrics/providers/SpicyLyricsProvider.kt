package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.storage.SpicyLyricsCache
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object SpicyLyricsProvider : LyricsProvider {
    override val id = "spicy"
    override val name = "Spicy Lyrics"
    override val supportsWordTiming = true
    override val continueOnFailure = true
    val isConfigured: Boolean get() = BuildConfig.SPICY_CLIENT_KEY.startsWith("sl_pk_")
    private val fetchLock = ReentrantLock()
    private val misses = LinkedHashMap<String, Long>()

    override fun cached(request: PlainLyricsSearchRequest): LyricsProviderResult? = SpicyLyricsCache.forContext(request.context)
        .read(SpicyLyricsCache.key(request.title, request.artist, request.durationMs))

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> = runCatching {
        while (!fetchLock.tryLock(100, TimeUnit.MILLISECONDS)) request.cancellationToken?.throwIfCancellationRequested()
        try {
            request.cancellationToken?.throwIfCancellationRequested()
            val song = request.originalSong ?: SongIdentity(request.title, request.artist, request.album, request.durationMs)
            val key = SpicyLyricsCache.key(song.title, song.artist, song.durationMs)
            val searchKey = if (request.searchMode == LyricsSearchMode.International) {
                "international:" + org.json.JSONArray(listOf(request.title, request.artist, request.album, request.durationMs)).toString()
            } else key
            SpicyLyricsCache.forContext(request.context).readForSearch(key, searchKey)?.let { return@runCatching it }
            if (!isConfigured) throw LyricsLookupException(id, name, LyricsLookupErrorType.NeedCredential, "Spicy client key not configured")
            if ((misses[searchKey] ?: 0L) > System.currentTimeMillis()) return@runCatching null
            val raw = withNativeLyricsCancellation(request.cancellationToken) { lookup ->
                SpicyLyricsNative.fetchLyricsForSearchJson(request.title, request.artist, request.album, request.durationMs, BuildConfig.SPICY_CLIENT_KEY, lookup, request.searchMode == LyricsSearchMode.International)
            }
            request.cancellationToken?.throwIfCancellationRequested()
            if (BuildConfig.DEBUG) {
                val summary = org.json.JSONObject(raw)
                val stage = summary.optString("stage").takeIf { it == "spotify_match" || it == "lyrics_fetch" } ?: "response"
                val lookup = summary.optJSONObject("lookup")
                android.util.Log.d("AirLyricsLyrics", "source=$id stage=$stage ok=${summary.optBoolean("ok")} candidates=${lookup?.optInt("candidates") ?: summary.optInt("candidates")} attempted=${lookup?.optInt("attempted") ?: 0}")
            }
            val fetchedAt = System.currentTimeMillis()
            val result = SpicyLyricsParser.parse(raw, key, fetchedAt)?.copy(
                pendingSpicyLyrics = PendingSpicyLyrics(raw, searchKey, fetchedAt)
            )
            if (result == null) {
                if (misses.size >= 128) misses.remove(misses.keys.first())
                misses[searchKey] = System.currentTimeMillis() + 5 * 60_000
            }
            result
        } finally { fetchLock.unlock() }
    }.recoverCatching { error ->
        request.cancellationToken?.throwIfCancellationRequested()
        when (error) {
            is java.util.concurrent.CancellationException -> throw error
            is LyricsLookupException -> throw error
            else -> throw LyricsLookupException(id, name, if (error is LinkageError) LyricsLookupErrorType.NativeError else LyricsLookupErrorType.ParseError, "Spicy Lyrics lookup failed")
        }
    }
}
