package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.util.AtomicFile
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.lyrics.parser.LrcParser
import java.io.File
import org.json.JSONObject

/** A durable reference to the last successful manual choice, never a second copy of lyrics. */
internal object ManualLyricsSelectionStore {
    data class Selection(val result: LyricsProviderResult, val info: LyricsStorage.LocalPlainLyricsInfo)

    private fun file(context: Context, song: SongIdentity) = AtomicFile(
        File(File(context.noBackupFilesDir, "lyrics-selections"), song.copy(durationMs = 0L).storageKey() + ".json")
    )

    private fun snapshot(file: AtomicFile): ByteArray? =
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.readFully() else null

    private fun write(file: AtomicFile, bytes: ByteArray?) {
        if (bytes == null) { file.delete(); return }
        check(file.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    fun read(context: Context, song: SongIdentity): Selection? = LyricsStorage.withStorageLock {
        val selectionFile = file(context, song)
        val bytes = runCatching { snapshot(selectionFile) }.getOrNull() ?: return@withStorageLock null
        val selected = runCatching {
            val doc = JSONObject(String(bytes, Charsets.UTF_8))
            if (doc.getString("directory") != LyricsStoragePaths.getLyricsDirRawPath(context)) return@runCatching null
            val local = PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, song.title, song.artist, song.durationMs)
            if (local != null && local.plainSource in setOf(LyricsStorage.SOURCE_MANUAL_IMPORT, LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK)
                && local.updatedAt > doc.getLong("selectedAt")) return@runCatching null
            if (doc.getString("kind") == "spicy") {
                val cache = SpicyLyricsCache.forContext(context)
                val key = doc.getString("key")
                val result = cache.read(key) ?: return@runCatching null
                val item = cache.item(key) ?: return@runCatching null
                val info = LyricsStorage.LocalPlainLyricsInfo(item.title, item.artist, item.durationMs,
                    item.name, item.source, item.provider, item.modifiedTimeMillis, item.indexKey, item.album)
                Selection(result.copy(manualLocal = true, selectedManually = true), info)
            } else {
                if (local == null || local.indexKey != doc.getString("key") || local.plainProvider != doc.getString("provider")) return@runCatching null
                val plain = PlainLyricsStorageOps.readPlainLyrics(context, song.title, song.artist, song.durationMs) ?: return@runCatching null
                Selection(LyricsProviderResult("local", "Local lyrics", plain,
                    matchedTitle = song.title, matchedArtist = song.artist, matchedAlbum = song.album,
                    matchedDurationMs = song.durationMs, manualLocal = true, selectedManually = true), local)
            }
        }.getOrNull()
        if (selected == null) {
            selectionFile.delete()
            return@withStorageLock null
        }
        // A manual choice keeps its word timing across reloads: the timed file was saved for
        // this same song when the choice was made.
        if (selected.result.wordByWordLines.isEmpty()) {
            val words = LyricsStorage.readWordByWordLyrics(
                context = context,
                title = song.title,
                artist = song.artist,
                duration = song.durationMs
            )
            if (words.isNotEmpty()) {
                return@withStorageLock Selection(
                    selected.result.copy(wordByWordLines = words),
                    selected.info
                )
            }
        }
        selected
    }

    fun forget(context: Context, song: SongIdentity, itemKey: String? = null) = LyricsStorage.withStorageLock {
        val selectionFile = file(context, song)
        if (itemKey != null) {
            val doc = runCatching { JSONObject(String(selectionFile.readFully(), Charsets.UTF_8)) }.getOrNull()
                ?: return@withStorageLock
            val selectedKey = (if (doc.optString("kind") == "spicy") SpicyLyricsCache.PREFIX else "") + doc.optString("key")
            if (selectedKey != itemKey) return@withStorageLock
        }
        selectionFile.delete()
    }

    /** Called only while the coordinator holds the winning request's commit lease. */
    fun commit(context: Context, song: SongIdentity, mode: LyricsSearchMode,
        result: LyricsProviderResult, manual: Boolean, savePlain: Boolean): LyricsProviderResult = LyricsStorage.withStorageLock {
        if (result.access == null && !savePlain && !manual) return@withStorageLock result
        // Imported word tracks keep the existing automatic-search protection.
        if (!manual && result.access == null && LyricsStorage.hasWordByWordLyrics(context, song.title, song.artist, song.durationMs)) return@withStorageLock result
        val selectionFile = file(context, song)
        val previousSelection = if (manual) snapshot(selectionFile) else null
        val doc = JSONObject().put("directory", LyricsStoragePaths.getLyricsDirRawPath(context))
            .put("mode", mode.name).put("selectedAt", System.currentTimeMillis())
        val cache = SpicyLyricsCache.forContext(context)
        val cacheKey = result.access?.cacheKey
        val pending = result.pendingSpicyLyrics
        val previousCache = if (cacheKey != null && pending != null) cache.snapshot(cacheKey) else null
        val fileName = LyricsFileNaming.managedPlainFileName(song)
        val relativeFile = LyricsFileNaming.managedRelativePath(fileName)
        val index = if (cacheKey == null) LyricsIndexStore.captureRaw(context) else null
        check(index !is LyricsIndexStore.RawSnapshot.Unreadable) { "Cannot snapshot lyrics index" }
        val oldPlain = if (cacheKey == null) LyricsFileStore.readManagedLyrics(context, relativeFile) else null
        check(cacheKey != null || oldPlain != null || LyricsFileStore.storedLyricsFileSize(context, relativeFile) == 0L) { "Cannot snapshot lyrics file" }
        try {
            val saved = if (cacheKey != null) {
                val savedResult = if (pending != null) {
                    checkNotNull(cache.save(cacheKey, song.title, song.artist, song.album, song.durationMs,
                        pending.raw, pending.searchKey, pending.fetchedAtMs))
                } else checkNotNull(cache.read(cacheKey)) { "Cached lyrics expired before save" }
                doc.put("kind", "spicy").put("key", cacheKey)
                savedResult
            } else {
                check(PlainLyricsStorageOps.savePlainLyrics(context, song.title, song.artist, song.durationMs,
                    LrcParser.mergeOriginalAndTranslationForStorage(result.plainLrc, result.translatedLrc),
                    song.album, LyricsStorage.SOURCE_DOWNLOADED, result.plainProviderId, overwrite = true,
                    matchedTitle = result.matchedTitle, matchedArtist = result.matchedArtist,
                    matchedDurationMs = result.matchedDurationMs)) { "Lyrics save failed" }
                SongAliasStore.rememberCatalogueAlias(
                    context, song, result.matchedTitle, result.matchedArtist
                )
                val info = checkNotNull(PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, song.title, song.artist, song.durationMs))
                doc.put("kind", "plain").put("key", info.indexKey).put("provider", info.plainProvider)
                result
            }
            if (manual) write(selectionFile, doc.toString().toByteArray(Charsets.UTF_8))
            LyricsStorage.markContentChanged()
            saved.copy(pendingSpicyLyrics = null, manualLocal = manual || saved.manualLocal, selectedManually = manual)
        } catch (error: Exception) {
            runCatching {
                if (cacheKey != null && pending != null) cache.restore(cacheKey, previousCache)
                if (index != null) {
                    if (oldPlain == null) LyricsFileStore.deleteManagedLyrics(context, relativeFile)
                    else check(LyricsFileStore.writeManagedLyrics(context, fileName, oldPlain))
                    check(LyricsIndexStore.restoreRaw(context, index))
                }
                if (manual) write(selectionFile, previousSelection)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
