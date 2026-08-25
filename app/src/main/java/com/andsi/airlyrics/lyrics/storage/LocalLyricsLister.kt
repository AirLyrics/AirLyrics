package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LocalLyricsLister {
    fun listRecent(context: Context, limit: Int): List<LyricsStorage.LocalLyricsItem> {
        return listAll(context).take(limit.coerceAtLeast(1))
    }

    fun listAll(context: Context): List<LyricsStorage.LocalLyricsItem> {
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
            rootItems.map { file -> documentItem(context, file, entry = null) } +
                managedItems.map { file ->
                    documentItem(context, file, indexedByFileName[file.name.orEmpty()])
                }
        } else {
            val root = LyricsStoragePaths.fallbackLyricsDir(context)
            val managedDir = File(root, MANAGED_LYRICS_DIR)
            val rootItems = root.listFiles().orEmpty()
                .filter { it.isFile && LyricsFileNaming.isPlainLyricsFile(it.name) }
            val managedItems = managedDir.listFiles().orEmpty()
                .filter { it.isFile && LyricsFileNaming.isPlainLyricsFile(it.name) }
                .filter { indexedByFileName.containsKey(it.name) }
            rootItems.map { file -> fileItem(file, entry = null) } +
                managedItems.map { file -> fileItem(file, indexedByFileName[file.name]) }
        }

        val listedIndexKeys = items.mapTo(mutableSetOf()) { it.indexKey }
        val indexedOnlyWordByWordItems = indexEntries
            .filter {
                it.wordByWordFile.isNotBlank() &&
                    it.key !in listedIndexKeys
            }
            .map { entry ->
                LyricsStorage.LocalLyricsItem(
                    name = entry.wordByWordFile.substringAfterLast('/'),
                    modifiedTimeMillis = entry.updatedAt,
                    sizeBytes = LyricsFileStore.storedLyricsFileSize(context, entry.wordByWordFile),
                    title = entry.title,
                    artist = entry.artist,
                    album = entry.album,
                    durationMs = entry.durationMs,
                    indexKey = entry.key,
                    source = entry.plainSource,
                    provider = entry.plainProvider,
                    hasPlainLyrics = false,
                    hasWordByWordLyrics = true
                )
            }

        return (items + indexedOnlyWordByWordItems)
            .distinctBy { item -> item.indexKey.ifBlank { "root:${item.name}" } }
            .sortedByDescending { it.modifiedTimeMillis }
    }

    private fun documentItem(
        context: Context,
        file: DocumentFile,
        entry: LyricsIndexEntry?
    ): LyricsStorage.LocalLyricsItem {
        return LyricsStorage.LocalLyricsItem(
            name = file.name.orEmpty(),
            modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
            sizeBytes = LyricsFileStore.documentFileSize(context, file),
            title = entry?.title.orEmpty(),
            artist = entry?.artist.orEmpty(),
            album = entry?.album.orEmpty(),
            durationMs = entry?.durationMs ?: 0L,
            indexKey = entry?.key.orEmpty(),
            source = entry?.plainSource ?: LyricsStorage.SOURCE_LEGACY,
            provider = entry?.plainProvider ?: "local",
            hasPlainLyrics = true,
            hasWordByWordLyrics = entry?.wordByWordFile?.isNotBlank() == true
        )
    }

    private fun fileItem(
        file: File,
        entry: LyricsIndexEntry?
    ): LyricsStorage.LocalLyricsItem {
        return LyricsStorage.LocalLyricsItem(
            name = file.name,
            modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
            sizeBytes = file.length(),
            title = entry?.title.orEmpty(),
            artist = entry?.artist.orEmpty(),
            album = entry?.album.orEmpty(),
            durationMs = entry?.durationMs ?: 0L,
            indexKey = entry?.key.orEmpty(),
            source = entry?.plainSource ?: LyricsStorage.SOURCE_LEGACY,
            provider = entry?.plainProvider ?: "local",
            hasPlainLyrics = true,
            hasWordByWordLyrics = entry?.wordByWordFile?.isNotBlank() == true
        )
    }
}
