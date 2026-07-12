package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity

internal object LocalLyricsDeleteOps {
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
                mode == LyricsStorage.DeleteMode.KARAOKE && entry.source == LyricsStorage.SOURCE_KARAOKE_FALLBACK
            if (
                (mode == LyricsStorage.DeleteMode.PLAIN ||
                    mode == LyricsStorage.DeleteMode.ALL ||
                    deleteGeneratedPlainFallback) &&
                entry.file.isNotBlank()
            ) {
                LyricsFileStore.deleteManagedLyrics(context, entry.file)
                deletedAny = true
            }
            if (
                (mode == LyricsStorage.DeleteMode.KARAOKE || mode == LyricsStorage.DeleteMode.ALL) &&
                entry.karaokeFile.isNotBlank()
            ) {
                LyricsFileStore.deleteManagedLyrics(context, entry.karaokeFile)
                deletedAny = true
            }
        }

        val updatedEntries = entries.mapNotNull { entry ->
            if (entry.key !in matchedKeys) return@mapNotNull entry

            val deleteGeneratedPlainFallback =
                mode == LyricsStorage.DeleteMode.KARAOKE && entry.source == LyricsStorage.SOURCE_KARAOKE_FALLBACK
            val plainFile = if (
                mode == LyricsStorage.DeleteMode.PLAIN ||
                mode == LyricsStorage.DeleteMode.ALL ||
                deleteGeneratedPlainFallback
            ) {
                ""
            } else {
                entry.file
            }
            val karaokeFile = if (mode == LyricsStorage.DeleteMode.KARAOKE || mode == LyricsStorage.DeleteMode.ALL) {
                ""
            } else {
                entry.karaokeFile
            }

            if (plainFile.isBlank() && karaokeFile.isBlank()) {
                null
            } else {
                entry.copy(
                    file = plainFile,
                    karaokeFile = karaokeFile,
                    updatedAt = now
                )
            }
        }

        if (updatedEntries.size != entries.size || updatedEntries != entries) {
            LyricsIndexStore.write(context, updatedEntries)
        }

        if (mode == LyricsStorage.DeleteMode.PLAIN || mode == LyricsStorage.DeleteMode.ALL) {
            val legacyFileName = LegacyLyricsFileName.make(title, artist, duration)
            deletedAny = LyricsFileStore.deleteLegacyLyrics(context, legacyFileName) || deletedAny
        }

        return deletedAny
    }
}
