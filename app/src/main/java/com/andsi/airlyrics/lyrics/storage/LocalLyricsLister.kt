package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalLyricsLister {
    fun listRecent(context: Context, limit: Int): List<LyricsStorage.LocalLyricsItem> {
        val indexEntries = LyricsIndexStore.read(context)
        val indexedByFileName = indexEntries.associateBy { it.plainFile.substringAfterLast('/') }
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        val items = if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            val managedDir = root?.findFile(MANAGED_LYRICS_DIR)?.takeIf { it.isDirectory }
            val rootItems = root?.listFiles().orEmpty()
                .filter { it.isFile && LyricsFileNaming.isPlainLyricsFile(it.name) }
            val managedItems = managedDir?.listFiles().orEmpty()
                .filter { it.isFile && LyricsFileNaming.isPlainLyricsFile(it.name) }
                .filter { indexedByFileName.containsKey(it.name.orEmpty()) }
            (rootItems + managedItems).map { file ->
                val entry = indexedByFileName[file.name.orEmpty()]
                LyricsStorage.LocalLyricsItem(
                    name = file.name.orEmpty(),
                    modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
                    sizeBytes = file.length(),
                    title = entry?.title.orEmpty(),
                    artist = entry?.artist.orEmpty(),
                    source = entry?.plainSource ?: LyricsStorage.SOURCE_LEGACY,
                    provider = entry?.plainProvider ?: "local",
                    hasPlainLyrics = true,
                    hasWordByWordLyrics = entry?.wordByWordFile?.isNotBlank() == true
                )
            }
        } else {
            val root = LyricsStoragePaths.fallbackLyricsDir(context)
            val managedDir = File(root, MANAGED_LYRICS_DIR)
            val rootItems = root.listFiles().orEmpty()
                .filter { it.isFile && LyricsFileNaming.isPlainLyricsFile(it.name) }
            val managedItems = managedDir.listFiles().orEmpty()
                .filter { it.isFile && LyricsFileNaming.isPlainLyricsFile(it.name) }
                .filter { indexedByFileName.containsKey(it.name) }
            (rootItems + managedItems).map { file ->
                val entry = indexedByFileName[file.name]
                LyricsStorage.LocalLyricsItem(
                    name = file.name,
                    modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
                    sizeBytes = file.length(),
                    title = entry?.title.orEmpty(),
                    artist = entry?.artist.orEmpty(),
                    source = entry?.plainSource ?: LyricsStorage.SOURCE_LEGACY,
                    provider = entry?.plainProvider ?: "local",
                    hasPlainLyrics = true,
                    hasWordByWordLyrics = entry?.wordByWordFile?.isNotBlank() == true
                )
            }
        }

        val indexedOnlyWordByWordItems = indexEntries
            .filter { it.plainFile.isBlank() && it.wordByWordFile.isNotBlank() }
            .map { entry ->
                LyricsStorage.LocalLyricsItem(
                    name = entry.wordByWordFile.substringAfterLast('/'),
                    modifiedTimeMillis = entry.updatedAt,
                    sizeBytes = 0L,
                    title = entry.title,
                    artist = entry.artist,
                    source = entry.plainSource,
                    provider = entry.plainProvider,
                    hasPlainLyrics = false,
                    hasWordByWordLyrics = true
                )
            }

        return (items + indexedOnlyWordByWordItems)
            .distinctBy { item -> item.title.lowercase().trim() + "|" + item.artist.lowercase().trim() + "|" + item.name }
            .sortedByDescending { it.modifiedTimeMillis }
            .take(limit.coerceAtLeast(1))
    }
}
