package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity

internal object LocalLyricsDeleteOps {
    fun deleteLocalLyricsItem(
        context: Context,
        item: LyricsStorage.LocalLyricsItem
    ): LyricsStorage.DeleteLocalLyricsItemResult {
        return if (item.indexKey.isNotBlank()) {
            deleteIndexedLyricsItem(context, item.indexKey)
        } else {
            deleteRootLyricsItem(context, item)
        }
    }

    private fun deleteIndexedLyricsItem(
        context: Context,
        indexKey: String
    ): LyricsStorage.DeleteLocalLyricsItemResult {
        val entries = LyricsIndexStore.read(context)
        val entry = entries.singleOrNull { it.key == indexKey }
            ?: return LyricsStorage.DeleteLocalLyricsItemResult.NotFound

        val rawIndex = LyricsIndexStore.captureRaw(context)
        if (rawIndex is LyricsIndexStore.RawSnapshot.Unreadable) {
            return LyricsStorage.DeleteLocalLyricsItemResult.Failed
        }

        val fileSnapshots = listOf(entry.plainFile, entry.wordByWordFile)
            .filter { it.isNotBlank() }
            .distinct()
            .map { relativeFile ->
                ManagedFileSnapshot.capture(context, relativeFile)
                    ?: return LyricsStorage.DeleteLocalLyricsItemResult.Failed
            }
        val updatedEntries = entries.filterNot { indexed -> indexed.key == entry.key }

        // Remove the index entry first so an interrupted delete cannot leave a visible
        // item pointing at an already deleted file.
        if (!LyricsIndexStore.write(context, updatedEntries)) {
            LyricsIndexStore.restoreRaw(context, rawIndex)
            return LyricsStorage.DeleteLocalLyricsItemResult.Failed
        }

        val filesDeleted = fileSnapshots.all { it.delete(context) }
        if (!filesDeleted) {
            val filesRestored = fileSnapshots.map { it.restore(context) }.all { it }
            if (filesRestored) {
                LyricsIndexStore.restoreRaw(context, rawIndex)
            }
            return LyricsStorage.DeleteLocalLyricsItemResult.Failed
        }

        return LyricsStorage.DeleteLocalLyricsItemResult.Deleted(entry.toSongIdentity())
    }

    private sealed class ManagedFileSnapshot {
        object Missing : ManagedFileSnapshot()

        data class Present(
            val relativeFile: String,
            val content: String
        ) : ManagedFileSnapshot()

        fun delete(context: Context): Boolean {
            return when (this) {
                is Missing -> true
                is Present -> {
                    !LyricsFileStore.managedLyricsExists(context, relativeFile) ||
                        LyricsFileStore.deleteManagedLyrics(context, relativeFile)
                }
            }
        }

        fun restore(context: Context): Boolean {
            return when (this) {
                is Missing -> true
                is Present -> {
                    LyricsFileStore.managedLyricsExists(context, relativeFile) ||
                        LyricsFileStore.writeManagedLyrics(
                            context = context,
                            fileName = relativeFile.substringAfterLast('/'),
                            lyrics = content
                        )
                }
            }
        }

        companion object {
            fun capture(context: Context, relativeFile: String): ManagedFileSnapshot? {
                if (!LyricsFileStore.managedLyricsExists(context, relativeFile)) {
                    return Missing
                }
                val content = LyricsFileStore.readManagedLyrics(context, relativeFile) ?: return null
                return Present(relativeFile, content)
            }
        }
    }

    private fun deleteRootLyricsItem(
        context: Context,
        item: LyricsStorage.LocalLyricsItem
    ): LyricsStorage.DeleteLocalLyricsItemResult {
        if (!LyricsFileNaming.isSafePlainLyricsBaseName(item.name)) {
            return LyricsStorage.DeleteLocalLyricsItemResult.NotFound
        }
        if (!LyricsFileStore.legacyPlainLyricsExists(context, item.name)) {
            return LyricsStorage.DeleteLocalLyricsItemResult.NotFound
        }
        if (!LyricsFileStore.deleteLegacyPlainLyrics(context, item.name)) {
            return LyricsStorage.DeleteLocalLyricsItemResult.Failed
        }

        val target = item.title.takeIf { it.isNotBlank() }?.let { title ->
            SongIdentity(
                title = title,
                artist = item.artist,
                album = item.album,
                durationMs = item.durationMs
            )
        }
        return LyricsStorage.DeleteLocalLyricsItemResult.Deleted(target)
    }

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
