package com.andsi.airlyrics.lyrics.storage

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

import com.andsi.airlyrics.core.model.SongIdentity
import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.WordByWordLine
import java.util.concurrent.atomic.AtomicLong

/**
 * Public facade for local lyric persistence.
 *
 * Keep external callers here, but keep implementation details in focused helpers:
 * paths, files, index matching, song identity, listing, editing, and word-by-word codecs.
 */
object LyricsStorage {
    internal val processGeneration: String = java.util.UUID.randomUUID().toString()
    private val storageLock = ReentrantLock()
    private val contentRevision = AtomicLong(0L)

    internal fun <T> withStorageLock(block: () -> T): T = storageLock.withLock { block() }

    internal fun isWaitingForStorage(thread: Thread): Boolean = storageLock.hasQueuedThread(thread)

    /** Process-local signal used to reconcile visible UI after local lyrics mutations. */
    internal fun currentRevision(): Long = contentRevision.get()

    internal fun markContentChanged() {
        contentRevision.incrementAndGet()
    }

    internal fun replaceSavedLyrics(
        context: Context,
        item: LocalLyricsItem,
        document: com.andsi.airlyrics.lyrics.importer.BatchLyricsDocument
    ): LyricsReplacementOps.Result = LyricsReplacementOps.replace(context, item, document)

    const val SOURCE_PROVIDER_CACHE = "provider_cache"
    const val SOURCE_MANUAL_IMPORT = "manual_import"
    const val SOURCE_MANUAL_EDIT = "manual_edit"
    const val SOURCE_DOWNLOADED = "downloaded"
    // Persisted compatibility contract. Do not change the serialized value.
    const val SOURCE_WORD_BY_WORD_FALLBACK = "karaoke_fallback"
    const val SOURCE_LEGACY = "legacy"

    enum class DeleteMode { PLAIN, WORD_BY_WORD, ALL }

    enum class DeleteAllSavedLyricsResult { DELETED, NOTHING_TO_DELETE, FAILED }

    sealed class DeleteLocalLyricsItemResult {
        data class Deleted(val target: SongIdentity?) : DeleteLocalLyricsItemResult()
        object NotFound : DeleteLocalLyricsItemResult()
        object Failed : DeleteLocalLyricsItemResult()
    }

    enum class LocalLyricsEditTarget { PLAIN, WORD_BY_WORD }

    sealed class ImportLyricsResult {
        object Saved : ImportLyricsResult()
        object TooLarge : ImportLyricsResult()
        object PlainLyricsAlreadyExists : ImportLyricsResult()
        object WordByWordLyricsAlreadyExists : ImportLyricsResult()
        data class InvalidFormat(
            val invalidLineNumbers: List<Int> = emptyList()
        ) : ImportLyricsResult()
        object ReadFailed : ImportLyricsResult()
        object SaveFailed : ImportLyricsResult()
        object SnapshotFailed : ImportLyricsResult()
        data class RollbackFailed(
            val originalFailureStep: WordByWordImportFailureStep,
            val originalFailureCause: WordByWordImportFailureCause,
            val failedRollbackSteps: List<WordByWordRollbackFailureStep>
        ) : ImportLyricsResult()
    }

    enum class WordByWordImportFailureStep {
        WORD_BY_WORD_FILE_WRITE,
        PLAIN_FALLBACK_FILE_WRITE,
        INDEX_WRITE
    }

    enum class WordByWordImportFailureCause {
        IO_OPERATION_RETURNED_FALSE
    }

    enum class WordByWordRollbackFailureStep {
        RESTORE_INDEX,
        RESTORE_PLAIN_FILE,
        RESTORE_WORD_BY_WORD_FILE
    }

