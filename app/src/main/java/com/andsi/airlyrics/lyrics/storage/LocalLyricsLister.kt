package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalLyricsLister {
    fun listRecent(context: Context, limit: Int): List<LyricsStorage.LocalLyricsItem> {
        val indexEntries = LyricsIndexStore.read(context)
        val indexedByFileName = indexEntries.associateBy { it.file.substringAfterLast('/') }
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        val items = if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            val managedDir = root?.findFile(MANAGED_LYRICS_DIR)?.takeIf { it.isDirectory }
            val rootItems = root?.listFiles().orEmpty()
                .filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
            val managedItems = managedDir?.listFiles().orEmpty()
                .filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
            (rootItems + managedItems).map { file ->
                val entry = indexedByFileName[file.name.orEmpty()]
                LyricsStorage.LocalLyricsItem(
                    name = file.name.orEmpty(),
                    modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
                    sizeBytes = file.length(),
                    title = entry?.title.orEmpty(),
                    artist = entry?.artist.orEmpty(),
                    source = entry?.source ?: LyricsStorage.SOURCE_LEGACY,
                    provider = entry?.provider ?: "local",
                    hasPlainLyrics = true,
                    hasKaraokeLyrics = entry?.karaokeFile?.isNotBlank() == true
                )
            }
        } else {
            val root = LyricsStoragePaths.fallbackLyricsDir(context)
            val managedDir = File(root, MANAGED_LYRICS_DIR)
            val rootItems = root.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".lrc", ignoreCase = true) }
            val managedItems = managedDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".lrc", ignoreCase = true) }
            (rootItems + managedItems).map { file ->
                val entry = indexedByFileName[file.name]
                LyricsStorage.LocalLyricsItem(
                    name = file.name,
                    modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
                    sizeBytes = file.length(),
                    title = entry?.title.orEmpty(),
                    artist = entry?.artist.orEmpty(),
                    source = entry?.source ?: LyricsStorage.SOURCE_LEGACY,
                    provider = entry?.provider ?: "local",
                    hasPlainLyrics = true,
                    hasKaraokeLyrics = entry?.karaokeFile?.isNotBlank() == true
                )
            }
        }

        val indexedOnlyKaraokeItems = indexEntries
            .filter { it.file.isBlank() && it.karaokeFile.isNotBlank() }
            .map { entry ->
                LyricsStorage.LocalLyricsItem(
                    name = entry.karaokeFile.substringAfterLast('/'),
                    modifiedTimeMillis = entry.updatedAt,
                    sizeBytes = 0L,
                    title = entry.title,
                    artist = entry.artist,
                    source = entry.source,
                    provider = entry.provider,
                    hasPlainLyrics = false,
                    hasKaraokeLyrics = true
                )
            }

        return (items + indexedOnlyKaraokeItems)
            .distinctBy { item -> item.title.lowercase().trim() + "|" + item.artist.lowercase().trim() + "|" + item.name }
            .sortedByDescending { it.modifiedTimeMillis }
            .take(limit.coerceAtLeast(1))
    }
}
