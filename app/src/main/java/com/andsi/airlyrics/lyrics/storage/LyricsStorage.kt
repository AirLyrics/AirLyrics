package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.WordByWordLine

/**
 * Public facade for local lyric persistence.
 *
 * Keep external callers here, but keep implementation details in focused helpers:
 * paths, files, index matching, song identity, listing, editing, and word-by-word codecs.
 */
object LyricsStorage {
    private val storageLock = Any()

    internal fun <T> withStorageLock(block: () -> T): T = synchronized(storageLock) { block() }

    const val SOURCE_MANUAL_IMPORT = "manual_import"
    const val SOURCE_DOWNLOADED = "downloaded"
    // Persisted compatibility contract. Do not change the serialized value.
    const val SOURCE_WORD_BY_WORD_FALLBACK = "karaoke_fallback"
    const val SOURCE_LEGACY = "legacy"

    enum class DeleteMode { PLAIN, WORD_BY_WORD, ALL }

    enum class DeleteAllSavedLyricsResult { DELETED, NOTHING_TO_DELETE, FAILED }

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
        val source: String = SOURCE_LEGACY,
        val provider: String = "local",
        val hasPlainLyrics: Boolean = true,
        val hasWordByWordLyrics: Boolean = false
    ) {
        val displayTitle: String
            get() = title.ifBlank { LyricsFileNaming.friendlyDisplayName(name) }
    }

    data class LocalPlainLyricsInfo(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val plainFileName: String,
        val plainSource: String,
        val plainProvider: String,
        val updatedAt: Long
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
        LocalLyricsLister.listRecent(context, limit)
    }

    fun readLocalLyricsItemText(
        context: Context,
        item: LocalLyricsItem,
        target: LocalLyricsEditTarget
    ): String? = withStorageLock {
        LocalLyricsEditor.readItemText(context, item, target)
    }

    fun updatePlainLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        plainLrc: String
    ): LocalLyricsUpdateResult = LocalLyricsEditor.updatePlainItemTextWithResult(context, item, plainLrc)

    fun validateWordByWordLyricsItemText(wordByWordLrc: String): LocalLyricsUpdateResult {
        return LocalLyricsEditor.validateWordByWordItemText(wordByWordLrc)
    }

    fun updateWordByWordLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        wordByWordLrc: String
    ): LocalLyricsUpdateResult = LocalLyricsEditor.updateWordByWordItemTextWithResult(context, item, wordByWordLrc)

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
        PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, title, artist, duration)
    }

    fun hasPlainLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        return readPlainLyrics(context, title, artist, duration) != null
    }

    fun readPlainLyrics(context: Context, title: String, artist: String, duration: Long): String? = withStorageLock {
        PlainLyricsStorageOps.readPlainLyrics(context, title, artist, duration)
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
        overwrite: Boolean = true
    ): Boolean = withStorageLock {
        PlainLyricsStorageOps.savePlainLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            plainLrc = plainLrc,
            album = album,
            plainSource = plainSource,
            plainProvider = plainProvider,
            overwrite = overwrite
        )
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
        )
    }

    fun hasWordByWordLyrics(context: Context, title: String, artist: String, duration: Long): Boolean = withStorageLock {
        WordByWordLyricsStorageOps.hasWordByWordLyrics(context, title, artist, duration)
    }

    fun readWordByWordLyrics(context: Context, title: String, artist: String, duration: Long): List<WordByWordLine> = withStorageLock {
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
        WordByWordLyricsStorageOps.saveWordByWordLyrics(
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
        )
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
        )
    }

    fun deleteLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        mode: DeleteMode = DeleteMode.ALL
    ): Boolean = withStorageLock {
        LocalLyricsDeleteOps.deleteLocalLyrics(context, title, artist, duration, mode)
    }

    fun deleteAllSavedLyrics(context: Context): DeleteAllSavedLyricsResult = withStorageLock {
        LocalLyricsDeleteOps.deleteAllSavedLyrics(context)
    }
}
