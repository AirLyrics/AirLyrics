package com.andsi.airlyrics.lyrics.search

import android.content.Context
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.providers.*
import com.andsi.airlyrics.lyrics.storage.*
import org.json.JSONObject
import org.json.JSONException

internal data class LyricsCandidate(val source: PlainLyricsSearchSource, val id: String,
    val song: SongIdentity, val score: Double, val storefront: String? = null)

/** Network reads stay separate from the explicit, transactional user selection. */
internal class ManualLyricsSearch(
    private val context: Context,
    private val nativeCall: (String, LyricsLookupCancellationToken) -> String = { request, token ->
        if (JSONObject(request).getString("provider") == PlainLyricsSearchSource.APPLE.key) {
            AppleManualLyricsRequest.execute(context, request, token)
        } else {
            withNativeLyricsCancellation(token) { lookup ->
                ManualLyricsNative.requestJson(request, BuildConfig.SPICY_CLIENT_KEY, lookup)
            }
        }
    }
) {
    fun search(source: PlainLyricsSearchSource, query: SongIdentity, token: LyricsLookupCancellationToken): List<LyricsCandidate> {
        val json = request(source, "search", "", query, token)
        val array = json.getJSONArray("candidates")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LyricsCandidate(source, item.getString("id"), SongIdentity(item.getString("title"),
                item.getString("artist"), item.getString("album"), item.optLong("duration_ms")), item.optDouble("score", 0.0), item.optString("storefront").takeIf { it.isNotBlank() })
        }.distinctBy { it.id }
    }

    fun fetch(candidate: LyricsCandidate, target: SongIdentity, token: LyricsLookupCancellationToken): ManualLyricsFetchResult {
        try {
            val response = request(candidate.source, "fetch", candidate.id, candidate.song, token, candidate.storefront)
            if (response.optString("content_state") == "empty") {
                val reason = LyricsUnavailableReason.fromWire(response.optString("reason"))
                return if (reason == LyricsUnavailableReason.SOURCE_EMPTY || reason == LyricsUnavailableReason.INSTRUMENTAL)
                    emptyLyrics(candidate) else ManualLyricsFetchResult.Unavailable(reason)
            }
            val raw = response.toString()
            if (candidate.source == PlainLyricsSearchSource.SPICY) {
                val body = response.getJSONObject("response").getJSONObject("Body")
                require(body.getString("id") == candidate.id) { "Candidate identity mismatch" }
                val type = body.getString("Type")
                if (type !in setOf("Syllable", "Line")) {
                    return ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.UNSUPPORTED_FORMAT)
                }
                val content = body.getJSONArray("Content")
                if (content.length() == 0) return emptyLyrics(candidate)
                for (index in 0 until content.length()) content.getJSONObject(index).getString("Type")
                if ((0 until content.length()).all { content.getJSONObject(it).getString("Type") == "Instrumental" })
                    return emptyLyrics(candidate)
                val fetchedAt = System.currentTimeMillis()
                val key = SpicyLyricsCache.key(target.title, target.artist, target.durationMs)
                val lyrics = SpicyLyricsParser.parse(raw, key, fetchedAt)?.copy(
                    pendingSpicyLyrics = PendingSpicyLyrics(raw, "manual:${candidate.id}", fetchedAt)
                )
                val reason = lyrics?.manualUnavailableReason()
                return if (lyrics != null && reason == null) ManualLyricsFetchResult.Available(lyrics)
                else ManualLyricsFetchResult.Unavailable(reason ?: LyricsUnavailableReason.NO_USABLE_LINES)
            }
            val t = candidate.song
            if (candidate.source == PlainLyricsSearchSource.APPLE) {
                require(response.getString("id") == candidate.id) { "Candidate identity mismatch" }
                val lyrics = ApplePlainLyricsProvider.parse(raw, t.title, t.artist, t.album, t.durationMs)
                val reason = lyrics?.manualUnavailableReason()
                return if (lyrics != null && reason == null &&
                    (lyrics.plainLrc.isNotBlank() || !lyrics.translatedLrc.isNullOrBlank()))
                    ManualLyricsFetchResult.Available(lyrics)
                else ManualLyricsFetchResult.Unavailable(reason ?: LyricsUnavailableReason.NO_USABLE_LINES)
            }
            val parsed = NativePlainLyricsResultParser.parse(raw, candidate.source.key, t.title,t.artist,t.album,t.durationMs)
            require(parsed.id == candidate.id) { "Candidate identity mismatch" }
            val lrc = parsed.primaryPlainLrc()
            val tracks = if (candidate.source == PlainLyricsSearchSource.LRCLIB && parsed.translatedLrc == null)
                splitLrclibEmbeddedTranslations(lrc) else null
            val lyrics = LyricsProviderResult(candidate.source.key, candidate.source.key,
                tracks?.originalLrc ?: lrc, tracks?.translatedLrc ?: parsed.translatedLrc,
                matchedTitle = t.title, matchedArtist = t.artist, matchedAlbum = t.album, matchedDurationMs = t.durationMs)
            val reason = lyrics.manualUnavailableReason()
            return if (reason == null) ManualLyricsFetchResult.Available(lyrics)
            else ManualLyricsFetchResult.Unavailable(reason)
        } catch (error: JSONException) {
            throw LyricsLookupException(candidate.source.key,candidate.source.key,LyricsLookupErrorType.ParseError,"Invalid lyrics response",error)
        } catch (error: IllegalArgumentException) {
            throw LyricsLookupException(candidate.source.key,candidate.source.key,LyricsLookupErrorType.ParseError,"Invalid lyrics content",error)
        }
    }

    private fun emptyLyrics(candidate: LyricsCandidate) = ManualLyricsFetchResult.Available(
        LyricsProviderResult(candidate.source.key, candidate.source.key, "",
            matchedTitle = candidate.song.title, matchedArtist = candidate.song.artist,
            matchedAlbum = candidate.song.album, matchedDurationMs = candidate.song.durationMs)
    )

    fun apply(target: SongIdentity, result: LyricsProviderResult, token: LyricsLookupCancellationToken,
        approvedRevision: String?): Boolean {
        require(result.manualUnavailableReason() == null) { "Invalid lyrics cannot be applied" }
        require(result.access?.isExpired() != true) { "Preview expired" }
        val lease = LyricsSearchCoordinator.begin(target,token,LyricsSearchOrigin.Manual,LyricsSearchMode.Original)
        return lease.use {
            lease.commit {
                LyricsStorage.withStorageLock {
                    val revision = protectedRevision(target)
                    if (revision != null && revision != approvedRevision) return@withStorageLock false
                    ManualLyricsSelectionStore.commit(context,target,LyricsSearchMode.Original,result,manual = true,savePlain = true)
                    true
                }
            }.also { saved -> if (saved) BroadcastLyricsChangedPublisher(context).publish(target) }
        }
    }

    fun protectedRevision(target: SongIdentity): String? {
        val info = LyricsStorage.withStorageLock {
            PlainLyricsStorageOps.getLocalPlainLyricsInfo(context,target.title,target.artist,target.durationMs)
        } ?: return null
        return if (info.plainSource in setOf(LyricsStorage.SOURCE_MANUAL_IMPORT,LyricsStorage.SOURCE_MANUAL_EDIT,LyricsStorage.SOURCE_LEGACY,LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK))
            "${info.indexKey}:${info.updatedAt}:${info.plainSource}" else null
    }

    private fun request(source: PlainLyricsSearchSource, operation: String, id: String, song: SongIdentity,
        token: LyricsLookupCancellationToken, storefront: String? = null): JSONObject {
        val request = JSONObject().put("provider",source.key).put("operation",operation)
            .put("language",context.resources.configuration.locales[0]?.language.orEmpty())
            .put("track",JSONObject().put("id",id).put("title",song.title).put("artist",song.artist)
                .put("album",song.album).put("duration_ms",song.durationMs))
        storefront?.let { request.put("storefront", it) }
        token.throwIfCancellationRequested()
        val raw = nativeCall(request.toString(), token)
        token.throwIfCancellationRequested()
        return JSONObject(raw).also {
            if (!it.optBoolean("ok") && operation == "fetch" && it.optString("error_type") == "NotFound") {
                it.put("ok", true).put("content_state", "empty").put("reason", "unknown")
            }
            if (!it.optBoolean("ok")) throw LyricsLookupException(source.key,source.key,
                LyricsLookupErrorType.fromNativeName(it.optString("error_type")),"Lyrics request failed")
        }
    }
}