    data class LocalLyricsItem(
        val name: String,
        val modifiedTimeMillis: Long,
        val sizeBytes: Long,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
        val indexKey: String = "",
        val source: String = SOURCE_LEGACY,
        val provider: String = "local",
        val hasPlainLyrics: Boolean = true,
        val hasWordByWordLyrics: Boolean = false
    ) {
        val displayTitle: String
            get() = title.ifBlank { LyricsFileNaming.friendlyDisplayName(name) }

        val canDelete: Boolean
            get() = indexKey.isNotBlank() || LyricsFileNaming.isSafePlainLyricsBaseName(name)
    }

    data class LocalPlainLyricsInfo(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val plainFileName: String,
        val plainSource: String,
        val plainProvider: String,
        val updatedAt: Long,
        val indexKey: String = "",
        val album: String = "",
        val matchedTitle: String = "",
        val matchedArtist: String = "",
        val matchedDurationMs: Long = 0L
    ) {
        val friendlyTitle: String
            get() = if (title.isNotBlank()) {
                if (artist.isBlank()) title else "$title - $artist"
            } else {
                plainFileName
            }
    }

    data class LocalLyricsUpdateResult(
        val saved: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun listRecentLyrics(context: Context, limit: Int = 8): List<LocalLyricsItem> = withStorageLock {
        (LocalLyricsLister.listRecent(context, limit) + SpicyLyricsCache.forContext(context).list()).sortedByDescending { it.modifiedTimeMillis }.take(limit)
    }

    fun listAllLyrics(context: Context): List<LocalLyricsItem> = withStorageLock {
        (LocalLyricsLister.listAll(context) + SpicyLyricsCache.forContext(context).list()).sortedByDescending { it.modifiedTimeMillis }
    }

    internal fun localLyricsFileSize(context: Context, relativeFile: String): Long = withStorageLock {
        if (relativeFile.startsWith(SpicyLyricsCache.PREFIX)) {
            SpicyLyricsCache.forContext(context)
                .item(relativeFile.removePrefix(SpicyLyricsCache.PREFIX))
                ?.sizeBytes ?: 0L
        } else {
            LyricsFileStore.storedLyricsFileSize(context, relativeFile)
        }
    }

    fun readLocalLyricsItemText(
        context: Context,
        item: LocalLyricsItem,
        target: LocalLyricsEditTarget
    ): String? = withStorageLock {
        if (item.indexKey.startsWith(SpicyLyricsCache.PREFIX)) {
            val result = SpicyLyricsCache.forContext(context).read(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX)) ?: return@withStorageLock null
            if (target == LocalLyricsEditTarget.WORD_BY_WORD) {
                com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser.wordByWordLinesToWordByWordLrc(result.wordByWordLines)
            } else if (result.wordByWordLines.isEmpty()) result.plainLrc else null
        } else LocalLyricsEditor.readItemText(context, item, target)
    }

