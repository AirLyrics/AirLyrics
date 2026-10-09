package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.core.model.LyricsAttribution
import com.andsi.airlyrics.core.model.LyricsCredit
import com.andsi.airlyrics.lyrics.*
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** Parses one response into a single coherent line/word payload; never merges other sources. */
internal object SpicyLyricsParser {
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    fun parse(raw: String, cacheKey: String, fetchedAt: Long): LyricsProviderResult? {
        val root = JSONObject(raw)
        if (!root.optBoolean("ok")) {
            val type = LyricsLookupErrorType.fromNativeName(root.optString("error_type"))
            if (type == LyricsLookupErrorType.NotFound) return null
            throw LyricsLookupException("spicy", "Spicy Lyrics", type, "Spicy Lyrics lookup failed")
        }
        val track = root.getJSONObject("track")
        val response = root.getJSONObject("response")
        val body = response.getJSONObject("Body")
        require(response.getInt("Status") == 200)
        require(body.getString("id") == track.getString("id"))
        val type = body.getString("Type")
        if (type !in setOf("Syllable", "Line")) return null
        val lines = mutableListOf<WordByWordLine>()
        val plain = mutableListOf<Pair<Long, String>>()
        val translated = mutableListOf<Pair<Long, String>>()
        val duration = track.optLong("duration_ms")
        val content = body.getJSONArray("Content")
        for (i in 0 until content.length()) {
            val line = content.getJSONObject(i)
            if (line.optString("Type") != "Vocal") continue
            if (type == "Syllable") {
                val voices = mutableListOf(line.getJSONObject("Lead"))
                line.optJSONArray("Background")?.let { bg -> for (j in 0 until bg.length()) voices += bg.getJSONObject(j) }
                voices.forEachIndexed { voiceIndex, voice ->
                    val start = time(voice, "StartTime")
                    val end = time(voice, "EndTime")
                    require(end > start && (duration <= 0 || end <= duration + 2000))
                    val syllables = voice.getJSONArray("Syllables")
                    val segments = mutableListOf<WordByWordSegment>()
                    for (j in 0 until syllables.length()) {
                        val s = syllables.getJSONObject(j)
                        val a = time(s, "StartTime")
                        val b = time(s, "EndTime")
                        val text = s.getString("Text")
                        require(text.isNotBlank() && b > a && a >= start - 50 && b <= end + 50)
                        require(segments.lastOrNull()?.startMs?.let { a >= it } != false)
                        // IsPartOfWord describes whether this syllable continues into the next.
                        val suffix = if (j < syllables.length() - 1 && !s.optBoolean("IsPartOfWord")) " " else ""
                        segments += WordByWordSegment(text + suffix, a, b)
                    }
                    require(segments.isNotEmpty())
                    val text = segments.joinToString("") { it.text }
                    lines += WordByWordLine(start, end, text, segments, isBackground = voiceIndex > 0)
                    if (voiceIndex == 0) plain += start to text
                    if (voiceIndex == 0) voice.optString("TranslatedText").takeIf { it.isNotBlank() }?.let { translated += start to it }
                }
            } else {
                val start = time(line, "StartTime")
                val end = time(line, "EndTime")
                require(end > start && (duration <= 0 || end <= duration + 2000))
                line.getString("Text").takeIf { it.isNotBlank() }?.let { plain += start to it }
                line.optString("TranslatedText").takeIf { it.isNotBlank() }?.let { translated += start to it }
            }
        }
        if (plain.isEmpty()) return null
        val provider = when (body.optString("source")) {
            "spicy_lyrics" -> "Spicy Lyrics"
            "apple_music" -> "Apple Music"
            "spotify" -> "Spotify"
            else -> "Unknown"
        }
        val credits = if (body.optString("source") == "spicy_lyrics") {
            val attribution = body.optJSONObject("UploadAttribution")
            listOf("Uploader", "Maker").mapNotNull { role ->
                attribution?.optJSONObject(role)?.let { person ->
                    val name = person.optString("username")
                    val url = person.optString("url").takeIf { it.startsWith("https://") }
                    name.takeIf { it.isNotBlank() }?.let { LyricsCredit(it, url) }
                }
            }.distinct()
        } else emptyList()
        return LyricsProviderResult("spicy", "Spicy Lyrics", lrc(plain),
            translated.takeIf { it.isNotEmpty() }?.let(::lrc), lines.sortedBy { it.startMs },
            track.optString("title"), track.optString("artist"), track.optString("album"), duration,
            LyricsAccess(fetchedAt, fetchedAt + RETENTION_MS, cacheKey, LyricsAttribution(provider, credits)))
    }

    private fun time(json: JSONObject, key: String): Long {
        val value = json.getDouble(key)
        require(value.isFinite() && value >= 0 && value < 24 * 60 * 60)
        return (value * 1000).roundToLong()
    }
    internal fun lrc(lines: List<Pair<Long, String>>): String = lines.sortedBy { it.first }.joinToString("\n") { (ms, text) ->
        String.format(Locale.ROOT, "[%02d:%02d.%03d]%s", ms / 60000, ms / 1000 % 60, ms % 1000, text.replace('\n', ' '))
    }
}
