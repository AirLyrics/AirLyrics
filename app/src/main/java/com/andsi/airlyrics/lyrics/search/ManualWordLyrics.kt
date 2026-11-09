package com.andsi.airlyrics.lyrics.search

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.storage.WordByWordLyricsJsonCodec
import org.json.JSONObject

/** Word providers are scoped to manual candidate lookup, not automatic search sources. */
internal enum class ManualWordSource(val key: String) {
    NETEASE("netease"), KUGOU("kugou")
}

internal data class ManualWordVersion(
    val id: String,
    val source: ManualWordSource,
    val song: SongIdentity,
    val lyrics: LyricsProviderResult,
    val requiresConfirmation: Boolean = false,
) {
    val isWordTimed: Boolean get() = lyrics.wordByWordLines.isNotEmpty()
    val key: String get() = "${source.key}:$id:${if (isWordTimed) "word" else "line"}"
}

internal data class ManualWordVersions(
    val versions: List<ManualWordVersion>,
    val partialError: Throwable? = null,
)

internal fun parseManualWordVersions(
    response: JSONObject, source: ManualWordSource, candidate: LyricsCandidate? = null,
): ManualWordVersions {
    fun failure(type: LyricsLookupErrorType, cause: Throwable? = null) =
        LyricsLookupException(source.key, source.key, type, "Word lyrics request failed", cause)
    try {
        var rowError: Throwable? = null
        val array = response.getJSONArray("versions")
        val versions = (0 until array.length()).mapNotNull { index ->
            try {
            val item = array.getJSONObject(index)
            require(item.getString("provider") == source.key)
            val track = item.getJSONObject("track")
            val id = item.getString("id")
            if (source == ManualWordSource.NETEASE) {
                require(id.isNotBlank() && id.all(Char::isDigit) && id == track.getString("id"))
                if (candidate != null) require(id == candidate.id)
            }
            val song = SongIdentity(track.getString("title"), track.getString("artist"),
                track.getString("album"), track.getLong("duration_ms"))
            val lines = WordByWordLyricsJsonCodec.parseWordByWordLinesJson(item.getJSONArray("lines").toString())
            require((item.optString("kind") == "line" || lines.isNotEmpty()) && lines.all { line ->
                line.segments.isNotEmpty() && line.segments.all {
                    it.startMs >= line.startMs && it.endMs > it.startMs && it.endMs <= line.endMs
                }
            })
            val lyrics = LyricsProviderResult(source.key, source.key, item.getString("lrc"),
                item.optString("translated_lrc").takeIf { !item.isNull("translated_lrc") && it.isNotBlank() },
                lines, song.title, song.artist, song.album, song.durationMs)
            require(lyrics.plainLrc.isNotBlank() && lyrics.manualUnavailableReason() == null)
            ManualWordVersion(id, source, song, lyrics, item.optBoolean("requires_confirmation"))
            } catch (e: Exception) { rowError = failure(LyricsLookupErrorType.ParseError,e); null }
        }.distinctBy { it.key }
        val partial = response.optString("partial_error").takeIf { !response.isNull("partial_error") && it.isNotBlank() }
            ?.let { failure(LyricsLookupErrorType.fromNativeName(it)) }
        if (versions.isEmpty() && rowError != null) throw rowError
        return ManualWordVersions(versions, partial ?: rowError)
    } catch (e: org.json.JSONException) {
        throw failure(LyricsLookupErrorType.ParseError, e)
    } catch (e: IllegalArgumentException) {
        throw failure(LyricsLookupErrorType.ParseError, e)
    }
}

/** Stable provider identity is separate from the current playback target. */
internal data class ManualWordRecording(
    val source: ManualWordSource, val id: String, val song: SongIdentity,
    val score: Double = 0.0, val related: Boolean = true,
    val requiresConfirmation: Boolean = false,
) { val key: String get() = "${source.key}:$id" }

internal data class ManualWordPage(
    val recordings: List<ManualWordRecording>, val page: Int = 1,
    val hasMore: Boolean = false, val keyword: String = "", val partialError: Throwable? = null,
)

internal data class ManualWordOption(
    val recording: ManualWordRecording, val id: String, val song: SongIdentity,
    val accessKey: String, val creator: String = "", val rating: String = "",
    val requiresConfirmation: Boolean = false,
) { val key: String get() = "${recording.key}:$id" }

internal data class ManualWordDetails(
    val versions: List<ManualWordVersion> = emptyList(),
    val options: List<ManualWordOption> = emptyList(), val partialError: Throwable? = null,
    val plainTextOnly: Boolean = false,
)

internal fun JSONObject.wordSong() = SongIdentity(getString("title"),getString("artist"),
    getString("album"),getLong("duration_ms"))

internal fun parseWordPage(response: JSONObject, source: ManualWordSource): ManualWordPage {
    val rows=response.getJSONArray("recordings")
    val recordings=(0 until rows.length()).map { i ->
        val row=rows.getJSONObject(i); val t=row.getJSONObject("track")
        ManualWordRecording(source,t.getString("id"),t.wordSong(),t.optDouble("score"),
            row.optBoolean("related"),row.optBoolean("requires_confirmation"))
    }.distinctBy { it.key }
    return ManualWordPage(recordings,response.getInt("page"),response.getBoolean("has_more"),
        response.getString("keyword"),response.wordError(source))
}
internal fun JSONObject.wordError(source:ManualWordSource):Throwable? =
    optString("partial_error").takeIf { !isNull("partial_error") && it.isNotBlank() }?.let {
        LyricsLookupException(source.key,source.key,LyricsLookupErrorType.fromNativeName(it),"Lyrics request failed")
    }
internal fun parseWordDetails(response:JSONObject, recording:ManualWordRecording):ManualWordDetails {
    val versions=parseManualWordVersions(response,recording.source,
        if(recording.source==ManualWordSource.NETEASE)
            LyricsCandidate(com.andsi.airlyrics.core.model.PlainLyricsSearchSource.NETEASE,recording.id,recording.song,0.0)
        else null)
    val rows=response.optJSONArray("options")
    val options=(0 until (rows?.length() ?: 0)).map { i ->
        val row=rows!!.getJSONObject(i)
        ManualWordOption(recording,row.getString("id"),row.getJSONObject("track").wordSong(),
            row.getString("access_key"),row.optString("creator").takeUnless { row.isNull("creator") }.orEmpty(),
            row.optString("rating").takeUnless { row.isNull("rating") }.orEmpty(),
            row.optBoolean("requires_confirmation"))
    }.distinctBy { it.key }
    return ManualWordDetails(versions.versions,options,versions.partialError,
        response.optString("content_state")=="plain")
}
