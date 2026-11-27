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

        val untimed = item.isUntimed
        val hasTranslation = untimed && !findEntry(context, item)?.untimedTranslation.isNullOrBlank()
        val validation = if (untimed) LrcParser.StorageValidationResult(plainLrc.isNotBlank() || hasTranslation)
            else LrcParser.validateForStorage(plainLrc)
        if (!validation.isValid) {
            return LyricsStorage.LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val normalizedPlainLrc = if (untimed) plainLrc else LrcParser.normalizeForStorage(plainLrc)
        if (normalizedPlainLrc.isBlank() && !hasTranslation) return LyricsStorage.LocalLyricsUpdateResult(saved = false)

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
                    if (indexed.key == entry.key) indexed.copy(updatedAt = now, plainSource = LyricsStorage.SOURCE_MANUAL_EDIT, plainProvider = "local") else indexed
                }
                if (!LyricsIndexStore.write(context, updated)) {
                    return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
                }
            }

            LyricsStorage.LocalLyricsUpdateResult(saved = true)
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
