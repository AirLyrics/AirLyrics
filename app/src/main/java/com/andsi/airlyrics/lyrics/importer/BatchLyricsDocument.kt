package com.andsi.airlyrics.lyrics.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URLDecoder
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.parser.TtmlParseResult
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.parser.TtmlParser
import com.andsi.airlyrics.lyrics.parser.TtmlTimingMode
import com.andsi.airlyrics.lyrics.parser.toParsedWordByWordLyrics
import com.andsi.airlyrics.lyrics.storage.LyricsFileStore

internal data class BatchLyricsDocument(
    val name: String,
    val plainLrc: String,
    val words: List<WordByWordLine> = emptyList(),
    val metadata: List<String> = emptyList(),
    val title: String = "",
    val artist: String = "",
    val targetKey: String = ""
) {
    val matchInfo get() = BatchLyricsMatcher.Document(name, title, artist, targetKey)
}

internal object BatchLyricsDocumentReader {
    enum class Failure { READ, TOO_LARGE, INVALID }
    data class Result(val name: String, val document: BatchLyricsDocument? = null, val failure: Failure? = null, val fingerprint: String = "")

    fun read(context: Context, uri: Uri): Result {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return runCatching {
            when (val read = LyricsFileStore.readTextFromUriWithResult(context, uri)) {
                LyricsFileStore.ReadTextResult.TooLarge -> Result(name, failure = Failure.TOO_LARGE)
                LyricsFileStore.ReadTextResult.Failed -> Result(name, failure = Failure.READ)
                is LyricsFileStore.ReadTextResult.Success -> parse(name, read.text, LyricsFormatDetector.detect(context, uri, read.text)).copy(
                    fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(read.text.toByteArray()).joinToString("") { "%02x".format(it) }
                )
            }
        }.getOrElse { Result(name, failure = Failure.READ) }
    }

    fun parse(name: String, text: String, format: LyricsDocumentFormat): Result {
        val content = if (format == LyricsDocumentFormat.LRC) text.lineSequence().filterNot {
            it.trimStart().startsWith("[airlyrics-key:", ignoreCase = true)
        }.joinToString("\n") else text
        val parsed = when (format) {
            LyricsDocumentFormat.TTML -> {
                val ttml = (TtmlParser.parse(text) as? TtmlParseResult.Success)?.document
                    ?: return Result(name, failure = Failure.INVALID)
                val lyrics = ttml.toParsedWordByWordLyrics()
                if (ttml.timingMode == TtmlTimingMode.WORD && lyrics.wordByWordLines.isEmpty()) {
                    return Result(name, failure = Failure.INVALID)
                }
                BatchLyricsDocument(name, lyrics.plainLrc, lyrics.wordByWordLines)
            }
            LyricsDocumentFormat.LRC -> {
                // Do not silently downgrade malformed word timing to plain text.
                val hasWordTags = WordByWordLrcParser.hasWordTiming(content)
                if (hasWordTags) {
                    val words = (LyricsImportParsers.parseWordByWord(format, content) as? LyricsTextParseResult.Success)?.value
                        ?: return Result(name, failure = Failure.INVALID)
                    BatchLyricsDocument(name, words.plainLrc, words.wordByWordLines, words.metadataLines)
                } else {
                    val plain = (LyricsImportParsers.parsePlain(format, content) as? LyricsTextParseResult.Success)?.value
                        ?: return Result(name, failure = Failure.INVALID)
                    BatchLyricsDocument(name, plain)
                }
            }
            LyricsDocumentFormat.UNKNOWN -> return Result(name, failure = Failure.INVALID)
        }
        if (parsed.plainLrc.isBlank()) return Result(name, failure = Failure.INVALID)
        fun tag(key: String): String = if (format == LyricsDocumentFormat.LRC) {
            Regex("(?im)^\\s*\\[$key:([^]\\r\\n]*)]\\s*$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        } else ""
        return Result(name, document = parsed.copy(title = tag("ti"), artist = tag("ar"), targetKey = runCatching { URLDecoder.decode(tag("airlyrics-key"), "UTF-8") }.getOrDefault(tag("airlyrics-key"))))
    }
}
