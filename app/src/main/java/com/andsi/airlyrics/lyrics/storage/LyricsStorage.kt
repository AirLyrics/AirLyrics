package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.parser.LrcParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Public facade for local lyric persistence.
 *
 * Keep external callers here, but keep implementation details in focused helpers:
 * paths, files, index matching, song identity, listing, and karaoke codecs.
 */
object LyricsStorage {
    const val SOURCE_MANUAL_IMPORT = "manual_import"
    const val SOURCE_DOWNLOADED = "downloaded"
    const val SOURCE_LEGACY = "legacy"

    enum class DeleteMode { PLAIN, KARAOKE, ALL }

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

        @Deprecated("UI should use Context.localizedLocalLyricsSubtitle(item) so user data is never passed through text replacement.")
        val displaySubtitle: String
            get() {
                val artistPart = artist.ifBlank { "未知歌手" }
                val sourcePart = when (source) {
                    SOURCE_MANUAL_IMPORT -> "手动导入"
                    SOURCE_DOWNLOADED -> if (provider.isBlank() || provider == "local") "本地缓存" else "本地缓存 · $provider"
                    SOURCE_LEGACY -> "本地歌词"
                    else -> "本地歌词"
                }
                return "$artistPart · $sourcePart"
            }

        @Deprecated("UI should use Context.localizedLocalLyricsType(item).")
        val lyricsTypeText: String
            get() = when {
                hasPlainLyrics && hasKaraokeLyrics -> "普通 + 逐字"
                hasKaraokeLyrics -> "逐字"
                hasPlainLyrics -> "普通"
                else -> "未知类型"
            }

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
        @Deprecated("UI should use Context.localizedLocalLyricsSource(info).")
        val sourceText: String
            get() = when (source) {
                SOURCE_MANUAL_IMPORT -> "手动导入"
                SOURCE_DOWNLOADED -> if (provider.isBlank() || provider == "local") "本地缓存" else "本地缓存 · $provider"
                SOURCE_LEGACY -> "本地歌词"
                else -> "本地歌词"
            }

