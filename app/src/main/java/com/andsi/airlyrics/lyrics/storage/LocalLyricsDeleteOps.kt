package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity

internal object LocalLyricsDeleteOps {
    fun deleteAllSavedLyrics(context: Context): LyricsStorage.DeleteAllSavedLyricsResult {
        val entries = LyricsIndexStore.read(context)
        val indexedFiles = entries.flatMap { entry ->
            listOf(entry.plainFile, entry.wordByWordFile)
        }
        val fileResult = LyricsFileStore.deleteAllSavedLyricsFiles(context, indexedFiles)
        val hadSavedLyrics = entries.isNotEmpty() || fileResult.foundAny

        if (!fileResult.deletedAll) {
            return LyricsStorage.DeleteAllSavedLyricsResult.FAILED
        }
        if (entries.isNotEmpty() && !LyricsIndexStore.write(context, emptyList())) {
            return LyricsStorage.DeleteAllSavedLyricsResult.FAILED
        }

        return if (hadSavedLyrics) {
            LyricsStorage.DeleteAllSavedLyricsResult.DELETED
        } else {
            LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE
        }
    }

    fun deleteLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        mode: LyricsStorage.DeleteMode
    ): Boolean {
        val entries = LyricsIndexStore.read(context)
        val identity = SongIdentity(title = title, artist = artist, durationMs = duration)
        val matched = entries.filter { it.isSameSong(identity) }
        val matchedKeys = matched.map { it.key }.toSet()
        var deletedAny = false
        val now = System.currentTimeMillis()

        matched.forEach { entry ->
            val deleteGeneratedPlainFallback =
                mode == LyricsStorage.DeleteMode.WORD_BY_WORD &&
                    entry.plainSource == LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
            if (
                (mode == LyricsStorage.DeleteMode.PLAIN ||
                    mode == LyricsStorage.DeleteMode.ALL ||
                    deleteGeneratedPlainFallback) &&
                entry.plainFile.isNotBlank()
            ) {
                LyricsFileStore.deleteManagedLyrics(context, entry.plainFile)
                deletedAny = true
            }
            if (
                (mode == LyricsStorage.DeleteMode.WORD_BY_WORD || mode == LyricsStorage.DeleteMode.ALL) &&
                entry.wordByWordFile.isNotBlank()
            ) {
                LyricsFileStore.deleteManagedLyrics(context, entry.wordByWordFile)
                deletedAny = true
            }
        }

        val updatedEntries = entries.mapNotNull { entry ->
            if (entry.key !in matchedKeys) return@mapNotNull entry

            val deleteGeneratedPlainFallback =
                mode == LyricsStorage.DeleteMode.WORD_BY_WORD &&
                    entry.plainSource == LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
            val plainFile = if (
                mode == LyricsStorage.DeleteMode.PLAIN ||
                mode == LyricsStorage.DeleteMode.ALL ||
                deleteGeneratedPlainFallback
            ) {
                ""
            } else {
                entry.plainFile
            }
            val wordByWordFile = if (mode == LyricsStorage.DeleteMode.WORD_BY_WORD || mode == LyricsStorage.DeleteMode.ALL) {
                ""
            } else {
                entry.wordByWordFile
            }

            if (plainFile.isBlank() && wordByWordFile.isBlank()) {
                null
            } else {
                entry.copy(
                    plainFile = plainFile,
                    wordByWordFile = wordByWordFile,
                    updatedAt = now
                )
            }
        }

        if (updatedEntries.size != entries.size || updatedEntries != entries) {
            LyricsIndexStore.write(context, updatedEntries)
        }

        if (mode == LyricsStorage.DeleteMode.PLAIN || mode == LyricsStorage.DeleteMode.ALL) {
            val legacyFileName = LyricsFileNaming.legacyPlainFileName(title, artist, duration)
            deletedAny = LyricsFileStore.deleteLegacyPlainLyrics(context, legacyFileName) || deletedAny
        }

        return deletedAny
    }
}