    fun updatePlainLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        plainLrc: String
    ): LocalLyricsUpdateResult {
        if (item.indexKey.startsWith(SpicyLyricsCache.PREFIX)) return SpicyLyricsCache.forContext(context)
            .edit(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX), plainLrc, false)
        return LocalLyricsEditor.updatePlainItemTextWithResult(context, item, plainLrc).also { result ->
            if (result.saved) markContentChanged()
        }
    }

    fun validateWordByWordLyricsItemText(wordByWordLrc: String): LocalLyricsUpdateResult {
        return LocalLyricsEditor.validateWordByWordItemText(wordByWordLrc)
    }

    fun updateWordByWordLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        wordByWordLrc: String
    ): LocalLyricsUpdateResult {
        if (item.indexKey.startsWith(SpicyLyricsCache.PREFIX)) return SpicyLyricsCache.forContext(context)
            .edit(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX), wordByWordLrc, true)
        return LocalLyricsEditor.updateWordByWordItemTextWithResult(context, item, wordByWordLrc)
            .also { result ->
                if (result.saved) markContentChanged()
            }
    }

    fun saveLyricsDirUri(context: Context, uri: Uri) = withStorageLock {
        LyricsStoragePaths.saveLyricsDirUri(context, uri)
    }

    fun validateLyricsDir(context: Context, uri: Uri): Boolean = LyricsStoragePaths.validateLyricsDir(context, uri)

    fun getLyricsDirRawPath(context: Context): String = LyricsStoragePaths.getLyricsDirRawPath(context)

    fun getLocalPlainLyricsInfo(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LocalPlainLyricsInfo? = withStorageLock {
        ManualLyricsSelectionStore.read(context, SongIdentity(title, artist, durationMs = duration))?.info
            ?: PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, title, artist, duration)
            ?: SpicyLyricsCache.forContext(context).item(SpicyLyricsCache.key(title, artist, duration))
                ?.let { LocalPlainLyricsInfo(it.title, it.artist, it.durationMs, it.name, it.source, it.provider, it.modifiedTimeMillis, it.indexKey, it.album) }
    }

    /** Metadata for the same word upgrade chosen by the repository, without accessing the network. */
    fun preferredOnlineWordItem(context: Context, title: String, artist: String, duration: Long): LocalLyricsItem? = withStorageLock {
        if (ManualLyricsSelectionStore.read(context, SongIdentity(title, artist, durationMs = duration)) != null) return@withStorageLock null
        val local = PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, title, artist, duration)
        if (local != null && (local.plainSource != SOURCE_DOWNLOADED || WordByWordLyricsStorageOps.hasWordByWordLyrics(context, title, artist, duration))) return@withStorageLock null
        SpicyLyricsCache.forContext(context).item(SpicyLyricsCache.key(title, artist, duration))?.takeIf { it.hasWordByWordLyrics }
    }

    fun hasPlainLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        return readPlainLyrics(context, title, artist, duration) != null
    }

    fun readPlainLyrics(context: Context, title: String, artist: String, duration: Long): String? = withStorageLock {
        ManualLyricsSelectionStore.read(context, SongIdentity(title, artist, durationMs = duration))?.result?.plainLrc
            ?: PlainLyricsStorageOps.readPlainLyrics(context, title, artist, duration)
            ?: SpicyLyricsCache.forContext(context).read(SpicyLyricsCache.key(title, artist, duration))?.plainLrc
    }

    fun savePlainLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        plainLrc: String,
        album: String = "",
        plainSource: String = SOURCE_DOWNLOADED,
        plainProvider: String = "local",
        overwrite: Boolean = true,
        matchedTitle: String = "",
        matchedArtist: String = "",
        matchedDurationMs: Long = 0L
    ): Boolean = withStorageLock {
        if (plainProvider == "spicy") return@withStorageLock false
        val saved = PlainLyricsStorageOps.savePlainLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            plainLrc = plainLrc,
            album = album,
            plainSource = plainSource,
            plainProvider = plainProvider,
            overwrite = overwrite,
            matchedTitle = matchedTitle,
            matchedArtist = matchedArtist,
            matchedDurationMs = matchedDurationMs
        )
        if (saved) markContentChanged()
        saved
    }

    fun importPlainLyricsFromUri(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): Boolean = importPlainLyricsFromUriWithResult(
        context = context,
        uri = uri,
        title = title,
        artist = artist,
        duration = duration,
        album = album,
        overwrite = overwrite
    ) is ImportLyricsResult.Saved

    fun importPlainLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): ImportLyricsResult {
        return PlainLyricsStorageOps.importPlainLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            overwrite = overwrite
        ).also { result ->
            if (result is ImportLyricsResult.Saved || result is ImportLyricsResult.RollbackFailed) {
                if (result is ImportLyricsResult.Saved) {
                    ManualLyricsSelectionStore.forget(context, SongIdentity(title, artist, album, duration))
                }
                markContentChanged()
            }
        }
    }

    fun hasWordByWordLyrics(context: Context, title: String, artist: String, duration: Long): Boolean = withStorageLock {
        ManualLyricsSelectionStore.read(context, SongIdentity(title, artist, durationMs = duration))?.let {
            return@withStorageLock it.result.wordByWordLines.isNotEmpty()
        }
        WordByWordLyricsStorageOps.hasWordByWordLyrics(context, title, artist, duration) ||
            (PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, title, artist, duration) == null &&
            SpicyLyricsCache.forContext(context).read(SpicyLyricsCache.key(title, artist, duration))?.wordByWordLines?.isNotEmpty() == true)
    }

    fun readWordByWordLyrics(context: Context, title: String, artist: String, duration: Long): List<WordByWordLine> = withStorageLock {
        ManualLyricsSelectionStore.read(context, SongIdentity(title, artist, durationMs = duration))?.let {
            return@withStorageLock it.result.wordByWordLines
        }
        WordByWordLyricsStorageOps.readWordByWordLyrics(context, title, artist, duration)
    }

    fun saveWordByWordLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        wordByWordLines: List<WordByWordLine>,
        album: String = "",
        wordByWordSource: String = SOURCE_DOWNLOADED,
        wordByWordProvider: String = "local",
        overwrite: Boolean = true,
        metadataLines: List<String> = emptyList()
    ): Boolean = withStorageLock {
        if (wordByWordProvider == "spicy") return@withStorageLock false
        val saved = WordByWordLyricsStorageOps.saveWordByWordLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            wordByWordLines = wordByWordLines,
            album = album,
            wordByWordSource = wordByWordSource,
            wordByWordProvider = wordByWordProvider,
            overwrite = overwrite,
            metadataLines = metadataLines
        )
        if (saved) markContentChanged()
        saved
    }

    fun importWordByWordLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): ImportLyricsResult {
        return WordByWordLyricsStorageOps.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            overwrite = overwrite,
            managedLyricsIo = AndroidManagedLyricsIo,
            indexIo = AndroidLyricsIndexIo
        ).also { result ->
            if (result is ImportLyricsResult.Saved || result is ImportLyricsResult.RollbackFailed) {
                if (result is ImportLyricsResult.Saved) {
                    ManualLyricsSelectionStore.forget(context, SongIdentity(title, artist, album, duration))
                }
                markContentChanged()
            }
        }
    }

    internal fun importWordByWordLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true,
        managedLyricsIo: ManagedLyricsIo,
        indexIo: LyricsIndexIo = AndroidLyricsIndexIo
    ): ImportLyricsResult {
        return WordByWordLyricsStorageOps.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            overwrite = overwrite,
            managedLyricsIo = managedLyricsIo,
            indexIo = indexIo
        ).also { result ->
            if (result is ImportLyricsResult.Saved || result is ImportLyricsResult.RollbackFailed) {
                if (result is ImportLyricsResult.Saved) {
                    ManualLyricsSelectionStore.forget(context, SongIdentity(title, artist, album, duration))
                }
                markContentChanged()
            }
        }
    }

    /** Stored resources, independent of playback selection and display settings. */
    data class DeletableLyrics(val plain: Boolean = false, val wordByWord: Boolean = false) {
        val all: Boolean get() = plain && wordByWord
    }

    fun deletableLyrics(context: Context, title: String, artist: String, duration: Long): DeletableLyrics = withStorageLock {
        val song = SongIdentity(title, artist, durationMs = duration)
        val entries = LyricsIndexStore.read(context).filter { it.isSameSong(song) }
        val cached = SpicyLyricsCache.forContext(context).read(SpicyLyricsCache.key(title, artist, duration))
        val cachedWords = cached?.wordByWordLines?.isNotEmpty() == true
        DeletableLyrics(
            plain = (cached != null && !cachedWords) || entries.any {
                it.plainSource != SOURCE_WORD_BY_WORD_FALLBACK && it.plainFile.isNotBlank() &&
                    LyricsFileStore.managedLyricsExists(context, it.plainFile)
            } || LyricsFileStore.readLegacyPlainLyrics(context, title, artist, duration) != null,
            wordByWord = cachedWords || entries.any {
                it.wordByWordFile.isNotBlank() && LyricsFileStore.managedLyricsExists(context, it.wordByWordFile)
            }
        )
    }

    fun deleteLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        mode: DeleteMode = DeleteMode.ALL
    ): Boolean = withStorageLock {
        val cache = SpicyLyricsCache.forContext(context)
        val key = SpicyLyricsCache.key(title, artist, duration)
        val payload = cache.read(key)
        val cacheMode = if (payload?.wordByWordLines?.isNotEmpty() == true) DeleteMode.WORD_BY_WORD else DeleteMode.PLAIN
        val cached = (mode == DeleteMode.ALL || (payload != null && mode == cacheMode)) && cache.delete(key)
        val deleted = LocalLyricsDeleteOps.deleteLocalLyrics(context, title, artist, duration, mode) || cached
        if (deleted) {
            // Reading invalidates only a selection whose underlying resource was removed.
            ManualLyricsSelectionStore.read(context, SongIdentity(title, artist, durationMs = duration))
            markContentChanged()
        }
        deleted
    }

    fun deleteLocalLyricsItem(
        context: Context,
        item: LocalLyricsItem
    ): DeleteLocalLyricsItemResult = withStorageLock {
        if (item.indexKey.startsWith(SpicyLyricsCache.PREFIX)) {
            return@withStorageLock if (SpicyLyricsCache.forContext(context).delete(item.indexKey.removePrefix(SpicyLyricsCache.PREFIX))) {
                val song = SongIdentity(item.title, item.artist, item.album, item.durationMs)
                ManualLyricsSelectionStore.forget(context, song, item.indexKey)
                DeleteLocalLyricsItemResult.Deleted(song)
            } else DeleteLocalLyricsItemResult.NotFound
        }
        val result = LocalLyricsDeleteOps.deleteLocalLyricsItem(context, item)
        if (result is DeleteLocalLyricsItemResult.Deleted) {
            ManualLyricsSelectionStore.forget(context, SongIdentity(item.title, item.artist, item.album, item.durationMs), item.indexKey)
        }
        if (result !is DeleteLocalLyricsItemResult.NotFound) markContentChanged()
        result
    }

    fun deleteAllSavedLyrics(context: Context): DeleteAllSavedLyricsResult = withStorageLock {
        val cache = SpicyLyricsCache.forContext(context)
        val hadCache = cache.list().isNotEmpty()
        if (!cache.clear()) return@withStorageLock DeleteAllSavedLyricsResult.FAILED
        val localResult = LocalLyricsDeleteOps.deleteAllSavedLyrics(context)
        val result = if (hadCache && localResult == DeleteAllSavedLyricsResult.NOTHING_TO_DELETE) DeleteAllSavedLyricsResult.DELETED else localResult
        if (result != DeleteAllSavedLyricsResult.NOTHING_TO_DELETE) markContentChanged()
        result
    }
    fun restrictedLyricsAccess(context: Context, itemKey: String): com.andsi.airlyrics.lyrics.LyricsAccess? =
        if (itemKey.startsWith(SpicyLyricsCache.PREFIX)) SpicyLyricsCache.forContext(context).read(itemKey.removePrefix(SpicyLyricsCache.PREFIX))?.access else null

    fun pruneOnlineCache(context: Context) = SpicyLyricsCache.forContext(context).prune()

    fun scheduleCacheMaintenance(context: Context) {
        val app = context.applicationContext
        Thread({
            runCatching { pruneOnlineCache(app) }
        }, "AirLyrics-CacheCleanup").apply { isDaemon = true }.start()
        val scheduler = app.getSystemService(android.app.job.JobScheduler::class.java)
        val job = android.app.job.JobInfo.Builder(7301, android.content.ComponentName(app, SpicyCacheCleanupService::class.java))
            .setPeriodic(24 * 60 * 60 * 1000L).build()
        runCatching { scheduler?.schedule(job) }
    }

}
