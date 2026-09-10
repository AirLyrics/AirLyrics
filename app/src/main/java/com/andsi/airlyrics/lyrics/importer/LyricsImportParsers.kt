package com.andsi.airlyrics.lyrics.importer

import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.ParsedWordByWordLyrics
import com.andsi.airlyrics.lyrics.parser.TtmlParseResult
import com.andsi.airlyrics.lyrics.parser.TtmlParser
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.parser.toParsedWordByWordLyrics
import com.andsi.airlyrics.lyrics.parser.toPlainLrc

internal sealed interface LyricsTextParseResult<out T> {
    data class Success<T>(val value: T) : LyricsTextParseResult<T>

    data class InvalidFormat(
        val invalidLineNumbers: List<Int> = emptyList()
    ) : LyricsTextParseResult<Nothing>
}

internal object LyricsImportParsers {
    fun parsePlain(
        format: LyricsDocumentFormat,
        text: String
    ): LyricsTextParseResult<String> {
        return when (format) {
            LyricsDocumentFormat.LRC -> parsePlainLrc(text)
            LyricsDocumentFormat.TTML -> when (val result = TtmlParser.parse(text)) {
                is TtmlParseResult.Success -> result.document.toPlainLrc()
                    .takeIf { it.isNotBlank() }
                    ?.let { LyricsTextParseResult.Success(it) }
                    ?: LyricsTextParseResult.InvalidFormat()

                is TtmlParseResult.Failure -> {
                    LyricsTextParseResult.InvalidFormat(result.invalidLineNumbers)
                }
            }
            LyricsDocumentFormat.UNKNOWN -> LyricsTextParseResult.InvalidFormat()
        }
    }

    fun parseWordByWord(
        format: LyricsDocumentFormat,
        text: String
    ): LyricsTextParseResult<ParsedWordByWordLyrics> {
        return when (format) {
            LyricsDocumentFormat.LRC -> parseWordByWordLrc(text)
            LyricsDocumentFormat.TTML -> when (val result = TtmlParser.parse(text)) {
                is TtmlParseResult.Success -> result.document.toParsedWordByWordLyrics()
                    .takeIf { it.wordByWordLines.isNotEmpty() }
                    ?.let { LyricsTextParseResult.Success(it) }
                    ?: LyricsTextParseResult.InvalidFormat()

                is TtmlParseResult.Failure -> {
                    LyricsTextParseResult.InvalidFormat(result.invalidLineNumbers)
                }
            }
            LyricsDocumentFormat.UNKNOWN -> LyricsTextParseResult.InvalidFormat()
        }
    }

    private fun parsePlainLrc(text: String): LyricsTextParseResult<String> {
        val validation = LrcParser.validateForStorage(text)
        if (!validation.isValid) {
            return LyricsTextParseResult.InvalidFormat(validation.invalidLineNumbers)
        }

        val normalized = LrcParser.normalizeForStorage(text)
        return if (normalized.isBlank()) {
            LyricsTextParseResult.InvalidFormat()
        } else {
            LyricsTextParseResult.Success(normalized)
        }
    }

    private fun parseWordByWordLrc(
        text: String
    ): LyricsTextParseResult<ParsedWordByWordLyrics> {
        val validation = WordByWordLrcParser.validateForStorage(text)
        if (!validation.isValid) {
            return LyricsTextParseResult.InvalidFormat(validation.invalidLineNumbers)
        }

        val parsed = WordByWordLrcParser.parseImport(text)
        return if (parsed.wordByWordLines.isEmpty()) {
            LyricsTextParseResult.InvalidFormat()
        } else {
            LyricsTextParseResult.Success(parsed)
        }
    }
}
