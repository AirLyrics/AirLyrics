package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocument

/** A per-song transaction. Exact selected keys are used instead of the legacy weak-song lookup. */
internal object LyricsReplacementOps {
    enum class Result { SAVED, STALE, FAILED, ROLLBACK_FAILED }

    fun replace(
        context: Context,
        item: LyricsStorage.LocalLyricsItem,
        document: BatchLyricsDocument,
        io: ManagedLyricsIo = AndroidManagedLyricsIo,
        indexIo: LyricsIndexIo = AndroidLyricsIndexIo
    ): Result = LyricsStorage.withStorageLock {
        if (item.indexKey.isBlank() || item.title.isBlank() || document.plainLrc.isBlank()) return@withStorageLock Result.FAILED
        val current = LyricsStorage.listAllLyrics(context).singleOrNull { it.indexKey == item.indexKey }
        if (current != item) return@withStorageLock Result.STALE
        val song = SongIdentity(item.title, item.artist, item.album, item.durationMs)
        val entries = indexIo.read(context)
        val isCache = item.indexKey.startsWith(SpicyLyricsCache.PREFIX)
        val key = if (isCache) song.storageKey() else item.indexKey
        val existing = entries.singleOrNull { it.key == key }
        if (!isCache && existing == null) return@withStorageLock Result.STALE
        val plainPath = existing?.plainFile?.takeIf { it.isNotBlank() }
            ?: LyricsFileNaming.managedRelativePath(LyricsFileNaming.managedPlainFileName(song))
        val wordPath = existing?.wordByWordFile?.takeIf { it.isNotBlank() }
            ?: LyricsFileNaming.managedRelativePath(LyricsFileNaming.managedWordByWordFileName(song))
        // Never overwrite a path owned by another entry, including truncated-key collisions.
        if (entries.any { it.key != key && (it.plainFile in setOf(plainPath, wordPath) || it.wordByWordFile in setOf(plainPath, wordPath)) }) {
            return@withStorageLock Result.FAILED
        }
        val indexSnapshot = indexIo.captureRaw(context)
        if (indexSnapshot is LyricsIndexStore.RawSnapshot.Unreadable) return@withStorageLock Result.FAILED
        val snapshots = linkedMapOf<String, String?>()
        for (path in listOf(plainPath, wordPath)) {
            snapshots[path] = if (io.exists(context, path)) io.read(context, path) ?: return@withStorageLock Result.FAILED else null
        }
        fun rollback(): Result {
            val filesRestored = snapshots.map { (path, content) ->
                runCatching {
                    if (content == null) !io.exists(context, path) || io.delete(context, path)
                    else io.write(context, path.substringAfterLast('/'), content)
                }.getOrDefault(false)
            }.all { it }
            val indexRestored = runCatching { indexIo.restoreRaw(context, indexSnapshot) }.getOrDefault(false)
            if (!filesRestored || !indexRestored) LyricsStorage.markContentChanged()
            return if (filesRestored && indexRestored) Result.FAILED else Result.ROLLBACK_FAILED
        }
        val now = System.currentTimeMillis()
        val replacement = LyricsIndexEntry(
            key, song.title, song.artist, song.album, song.durationMs,
            plainFile = plainPath,
            wordByWordFile = if (document.words.isEmpty()) "" else wordPath,
            plainSource = if (document.words.isEmpty()) LyricsStorage.SOURCE_MANUAL_IMPORT else LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
            plainProvider = "local",
            wordByWordProvider = if (document.words.isEmpty()) "" else "local",
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val saved = runCatching {
            check(io.write(context, plainPath.substringAfterLast('/'), document.plainLrc))
            if (document.words.isNotEmpty()) {
                check(io.write(context, wordPath.substringAfterLast('/'), WordByWordLyricsJsonCodec.wordByWordLinesToJson(document.words, document.metadata)))
            } else if (io.exists(context, wordPath)) {
                check(io.delete(context, wordPath))
            }
            check(indexIo.write(context, entries.filterNot { it.key == key } + replacement))
        }.isSuccess
        if (!saved) return@withStorageLock rollback()
        // Imported lyrics are durable local content, independent of the online cache lifetime.
        ManualLyricsSelectionStore.forget(context, song)
        val cache = SpicyLyricsCache.forContext(context)
        cache.delete(SpicyLyricsCache.key(song.title, song.artist, song.durationMs))
        if (isCache) cache.delete(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX))
        LyricsStorage.markContentChanged()
        Result.SAVED
    }
}
