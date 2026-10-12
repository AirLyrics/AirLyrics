package com.andsi.airlyrics.lyrics.providers

import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import java.util.concurrent.CancellationException
import org.json.JSONObject

internal object InternationalTitleNative {
    init { LyricsNativeLibrary.ensureLoaded() }
    external fun resolveJson(title: String, artist: String, album: String, durationMs: Long, lookupId: Long): String
}

/** Transforms only lookup metadata; storage always keeps the player's song identity. */
internal object InternationalTitleResolver {
    fun resolve(request: PlainLyricsSearchRequest): PlainLyricsSearchRequest {
        request.cancellationToken?.throwIfCancellationRequested()
        return try {
            val raw = withNativeLyricsCancellation(request.cancellationToken) { lookup ->
                InternationalTitleNative.resolveJson(request.title, request.artist, request.album, request.durationMs, lookup)
            }
            request.cancellationToken?.throwIfCancellationRequested()
            val result = JSONObject(raw)
            val title = result.optString("title").trim()
            if (title.isEmpty()) {
                log("fallback=${result.optString("reason", "no_title")}")
                request
            } else {
                val artist = result.optString("artist").takeIf { it.isNotBlank() && it != "null" } ?: request.artist
                val album = result.optString("album").takeIf { it != "null" }.orEmpty()
                log("method=${result.optString("method")} appleId=${result.optLong("appleId")} title=$title artist=$artist")
                request.copy(title = title, artist = artist, album = album,
                    searchMode = LyricsSearchMode.International,
                    originalSong = SongIdentity(request.title, request.artist, request.album, request.durationMs))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
            request.cancellationToken?.throwIfCancellationRequested()
            log("fallback=native_unavailable")
            request
        } catch (error: Exception) {
            request.cancellationToken?.throwIfCancellationRequested()
            log("fallback=${error.javaClass.simpleName}")
            request
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d("AirLyricsLyrics", "stage=international_title ${message.replace(Regex("[\\r\\n\\t]+"), " ").take(500)}")
    }
}
