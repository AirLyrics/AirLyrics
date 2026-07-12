package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.KaraokeLine

/**
 * Public facade for local lyric persistence.
 *
 * Keep external callers here, but keep implementation details in focused helpers:
 * paths, files, index matching, song identity, listing, editing, and karaoke codecs.
 */
object LyricsStorage {
    private val storageLock = Any()

    internal fun <T> withStorageLock(block: () -> T): T = synchronized(storageLock) { block() }

    const val SOURCE_MANUAL_IMPORT = "manual_import"
    const val SOURCE_DOWNLOADED = "downloaded"
    const val SOURCE_KARAOKE_FALLBACK = "karaoke_fallback"
    const val SOURCE_LEGACY = "legacy"

    enum class DeleteMode { PLAIN, KARAOKE, ALL }

    enum class LocalLyricsEditTarget { PLAIN, KARAOKE }

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
        val hasKaraokeLyrics: Boolean = false
    ) {
        val displayTitle: String
            get() = title.ifBlank { friendlyNameFromFileName(name) }

        private fun friendlyNameFromFileName(fileName: String): String {
            return fileName
                .substringAfterLast('/')
                .removeSuffix(".karaoke.json")
                .removeSuffix(".lrc")
                .replace(Regex("\\s*\\[[0-9a-fA-F]{8}]$"), "")
                .replace('_', ' ')
                .trim()
                .ifBlank { fileName }
        }
    }

    data class LocalLyricsInfo(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val fileName: String,
        val source: String,
        val provider: String,
        val updatedAt: Long
    ) {
        val friendlyTitle: String
            get() = if (title.isNotBlank()) {
                if (artist.isBlank()) title else "$title - $artist"
            } else {
                fileName
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

    fun updateLocalLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        text: String
    ): LocalLyricsUpdateResult = LocalLyricsEditor.updatePlainItemTextWithResult(context, item, text)

    fun validateKaraokeLyricsItemText(text: String): LocalLyricsUpdateResult {
        return LocalLyricsEditor.validateKaraokeItemText(text)
    }

    fun updateKaraokeLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        text: String
    ): LocalLyricsUpdateResult = LocalLyricsEditor.updateKaraokeItemTextWithResult(context, item, text)

    fun saveLyricsDirUri(context: Context, uri: Uri) = LyricsStoragePaths.saveLyricsDirUri(context, uri)

    fun validateLyricsDir(context: Context, uri: Uri): Boolean = LyricsStoragePaths.validateLyricsDir(context, uri)

    fun getLyricsDirRawPath(context: Context): String = LyricsStoragePaths.getLyricsDirRawPath(context)

    fun getLocalLyricsInfo(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LocalLyricsInfo? = withStorageLock {
        PlainLyricsStorageOps.getLocalLyricsInfo(context, title, artist, duration)
    }

    fun hasLocalLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        return readLocalLyrics(context, title, artist, duration) != null
    }

    fun readLocalLyrics(context: Context, title: String, artist: String, duration: Long): String? = withStorageLock {
        PlainLyricsStorageOps.readLocalLyrics(context, title, artist, duration)
    }

    fun saveLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        lyrics: String,
        album: String = "",
        source: String = SOURCE_DOWNLOADED,
        provider: String = "local",
        overwrite: Boolean = true
    ): Boolean = withStorageLock {
        PlainLyricsStorageOps.saveLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            lyrics = lyrics,
            album = album,
            source = source,
            provider = provider,
            overwrite = overwrite
        )
    }

    fun importLyricsFromUri(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): Boolean = importLyricsFromUriWithResult(
        context = context,
        uri = uri,
        title = title,
        artist = artist,
        duration = duration,
        album = album,
        overwrite = overwrite
    ) is ImportLyricsResult.Saved

    fun importLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): ImportLyricsResult {
        return PlainLyricsStorageOps.importLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            overwrite = overwrite
        )
    }

    fun hasKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): Boolean = withStorageLock {
        KaraokeLyricsStorageOps.hasKaraokeLyrics(context, title, artist, duration)
    }

    fun readKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): List<KaraokeLine> = withStorageLock {
        KaraokeLyricsStorageOps.readKaraokeLyrics(context, title, artist, duration)
    }

    fun saveKaraokeLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        karaokeLines: List<KaraokeLine>,
        album: String = "",
        source: String = SOURCE_DOWNLOADED,
        provider: String = "local",
        overwrite: Boolean = true,
        metadataLines: List<String> = emptyList()
    ): Boolean = withStorageLock {
        KaraokeLyricsStorageOps.saveKaraokeLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            karaokeLines = karaokeLines,
            album = album,
            source = source,
            provider = provider,
            overwrite = overwrite,
            metadataLines = metadataLines
        )
    }

    fun importKaraokeLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): ImportLyricsResult {
        return KaraokeLyricsStorageOps.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            overwrite = overwrite
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
}
