package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.apple.AppleMusicSessionStore
import com.andsi.airlyrics.apple.AppleMusicWebClient
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.parser.LrcParser

import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Apple Music lyrics: line-level LRC plus Apple's own word timings and subtitle translations.
 *
 * Requires the account tokens captured on the Apple Music account page. The catalogue lookup runs
 * in the Rust core so it shares the same matching policy as the other sources.
 */
object ApplePlainLyricsProvider : LyricsProvider {
    override val id = "apple"
    override val name = "Apple Music"
    override val supportsWordTiming = true
    override val continueOnFailure = true

    private const val CACHE_LIMIT = 32
    private val cache = object : LinkedHashMap<String, LyricsProviderResult?>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsProviderResult?>) =
            size > CACHE_LIMIT
    }

    override fun cached(request: PlainLyricsSearchRequest): LyricsProviderResult? =
        cache[key(request.title, request.artist, request.durationMs)]

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> = runCatching {
        request.cancellationToken?.throwIfCancellationRequested()
        val session = AppleMusicSessionStore.load(request.context)
            ?: throw LyricsLookupException(id, name, LyricsLookupErrorType.NeedCredential, "Apple Music is not signed in")
        val developerToken = runBlocking {
            AppleMusicWebClient.developerToken(request.context).getOrElse {
                throw LyricsLookupException(
                    id, name, LyricsLookupErrorType.NetworkError, it.message.orEmpty(), it
                )
            }
        }
        val jsonText = withNativeLyricsCancellation(token = request.cancellationToken) { lookupId ->
            AppleLyricsNative.fetchLyricsJson(
                title = request.title,
                artist = request.artist,
                album = request.album,
                durationMs = request.durationMs,
                mediaUserToken = session.mediaUserToken,
                developerToken = developerToken,
                storefront = session.storefront,
                lookupId = lookupId
            )
        }
        request.cancellationToken?.throwIfCancellationRequested()
        val result = parse(
            jsonText = jsonText,
            fallbackTitle = request.title,
            fallbackArtist = request.artist,
            fallbackAlbum = request.album,
            fallbackDurationMs = request.durationMs
        )
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "AirLyricsLyrics",
                "source=apple ok=${result != null} words=${result?.wordByWordLines?.size ?: 0}"
            )
        }
        cache[key(request.title, request.artist, request.durationMs)] = result
        result
    }.recoverCatching { error ->
        request.cancellationToken?.throwIfCancellationRequested()
        when (error) {
            is java.util.concurrent.CancellationException -> throw error
            is LyricsLookupException -> throw error
            else -> throw LyricsLookupException(
                id, name,
                if (error is LinkageError) LyricsLookupErrorType.NativeError else LyricsLookupErrorType.ParseError,
                "Failed to fetch Apple Music lyrics",
                error
            )
        }
    }

    internal fun parse(
        jsonText: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long
    ): LyricsProviderResult? {
        val json = JSONObject(jsonText)
        val errorType = json.optString("error_type").takeIf { it.isNotBlank() }
        if (!json.optBoolean("ok", false)) {
            if (errorType == "NotFound") return null
            val type = when (errorType) {
                "NetworkError" -> LyricsLookupErrorType.NetworkError
                "ParseError" -> LyricsLookupErrorType.ParseError
                "NativeError" -> LyricsLookupErrorType.NativeError
                "NeedCredential" -> LyricsLookupErrorType.NeedCredential
                else -> LyricsLookupErrorType.NativeError
            }
            throw LyricsLookupException(
                id, name, type,
                json.optString("error").takeIf { it.isNotBlank() }
                    ?: "Invalid Apple Music native response: error_type=$errorType"
            )
        }
        val lrc = json.optString("lrc").takeIf { it.isNotBlank() }
            ?: translatedLrc(json)
            ?: return null
        return LyricsProviderResult(
            plainProviderId = id,
            plainProviderName = name,
            plainLrc = lrc,
            translatedLrc = translatedLrc(json),
            wordByWordLines = parseWordByWord(json, json.optString("translated_lrc").takeIf { it.isNotBlank() }),
            matchedTitle = json.optString("title", fallbackTitle),
            matchedArtist = json.optString("artist", fallbackArtist),
            matchedAlbum = json.optString("album", fallbackAlbum),
            matchedDurationMs = json.optLong("duration_ms", fallbackDurationMs)
        )
    }

    private fun parseWordByWord(json: JSONObject, translatedLrc: String?): List<WordByWordLine> {
        val lines = json.optJSONArray("word_by_word") ?: return emptyList()
        val translations = LrcParser.parse(translatedLrc.orEmpty())
            .filterNot { it.isMetadata }
            .sortedBy { it.timeMs }
            .map { it.copy(text = normalizeCjkSpacing(it.text)) }

        data class Raw(
            val startMs: Long,
            val endMs: Long,
            val text: String,
            val segments: List<WordByWordSegment>,
            val hasTranslatedText: Boolean,
            val translatedText: String
        )

        val raws = buildList {
            for (index in 0 until lines.length()) {
                val line = lines.optJSONObject(index) ?: continue
                val text = line.optString("text")
                if (text.isBlank()) continue
                val segmentsJson = line.optJSONArray("segments") ?: continue
                val segments = buildList {
                    for (segmentIndex in 0 until segmentsJson.length()) {
                        val segment = segmentsJson.optJSONObject(segmentIndex) ?: continue
                        val segmentText = segment.optString("text")
                        if (segmentText.isBlank()) continue
                        add(
                            WordByWordSegment(
                                text = segmentText,
                                startMs = segment.optLong("start_ms"),
                                endMs = segment.optLong("end_ms")
                            )
                        )
                    }
                }
                if (segments.isEmpty()) continue
                add(
                    Raw(
                        startMs = line.optLong("start_ms"),
                        endMs = line.optLong("end_ms"),
                        text = text,
                        segments = segments,
                        hasTranslatedText = line.has("translated_text"),
                        translatedText = if (line.isNull("translated_text")) ""
                            else normalizeCjkSpacing(line.optString("translated_text"))
                    )
                )
            }
        }
        if (raws.isEmpty()) return emptyList()

        // A line the singer repeats often carries the translation only once (Apple keys each
        // occurrence separately). Reuse it when the sentence is identical after dropping the
        // performance echo and folding scripts, and only when every candidate agrees.
        fun comparisonKey(text: String): String = ChineseText.comparisonForm(text)
        val reusableTranslations = raws
            .mapNotNull { raw ->
                raw.translatedText.takeIf { it.isNotBlank() }
                    ?.let { comparisonKey(raw.text) to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.distinct() }

        // Rust LRC timestamps truncate to 10ms. Legacy payloads are safe only when both
        // sides have a unique line in that bucket, including original lines without words.
        val translationsByTime = translations.groupBy { it.timeMs / 10 }
        val rawsByTime = raws.groupBy { it.startMs / 10 }
        val originalsByTime = LrcParser.parse(json.optString("lrc"))
            .filterNot { it.isMetadata }.groupBy { it.timeMs / 10 }

        return raws.map { raw ->
            val bucket = raw.startMs / 10
            val translatedText = if (raw.hasTranslatedText) raw.translatedText else {
                translationsByTime[bucket]?.singleOrNull()
                    ?.takeIf {
                        rawsByTime[bucket]?.size == 1 &&
                            originalsByTime[bucket]?.size == 1
                    }?.text
                    ?: reusableTranslations[comparisonKey(raw.text)]?.singleOrNull()
                    ?: ""
            }
            WordByWordLine(
                startMs = raw.startMs,
                endMs = raw.endMs,
                text = raw.text,
                segments = raw.segments,
                translatedText = translatedText,
                translatedSegments = buildTranslatedSegments(
                    translatedText, raw.segments, raw.startMs, raw.endMs
                )
            )
        }
    }

    /**
     * Builds the timing used when AUTO mode shows the simplified translation.
     *
     * When the "translation" is only a traditional→simplified conversion (the common case for
     * Mandarin songs), the original word timing is reused verbatim: character counts are equal,
     * so re-deriving times would only introduce drift. For real translations (Cantonese, foreign
     * lyrics) the counts differ, and the line's own range is distributed evenly as a fallback.
     */
    private fun buildTranslatedSegments(
        translatedText: String,
        segments: List<WordByWordSegment>,
        startMs: Long,
        endMs: Long
    ): List<WordByWordSegment> {
        if (translatedText.isBlank() || endMs <= startMs) return emptyList()
        // A real translation has no character-level timing we can trust; leave the timing empty so
        // the UI shows the translation as plain text instead of pretending to be karaoke-accurate.
        return conversionSegments(translatedText, segments).orEmpty()
    }

    /** Reuses the original token times when the translation is a pure script conversion. */
    private fun conversionSegments(
        translatedText: String,
        segments: List<WordByWordSegment>
    ): List<WordByWordSegment>? {
        if (segments.isEmpty()) return null
        val source = segments.joinToString("") { it.text }
        if (!ChineseText.isScriptConversion(source, translatedText)) {
            return null
        }
        // When the translation carries the echo too (it is sung, not an annotation), every timed
        // segment takes part. When only the original has it, the echo must not consume characters.
        val spokenTranslation = ChineseText.stripPerformanceRepeat(translatedText)
        // The echo can sit on only one side: Apple may keep it in the translation while the timed
        // tokens carry just a stray bracket, or the other way round. Decide from what the tokens
        // actually contain instead of comparing raw lengths.
        val sourceForm = ChineseText.normalizedForm(source)
        val tokensCarryEcho = sourceForm != ChineseText.normalizedForm(spokenTranslation) &&
            sourceForm == ChineseText.normalizedForm(translatedText)
        val spoken = ChineseText.stripPerformanceRepeat(source)
        val timed = when {
            tokensCarryEcho -> segments
            spoken.length == source.length -> segments
            else -> prefixSegments(segments, spoken.length)
        }
        if (timed.isEmpty()) return null
        // Consume against whatever the display text is: the full translation when the tokens also
        // carry the echo, otherwise only its spoken part.
        val targetText = if (tokensCarryEcho) translatedText else spokenTranslation
        // Convert the whole line once and slice by boundaries: a phrase such as 甦醒 or 瞭解 can
        // be split across tokens, and converting each token alone would leave it untranslated.
        val (convertedSource, boundaries) = ChineseText.simplifiedBoundaries(source)
        var sourceOffset = 0
        val mapped = mutableListOf<WordByWordSegment>()
        var cursor = 0
        for (segment in timed) {
            val segmentStart = sourceOffset
            val segmentEnd = segmentStart + segment.text.length
            sourceOffset = segmentEnd
            val boundaryStart = boundaries.getOrNull(segmentStart) ?: -1
            val boundaryEnd = boundaries.getOrNull(segmentEnd) ?: -1
            val target = when {
                boundaryStart >= 0 && boundaryEnd in boundaryStart..convertedSource.length ->
                    ChineseText.normalizedForm(convertedSource.substring(boundaryStart, boundaryEnd))
                // A phrase split across tokens (甦|醒) has no interior boundary; when the
                // conversion keeps the character count, slice by length instead of giving up.
                boundaryStart >= 0 && convertedSource.length == source.length ->
                    ChineseText.normalizedForm(
                        convertedSource.substring(
                            boundaryStart,
                            minOf(boundaryStart + segment.text.length, convertedSource.length)
                        )
                    )
                else -> ChineseText.normalizedForm(segment.text)
            }
            if (target.isEmpty()) continue
            val consumed = StringBuilder()
            var matched = 0
            // Punctuation and spaces may be present on either side; they never consume a token
            // character, so a translation with an extra space still maps onto the original timing.
            while (matched < target.length && cursor < targetText.length) {
                val character = targetText[cursor]
                consumed.append(character)
                cursor++
                if (!isIgnorable(character)) matched++
            }
            if (matched != target.length ||
                ChineseText.normalizedForm(consumed.toString()) != target
            ) return null
            mapped += segment.copy(text = consumed.toString())
        }
        while (cursor < targetText.length && isIgnorable(targetText[cursor])) {
            if (mapped.isEmpty()) return null
            val last = mapped.removeAt(mapped.lastIndex)
            mapped += last.copy(text = last.text + targetText[cursor])
            cursor++
        }
        return if (cursor == targetText.length) mapped else null
    }

    private fun translatedLrc(json: JSONObject): String? =
        json.optString("translated_lrc")
            .takeIf { it.isNotBlank() }
            ?.let(::normalizeCjkSpacing)

    /** Apple sometimes leaves a space between Han characters; it looks wrong in a lyric line. */
    private fun normalizeCjkSpacing(text: String): String {
        if (text.isEmpty()) return text
        val builder = StringBuilder(text.length)
        text.forEachIndexed { index, character ->
            val betweenHan = character == ' ' && index > 0 && index < text.lastIndex &&
                isHan(text[index - 1]) && isHan(text[index + 1])
            if (!betweenHan) builder.append(character)
        }
        return builder.toString()
    }

    private fun isHan(character: Char): Boolean =
        character.code in 0x3400..0x9FFF || character.code in 0xF900..0xFAFF

    private fun isIgnorable(character: Char): Boolean =
        character.isWhitespace() ||
        character in "\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A,.!?;:\u00B7\u2026\u2014\uFF08\uFF09()\u3010\u3011[]"

    /** The leading segments whose text covers [limit] UTF-16 units; the last one is truncated. */
    private fun prefixSegments(
        segments: List<WordByWordSegment>,
        limit: Int
    ): List<WordByWordSegment> {
        val out = mutableListOf<WordByWordSegment>()
        var count = 0
        for (segment in segments) {
            if (count >= limit) break
            val remaining = limit - count
            if (segment.text.length <= remaining) {
                out += segment
                count += segment.text.length
            } else {
                out += segment.copy(text = segment.text.substring(0, remaining))
                break
            }
        }
        return out
    }

    /** Whitespace and punctuation differ between scripts; they must not block the comparison. */
    private fun comparable(text: String): String = text.filterNot(::isIgnorable)

    private fun splitByCodePoint(text: String): List<String> {
        val characters = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val next = index + Character.charCount(text.codePointAt(index))
            characters += text.substring(index, next)
            index = next
        }
        return characters
    }

    private fun key(title: String, artist: String, durationMs: Long) =
        "$title\u0000$artist\u0000$durationMs"
}
