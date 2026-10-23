package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider

/** Repair only app-downloaded Apple documents. Imports and other providers are never rewritten. */
internal object AppleStoredLyricsRepair {
    fun repair(context: Context, entry: LyricsIndexEntry) {
        if (entry.plainSource != LyricsStorage.SOURCE_DOWNLOADED ||
            entry.wordByWordProvider != "apple" || entry.plainProvider != "apple") return
        val raw = entry.wordByWordFile.takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) } ?: return
        ChineseText.ensureLoaded(context)
        val document = WordByWordLyricsJsonCodec.parseWordByWordDocumentJson(raw)
        val plain = entry.plainFile.takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
        val plainLines = LrcParser.parse(plain.orEmpty()).filterNot { it.isMetadata }
        val replacements = mutableMapOf<String, String>()
        val repaired = document.wordByWordLines.map { line ->
            var segments = line.segments
            var text = line.text
            val corrupted = LyricsStorage.hasLostEchoBracket(segments.joinToString("") { it.text })
            if (corrupted) {
                // The old flat span regex discarded a whole timed token. Its time is lost; retain
                // the intact sung sentence rather than invent timing for the missing echo prefix.
                val spoken = ChineseText.stripPerformanceRepeat(segments.joinToString("") { it.text })
                var remaining = spoken.count { !it.isWhitespace() }
                segments = segments.mapNotNull { segment ->
                    if (remaining <= 0) null else {
                        var end = 0
                        while (end < segment.text.length && remaining > 0) {
                            if (!segment.text[end].isWhitespace()) remaining--
                            end++
                        }
                        segment.copy(text = segment.text.take(end))
                    }
                }
                text = segments.joinToString("") { it.text }.trim()
                replacements[line.text] = text
            }
            val translation = line.translatedText.ifBlank {
                plainLines.filter { it.timeMs / 10 == line.startMs / 10 }
                    .singleOrNull()?.translation.orEmpty()
            }.let { if (corrupted) ChineseText.stripPerformanceRepeat(it) else it }
            val translated = ApplePlainLyricsProvider.conversionSegments(translation, segments)
            if (!corrupted && translated == null) return@map line
            line.copy(text = text, segments = segments, translatedText = translation,
                translatedSegments = translated ?: line.translatedSegments)
        }
        if (repaired == document.wordByWordLines) return
        // Idempotent, same managed files; no index, selection, provenance or metadata changes.
        if (plain != null && replacements.isNotEmpty()) {
            val updated = plain.lineSequence().joinToString("\n") { row ->
                val prefix = Regex("^(?:\\[[^]]*])+").find(row)?.value.orEmpty()
                prefix + (replacements[row.removePrefix(prefix)] ?: row.removePrefix(prefix))
            }
            if (!LyricsFileStore.writeManagedLyrics(context, entry.plainFile.substringAfterLast('/'), updated)) return
        }
        if (!LyricsFileStore.writeManagedLyrics(context, entry.wordByWordFile.substringAfterLast('/'),
                WordByWordLyricsJsonCodec.wordByWordLinesToJson(repaired, document.metadataLines))) return
    }
}
