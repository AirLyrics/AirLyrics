package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.lyrics.parser.KaraokeLrcParser
import com.andsi.airlyrics.lyrics.parser.LrcParser

internal object LocalLyricsEditor {
    fun readItemText(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        target: LyricsStorage.LocalLyricsEditTarget
    ): String? {
        val entry = LyricsIndexStore.findByFileName(context, item.name)
        return when (target) {
            LyricsStorage.LocalLyricsEditTarget.PLAIN -> {
                if (item.hasPlainLyrics && entry?.file?.isNotBlank() == true) {
                    LyricsFileStore.readManagedLyrics(context, entry.file)?.let { return it }
                }
                if (item.hasPlainLyrics) LyricsFileStore.readLyricsFileByName(context, item.name) else null
            }
            LyricsStorage.LocalLyricsEditTarget.KARAOKE -> {
                val rawKaraoke = entry?.karaokeFile
                    ?.takeIf { it.isNotBlank() }
                    ?.let { LyricsFileStore.readManagedLyrics(context, it) }
                    ?: return null
                val karaokeDocument = KaraokeLyricsStorageOps.parseStoredKaraokeText(rawKaraoke)
                karaokeDocument.lines.takeIf { it.isNotEmpty() }?.let {
                    KaraokeLrcParser.linesToEnhancedLrc(it, karaokeDocument.metadataLines)
                }
            }
        }
    }

    fun updatePlainItemTextWithResult(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        text: String
    ): LyricsStorage.LocalLyricsUpdateResult {
        if (!item.hasPlainLyrics || LyricsFileNaming.isKaraokeLyricsFile(item.name)) {
            return LyricsStorage.LocalLyricsUpdateResult(saved = false)
        }

        val validation = LrcParser.validateForStorage(text)
        if (!validation.isValid) {
            return LyricsStorage.LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return LyricsStorage.LocalLyricsUpdateResult(saved = false)

        return LyricsStorage.withStorageLock {
            val entry = LyricsIndexStore.findByFileName(context, item.name)
            val fileName = entry?.file?.substringAfterLast('/') ?: item.name
            val saved = if (entry?.file?.isNotBlank() == true) {
                LyricsFileStore.writeManagedLyrics(context, fileName, normalizedLyrics)
            } else {
                LyricsFileStore.writeLyricsFileByName(context, item.name, normalizedLyrics)
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

    fun validateKaraokeItemText(text: String): LyricsStorage.LocalLyricsUpdateResult {
        val validation = KaraokeLrcParser.validateForStorage(text)
        return LyricsStorage.LocalLyricsUpdateResult(
            saved = validation.isValid,
            invalidLineNumbers = validation.invalidLineNumbers
        )
    }

    fun updateKaraokeItemTextWithResult(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        text: String
    ): LyricsStorage.LocalLyricsUpdateResult {
        if (!item.hasKaraokeLyrics) return LyricsStorage.LocalLyricsUpdateResult(saved = false)

        val validation = KaraokeLrcParser.validateForStorage(text)
        if (!validation.isValid) {
            return LyricsStorage.LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val parsedKaraoke = KaraokeLrcParser.parseImport(text)
        val karaokeLines = parsedKaraoke.karaokeLines
        if (karaokeLines.isEmpty()) return LyricsStorage.LocalLyricsUpdateResult(saved = false)
        val json = KaraokeLyricsCodec.linesToJson(karaokeLines, parsedKaraoke.metadataLines)

        return LyricsStorage.withStorageLock {
            val entry = LyricsIndexStore.findByFileName(context, item.name)
                ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            val karaokeFileName = entry.karaokeFile.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            val fallbackPlainLrc = KaraokeLyricsStorageOps.buildGeneratedPlainFallback(context, entry, parsedKaraoke)
            if (fallbackPlainLrc.isBlank()) return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            if (!LyricsFileStore.writeManagedLyrics(context, karaokeFileName, json)) {
                return@withStorageLock LyricsStorage.LocalLyricsUpdateResult(saved = false)
            }

            val plainSaved = if (entry.file.isBlank()) {
                PlainLyricsStorageOps.saveLyrics(
                    context = context,
                    title = entry.title,
                    artist = entry.artist,
                    duration = entry.durationMs,
                    lyrics = fallbackPlainLrc,
                    album = entry.album,
                    source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
                    provider = "local",
                    overwrite = true
                )
            } else {
                val plainFileName = entry.file.substringAfterLast('/').takeIf { it.isNotBlank() }
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
                        source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
                        provider = "local",
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
}
