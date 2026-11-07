package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser

/** Structured editing is separate from the public LRC read/export interface. */
internal object WordLyricsEditorStorage {
    fun read(context: Context, item: LyricsStorage.LocalLyricsItem): String? = LyricsStorage.withStorageLock {
        if (item.indexKey.startsWith(SpicyLyricsCache.PREFIX)) {
            val result = SpicyLyricsCache.forContext(context).read(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX))
                ?: return@withStorageLock null
            return@withStorageLock WordLyricsDocument(result.wordByWordLines, translatedLrc = result.translatedLrc)
                .takeIf { it.lines.isNotEmpty() }?.serialize()
        }
        val entry = LyricsIndexStore.read(context).singleOrNull { it.key == item.indexKey }
            ?: return@withStorageLock null
        val text = LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile)
            ?: return@withStorageLock null
        WordLyricsDocument.parse(text).takeIf { it.lines.isNotEmpty() }?.serialize()
    }

    fun save(context: Context, item: LyricsStorage.LocalLyricsItem, text: String): LyricsStorage.LocalLyricsUpdateResult {
        val document = WordLyricsDocument.parse(text)
        val invalid = document.invalidLines()
        if (document.lines.isEmpty() || invalid.isNotEmpty())
            return LyricsStorage.LocalLyricsUpdateResult(false, invalid)
        if (item.indexKey.startsWith(SpicyLyricsCache.PREFIX)) {
            return SpicyLyricsCache.forContext(context).editWords(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX), document)
        }
        return LyricsStorage.withStorageLock {
            val entry = LyricsIndexStore.read(context).singleOrNull { it.key == item.indexKey }
                ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(false)
            val oldWords = LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile)
                ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(false)
            if (WordLyricsDocument.parse(oldWords) == document)
                return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(true)
            val plainName = entry.plainFile.substringAfterLast('/').ifBlank {
                LyricsFileNaming.managedPlainFileName(SongIdentity(entry.title, entry.artist, entry.album, entry.durationMs))
            }
            val plainPath = entry.plainFile.ifBlank { LyricsFileNaming.managedRelativePath(plainName) }
            val oldPlain = LyricsFileStore.readManagedLyrics(context, plainPath)
            if (oldPlain == null && LyricsFileStore.managedLyricsExists(context, plainPath))
                return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(false)
            val indexSnapshot = LyricsIndexStore.captureRaw(context)
            if (indexSnapshot is LyricsIndexStore.RawSnapshot.Unreadable)
                return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(false)
            val vocal = document.lines.filterNot { it.isBackground }
            val translations = vocal.filter { it.translatedText.isNotBlank() }
                .associate { it.startMs to it.translatedText.lines() }
            val generated = WordByWordLrcParser.wordByWordLinesToPlainLrc(vocal, document.metadata, translations)
            val plain = if (!document.translatedLrc.isNullOrBlank()) {
                LrcParser.mergeOriginalAndTranslationForStorage(generated, document.translatedLrc)
            } else if (translations.isNotEmpty()) generated
            else WordByWordLyricsStorageOps.buildGeneratedPlainFallback(context, entry,
                com.andsi.airlyrics.lyrics.parser.ParsedWordByWordLyrics(vocal, generated, false, document.metadata))
            var committed = false
            try {
                check(LyricsFileStore.writeManagedLyrics(context, entry.wordByWordFile.substringAfterLast('/'), document.serialize()))
                check(LyricsFileStore.writeManagedLyrics(context, plainName, plain))
                check(LyricsIndexStore.write(context, LyricsIndexStore.read(context).map {
                    if (it.key == entry.key) it.copy(plainFile = plainPath,
                        plainSource = LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK, plainProvider = "local",
                        updatedAt = System.currentTimeMillis()) else it
                }))
                committed = true
                LyricsStorage.markContentChanged()
                LyricsStorage.LocalLyricsUpdateResult(true)
            } finally {
                if (!committed) {
                    check(LyricsFileStore.writeManagedLyrics(context, entry.wordByWordFile.substringAfterLast('/'), oldWords))
                    if (oldPlain == null) check(LyricsFileStore.deleteManagedLyrics(context, plainPath))
                    else check(LyricsFileStore.writeManagedLyrics(context, plainName, oldPlain))
                    check(LyricsIndexStore.restoreRaw(context, indexSnapshot))
                }
            }
        }
    }
}
