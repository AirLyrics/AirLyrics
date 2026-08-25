package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.parser.LrcParser

internal object LocalLyricsEditor {
    fun readItemText(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        target: LyricsStorage.LocalLyricsEditTarget
    ): String? {
        val entry = findEntry(context, item)
        return when (target) {
            LyricsStorage.LocalLyricsEditTarget.PLAIN -> {
                if (!item.hasPlainLyrics) return null
                if (item.indexKey.isNotBlank()) {
                    val relativeFile = entry?.plainFile?.takeIf { it.isNotBlank() } ?: return null
                    return LyricsFileStore.readManagedLyrics(context, relativeFile)
                }
                LyricsFileStore.readRootPlainLyricsFileByName(context, item.name)
            }
            LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD -> {
                val wordByWordText = entry?.wordByWordFile
                    ?.takeIf { it.isNotBlank() }
                    ?.let { LyricsFileStore.readManagedLyrics(context, it) }
                    ?: return null
                val wordByWordDocument = WordByWordLyricsStorageOps.parseStoredWordByWordText(wordByWordText)
                wordByWordDocument.wordByWordLines.takeIf { it.isNotEmpty() }?.let {
                    WordByWordLrcParser.wordByWordLinesToWordByWordLrc(it, wordByWordDocument.metadataLines)
                }
            }
        }
    }

    fun updatePlainItemTextWithResult(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        plainLrc: String
    ): LyricsStorage.LocalLyricsUpdateResult {
        if (!item.hasPlainLyrics || LyricsFileNaming.isWordByWordLyricsFile(item.name)) {
            return LyricsStorage.LocalLyricsUpdateResult(saved = false)
        }

        val validation = LrcParser.validateForStorage(plainLrc)
        if (!validation.isValid) {
            return LyricsStorage.LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val normalizedPlainLrc = LrcParser.normalizeForStorage(plainLrc)
        if (normalizedPlainLrc.isBlank()) return LyricsStorage.LocalLyricsUpdateResult(saved = false)

        return LyricsStorage.withStorageLock {
            val entry = findEntry(context, item)
            val saved = if (item.indexKey.isNotBlank()) {
                val plainFileName = entry?.plainFile
                    ?.takeIf { it.isNotBlank() }
                    ?.substringAfterLast('/')
                    ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
                LyricsFileStore.writeManagedLyrics(context, plainFileName, normalizedPlainLrc)
            } else {
                LyricsFileStore.writeRootPlainLyricsFileByName(context, item.name, normalizedPlainLrc)
            }
            if (!saved) return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)

            if (entry != null) {
                val now = System.currentTimeMillis()
                val updated = LyricsIndexStore.read(context).map { indexed ->
                    if (indexed.key == entry.key) indexed.copy(updatedAt = now) else indexed
                }
                if (!LyricsIndexStore.write(context, updated)) {
                    return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
                }
            }

            LyricsStorage.LocalLyricsUpdateResult(saved = true)
        }
    }

    fun validateWordByWordItemText(wordByWordLrc: String): LyricsStorage.LocalLyricsUpdateResult {
        val validation = WordByWordLrcParser.validateForStorage(wordByWordLrc)
        return LyricsStorage.LocalLyricsUpdateResult(
            saved = validation.isValid,
            invalidLineNumbers = validation.invalidLineNumbers
        )
    }

    fun updateWordByWordItemTextWithResult(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        wordByWordLrc: String
    ): LyricsStorage.LocalLyricsUpdateResult {
        if (!item.hasWordByWordLyrics) return LyricsStorage.LocalLyricsUpdateResult(saved = false)

        val validation = WordByWordLrcParser.validateForStorage(wordByWordLrc)
        if (!validation.isValid) {
            return LyricsStorage.LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val parsedWordByWordLyrics = WordByWordLrcParser.parseImport(wordByWordLrc)
        val wordByWordLines = parsedWordByWordLyrics.wordByWordLines
        if (wordByWordLines.isEmpty()) return LyricsStorage.LocalLyricsUpdateResult(saved = false)
        val wordByWordJson = WordByWordLyricsJsonCodec.wordByWordLinesToJson(
            wordByWordLines,
            parsedWordByWordLyrics.metadataLines
        )

        return LyricsStorage.withStorageLock {
            val entry = findEntry(context, item)
                ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            val wordByWordFileName = entry.wordByWordFile.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            val fallbackPlainLrc = WordByWordLyricsStorageOps.buildGeneratedPlainFallback(
                context,
                entry,
                parsedWordByWordLyrics
            )
            if (fallbackPlainLrc.isBlank()) return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            if (!LyricsFileStore.writeManagedLyrics(context, wordByWordFileName, wordByWordJson)) {
                return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            }

            val plainSaved = if (entry.plainFile.isBlank()) {
                PlainLyricsStorageOps.savePlainLyrics(
                    context = context,
                    title = entry.title,
                    artist = entry.artist,
                    duration = entry.durationMs,
                    plainLrc = fallbackPlainLrc,
                    album = entry.album,
                    plainSource = LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
                    plainProvider = "local",
                    overwrite = true
                )
            } else {
                val plainFileName = entry.plainFile.substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
                LyricsFileStore.writeManagedLyrics(context, plainFileName, fallbackPlainLrc)
            }
            if (!plainSaved) {
                return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            }

            val now = System.currentTimeMillis()
            val updated = LyricsIndexStore.read(context).map { indexed ->
                if (indexed.key == entry.key) {
                    indexed.copy(
                        plainSource = LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
                        plainProvider = "local",
                        updatedAt = now
                    )
                } else {
                    indexed
                }
            }

            if (LyricsIndexStore.write(context, updated)) {
                LyricsStorage.LocalLyricsUpdateResult(saved = true)
            } else {
                LyricsStorage.LocalLyricsUpdateResult(saved = false)
            }
        }
    }

    private fun findEntry(
        context: Context,
        item: LyricsStorage.LocalLyricsItem
    ): LyricsIndexEntry? {
        if (item.indexKey.isNotBlank()) {
            return LyricsIndexStore.read(context).singleOrNull { it.key == item.indexKey }
        }
        return null
    }
}