        val friendlyTitle: String
            get() = if (title.isNotBlank()) {
                if (artist.isBlank()) title else "$title - $artist"
            } else {
                fileName
            }
    }

    fun listRecentLyrics(context: Context, limit: Int = 8): List<LocalLyricsItem> {
        return LocalLyricsLister.listRecent(context, limit)
    }

    @Deprecated("UI should use Context.localizedLocalLyricsMeta(item).")
    fun formatLocalLyricsItem(item: LocalLyricsItem): String {
        val dateText = if (item.modifiedTimeMillis > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.modifiedTimeMillis))
        } else {
            "未知时间"
        }

        val sizeText = when {
            item.sizeBytes >= 1024 * 1024 -> "%.1f MB".format(item.sizeBytes / 1024f / 1024f)
            item.sizeBytes >= 1024 -> "%.1f KB".format(item.sizeBytes / 1024f)
            item.sizeBytes > 0 -> "${item.sizeBytes} B"
            else -> "未知大小"
        }

        return "$dateText · $sizeText"
    }

    fun readLocalLyricsItemText(context: Context, item: LocalLyricsItem): String? {
        val entry = LyricsIndexStore.findByFileName(context, item.name)
        if (item.hasPlainLyrics && entry?.file?.isNotBlank() == true) {
            LyricsFileStore.readManagedLyrics(context, entry.file)?.let { return it }
        }
        if (!item.hasPlainLyrics && item.hasKaraokeLyrics && entry?.karaokeFile?.isNotBlank() == true) {
            LyricsFileStore.readManagedLyrics(context, entry.karaokeFile)?.let { return it }
        }
        return LyricsFileStore.readLyricsFileByName(context, item.name)
    }

    fun updateLocalLyricsItemText(context: Context, item: LocalLyricsItem, text: String): Boolean {
        if (!item.hasPlainLyrics || item.name.endsWith(".karaoke.json", ignoreCase = true)) return false
        if (!LyricsFileStore.looksLikeTimedLrc(text)) return false

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return false

        val entry = LyricsIndexStore.findByFileName(context, item.name)
        val fileName = entry?.file?.substringAfterLast('/') ?: item.name
        val saved = if (entry?.file?.isNotBlank() == true) {
            LyricsFileStore.writeManagedLyrics(context, fileName, normalizedLyrics)
        } else {
            LyricsFileStore.writeLyricsFileByName(context, item.name, normalizedLyrics)
        }
        if (!saved) return false

        if (entry != null) {
            val updated = LyricsIndexStore.read(context).map { indexed ->
                if (indexed.key == entry.key) indexed.copy(updatedAt = System.currentTimeMillis()) else indexed
            }
            LyricsIndexStore.write(context, updated)
        }
        return true
    }

    fun saveLyricsDirUri(context: Context, uri: Uri) = LyricsStoragePaths.saveLyricsDirUri(context, uri)

    fun getLyricsDirUri(context: Context): Uri? = LyricsStoragePaths.getLyricsDirUri(context)

    @Deprecated("UI should compose the save-folder label with a keyed formatter instead of translating the full path.")
    fun getLyricsDirDisplayPath(context: Context): String = LyricsStoragePaths.getLyricsDirDisplayPath(context)

    fun getLyricsDirRawPath(context: Context): String = LyricsStoragePaths.getLyricsDirRawPath(context)

    fun getLocalLyricsInfo(context: Context, title: String, artist: String, duration: Long): LocalLyricsInfo? {
        val indexed = LyricsIndexStore.find(context, title, artist, duration)
        if (indexed != null && indexed.file.isNotBlank()) {
            return LocalLyricsInfo(
                title = indexed.title,
                artist = indexed.artist,
                durationMs = indexed.durationMs,
                fileName = indexed.file.substringAfterLast('/'),
                source = indexed.source,
                provider = indexed.provider,
                updatedAt = indexed.updatedAt
            )
        }

        val legacyFileName = SongIdentity.makeLegacyFileName(title, artist, duration)
        val legacyExists = LyricsFileStore.readLegacyLyrics(context, title, artist, duration) != null

        return if (legacyExists) {
            LocalLyricsInfo(title, artist, duration, legacyFileName, SOURCE_LEGACY, "local", 0L)
        } else {
            null
        }
    }

    fun hasLocalLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        return readLocalLyrics(context, title, artist, duration) != null
    }

    fun readLocalLyrics(context: Context, title: String, artist: String, duration: Long): String? {
        LyricsIndexStore.find(context, title, artist, duration)?.let { entry ->
            if (entry.file.isNotBlank()) {
                LyricsFileStore.readManagedLyrics(context, entry.file)?.let { return it }
            }
        }
        return LyricsFileStore.readLegacyLyrics(context, title, artist, duration)
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
    ): Boolean {
        val normalizedKey = SongIdentity.makeStorageKey(title, artist, duration)
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.file?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = "${normalizedKey.take(16)}.lrc"
        val relativeFile = "$MANAGED_LYRICS_DIR/$fileName"
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, lyrics)) return false

        val entries = LyricsIndexStore.read(context)
            .filterNot { SongIdentity.isSameSong(it, title, artist, duration) || it.key == normalizedKey }
            .toMutableList()

        entries += LyricsIndexEntry(
            key = normalizedKey,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            file = relativeFile,
            karaokeFile = existing?.karaokeFile.orEmpty(),
            source = source,
            provider = provider,
            karaokeProvider = existing?.karaokeProvider.orEmpty(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        LyricsIndexStore.write(context, entries)
        return true
    }

    fun importLyricsFromUri(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): Boolean {
        val text = LyricsFileStore.readTextFromUri(context, uri) ?: return false
        if (!LyricsFileStore.looksLikeTimedLrc(text)) return false

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return false

        return saveLyrics(context, title, artist, duration, normalizedLyrics, album, SOURCE_MANUAL_IMPORT, "local", overwrite)
    }

    fun hasKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return false
        return entry.karaokeFile.isNotBlank() && LyricsFileStore.readManagedLyrics(context, entry.karaokeFile) != null
    }

    fun readKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): List<KaraokeLine> {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return emptyList()
        val rawJson = entry.karaokeFile
            .takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
            ?: return emptyList()
        return KaraokeLyricsCodec.parseJson(rawJson)
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
        overwrite: Boolean = true
    ): Boolean {
        if (karaokeLines.isEmpty()) return false

        val normalizedKey = SongIdentity.makeStorageKey(title, artist, duration)
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.karaokeFile?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = "${normalizedKey.take(16)}.karaoke.json"
        val relativeFile = "$MANAGED_LYRICS_DIR/$fileName"
        val json = KaraokeLyricsCodec.linesToJson(karaokeLines)
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, json)) return false

        val entries = LyricsIndexStore.read(context)
            .filterNot { SongIdentity.isSameSong(it, title, artist, duration) || it.key == normalizedKey }
            .toMutableList()

        entries += LyricsIndexEntry(
            key = normalizedKey,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            file = existing?.file.orEmpty(),
            karaokeFile = relativeFile,
            source = existing?.source ?: source,
            provider = existing?.provider ?: "local",
            karaokeProvider = provider,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        LyricsIndexStore.write(context, entries)
        return true
    }

    fun importKaraokeLyricsFromUri(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): Boolean {
        val text = LyricsFileStore.readTextFromUri(context, uri) ?: return false
        val lines = KaraokeLyricsCodec.parseJson(text).ifEmpty { KaraokeLyricsCodec.parseEnhancedLrc(text) }
        if (lines.isEmpty()) return false

        val savedKaraoke = saveKaraokeLyrics(context, title, artist, duration, lines, album, SOURCE_MANUAL_IMPORT, "local", overwrite)
        if (!savedKaraoke) return false

        if (!hasLocalLyrics(context, title, artist, duration)) {
            return saveLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                lyrics = KaraokeLyricsCodec.linesToPlainLrc(lines),
                album = album,
                source = SOURCE_MANUAL_IMPORT,
                provider = "local",
                overwrite = false
            )
        }

        return true
    }

    fun deleteLocalLyrics(context: Context, title: String, artist: String, duration: Long, mode: DeleteMode = DeleteMode.ALL): Boolean {
        val entries = LyricsIndexStore.read(context)
        val matched = entries.filter { SongIdentity.isSameSong(it, title, artist, duration) }
        val matchedKeys = matched.map { it.key }.toSet()
        var deletedAny = false
        val now = System.currentTimeMillis()

        matched.forEach { entry ->
            if ((mode == DeleteMode.PLAIN || mode == DeleteMode.ALL) && entry.file.isNotBlank()) {
                LyricsFileStore.deleteManagedLyrics(context, entry.file)
                deletedAny = true
            }
            if ((mode == DeleteMode.KARAOKE || mode == DeleteMode.ALL) && entry.karaokeFile.isNotBlank()) {
                LyricsFileStore.deleteManagedLyrics(context, entry.karaokeFile)
                deletedAny = true
            }
        }

        val updatedEntries = entries.mapNotNull { entry ->
            if (entry.key !in matchedKeys) return@mapNotNull entry

            val plainFile = if (mode == DeleteMode.PLAIN || mode == DeleteMode.ALL) "" else entry.file
            val karaokeFile = if (mode == DeleteMode.KARAOKE || mode == DeleteMode.ALL) "" else entry.karaokeFile

            if (plainFile.isBlank() && karaokeFile.isBlank()) null else entry.copy(
                file = plainFile,
                karaokeFile = karaokeFile,
                updatedAt = now
            )
        }

        if (updatedEntries.size != entries.size || updatedEntries != entries) {
            LyricsIndexStore.write(context, updatedEntries)
        }

        if (mode == DeleteMode.PLAIN || mode == DeleteMode.ALL) {
            val legacyFileName = SongIdentity.makeLegacyFileName(title, artist, duration)
            deletedAny = LyricsFileStore.deleteLegacyLyrics(context, legacyFileName) || deletedAny
        }

        return deletedAny
    }

    /** Test seams for pure karaoke codec coverage. */
    fun parseEnhancedLrcKaraokeForTest(rawLrc: String): List<KaraokeLine> = KaraokeLyricsCodec.parseEnhancedLrc(rawLrc)

    fun parseKaraokeJsonForTest(rawJson: String): List<KaraokeLine> = KaraokeLyricsCodec.parseJson(rawJson)

    fun karaokeLinesToJsonForTest(lines: List<KaraokeLine>): String = KaraokeLyricsCodec.linesToJson(lines)

    fun karaokeLinesToPlainLrcForTest(lines: List<KaraokeLine>): String = KaraokeLyricsCodec.linesToPlainLrc(lines)
}
