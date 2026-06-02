package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.parser.LrcParser

/**
 * Public facade for local lyric persistence.
 *
 * Keep external callers here, but keep implementation details in focused helpers:
 * paths, files, index matching, song identity, listing, and karaoke codecs.
 */
object LyricsStorage {
    private val storageLock = Any()

    private inline fun <T> withStorageLock(block: () -> T): T = synchronized(storageLock) { block() }

    const val SOURCE_MANUAL_IMPORT = "manual_import"
    const val SOURCE_DOWNLOADED = "downloaded"
    const val SOURCE_LEGACY = "legacy"

    enum class DeleteMode { PLAIN, KARAOKE, ALL }

    enum class LocalLyricsEditTarget { PLAIN, KARAOKE }

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

    fun listRecentLyrics(context: Context, limit: Int = 8): List<LocalLyricsItem> = withStorageLock {
        LocalLyricsLister.listRecent(context, limit)
    }


    fun readLocalLyricsItemText(context: Context, item: LocalLyricsItem): String? {
        val defaultTarget = if (item.hasPlainLyrics) LocalLyricsEditTarget.PLAIN else LocalLyricsEditTarget.KARAOKE
        return readLocalLyricsItemText(context, item, defaultTarget)
    }

    fun readLocalLyricsItemText(
        context: Context,
        item: LocalLyricsItem,
        target: LocalLyricsEditTarget
    ): String? = withStorageLock {
        val entry = LyricsIndexStore.findByFileName(context, item.name)
        when (target) {
            LocalLyricsEditTarget.PLAIN -> {
                if (item.hasPlainLyrics && entry?.file?.isNotBlank() == true) {
                    LyricsFileStore.readManagedLyrics(context, entry.file)?.let { return@withStorageLock it }
                }
                if (item.hasPlainLyrics) LyricsFileStore.readLyricsFileByName(context, item.name) else null
            }
            LocalLyricsEditTarget.KARAOKE -> {
                val rawKaraoke = entry?.karaokeFile
                    ?.takeIf { it.isNotBlank() }
                    ?.let { LyricsFileStore.readManagedLyrics(context, it) }
                    ?: return@withStorageLock null
                val karaokeLines = KaraokeLyricsCodec.parseJson(rawKaraoke).ifEmpty {
                    KaraokeLyricsCodec.parseEnhancedLrc(rawKaraoke)
                }
                karaokeLines.takeIf { it.isNotEmpty() }?.let { KaraokeLyricsCodec.linesToEnhancedLrc(it) }
            }
        }
    }

    data class LocalLyricsUpdateResult(
        val saved: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun updateLocalLyricsItemText(context: Context, item: LocalLyricsItem, text: String): Boolean {
        return updateLocalLyricsItemTextWithResult(context, item, text).saved
    }

    fun updateLocalLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        text: String
    ): LocalLyricsUpdateResult {
        if (!item.hasPlainLyrics || item.name.endsWith(".karaoke.json", ignoreCase = true)) {
            return LocalLyricsUpdateResult(saved = false)
        }

        val validation = LrcParser.validateForStorage(text)
        if (!validation.isValid) {
            return LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return LocalLyricsUpdateResult(saved = false)

        return withStorageLock {
            val entry = LyricsIndexStore.findByFileName(context, item.name)
            val fileName = entry?.file?.substringAfterLast('/') ?: item.name
            val saved = if (entry?.file?.isNotBlank() == true) {
                LyricsFileStore.writeManagedLyrics(context, fileName, normalizedLyrics)
            } else {
                LyricsFileStore.writeLyricsFileByName(context, item.name, normalizedLyrics)
            }
            if (!saved) return@withStorageLock LocalLyricsUpdateResult(saved = false)

            if (entry != null) {
                val now = System.currentTimeMillis()
                val updated = LyricsIndexStore.read(context).map { indexed ->
                    if (indexed.key == entry.key) indexed.copy(updatedAt = now) else indexed
                }
                LyricsIndexStore.write(context, updated)
            }
            LocalLyricsUpdateResult(saved = true)
        }
    }

    fun validateKaraokeLyricsItemText(text: String): LocalLyricsUpdateResult {
        val validation = KaraokeLyricsCodec.validateEnhancedLrcForStorage(text)
        return LocalLyricsUpdateResult(
            saved = validation.isValid,
            invalidLineNumbers = validation.invalidLineNumbers
        )
    }

    fun updateKaraokeLyricsItemTextWithResult(
        context: Context,
        item: LocalLyricsItem,
        text: String
    ): LocalLyricsUpdateResult {
        if (!item.hasKaraokeLyrics) return LocalLyricsUpdateResult(saved = false)

        val validation = KaraokeLyricsCodec.validateEnhancedLrcForStorage(text)
        if (!validation.isValid) {
            return LocalLyricsUpdateResult(
                saved = false,
                invalidLineNumbers = validation.invalidLineNumbers
            )
        }

        val karaokeLines = KaraokeLyricsCodec.parseEnhancedLrc(text)
        if (karaokeLines.isEmpty()) return LocalLyricsUpdateResult(saved = false)
        val json = KaraokeLyricsCodec.linesToJson(karaokeLines)

        return withStorageLock {
            val entry = LyricsIndexStore.findByFileName(context, item.name)
                ?: return@withStorageLock LocalLyricsUpdateResult(saved = false)
            val karaokeFileName = entry.karaokeFile.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: return@withStorageLock LocalLyricsUpdateResult(saved = false)
            if (!LyricsFileStore.writeManagedLyrics(context, karaokeFileName, json)) {
                return@withStorageLock LocalLyricsUpdateResult(saved = false)
            }

            val now = System.currentTimeMillis()
            val updated = LyricsIndexStore.read(context).map { indexed ->
                if (indexed.key == entry.key) indexed.copy(updatedAt = now) else indexed
            }
            LyricsIndexStore.write(context, updated)
            LocalLyricsUpdateResult(saved = true)
        }
    }

    fun saveLyricsDirUri(context: Context, uri: Uri) = LyricsStoragePaths.saveLyricsDirUri(context, uri)

    fun getLyricsDirUri(context: Context): Uri? = LyricsStoragePaths.getLyricsDirUri(context)

    @Deprecated("UI should compose the save-folder label with a keyed formatter instead of translating the full path.")
    fun getLyricsDirDisplayPath(context: Context): String = LyricsStoragePaths.getLyricsDirDisplayPath(context)

    fun getLyricsDirRawPath(context: Context): String = LyricsStoragePaths.getLyricsDirRawPath(context)

    fun getLocalLyricsInfo(context: Context, title: String, artist: String, duration: Long): LocalLyricsInfo? = withStorageLock {
        val indexed = LyricsIndexStore.find(context, title, artist, duration)
        if (indexed != null && indexed.file.isNotBlank()) {
            return@withStorageLock LocalLyricsInfo(
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

        if (legacyExists) {
            LocalLyricsInfo(title, artist, duration, legacyFileName, SOURCE_LEGACY, "local", 0L)
        } else {
            null
        }
    }

    fun hasLocalLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        return readLocalLyrics(context, title, artist, duration) != null
    }

    fun readLocalLyrics(context: Context, title: String, artist: String, duration: Long): String? = withStorageLock {
        val entry = LyricsIndexStore.find(context, title, artist, duration)
        if (entry?.file?.isNotBlank() == true) {
            LyricsFileStore.readManagedLyrics(context, entry.file)?.let { return@withStorageLock it }
        }
        LyricsFileStore.readLegacyLyrics(context, title, artist, duration)
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
        val normalizedKey = SongIdentity.makeStorageKey(title, artist, duration)
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.file?.isNotBlank() == true && !overwrite) return@withStorageLock false

        val now = System.currentTimeMillis()
        val fileName = "${normalizedKey.take(16)}.lrc"
        val relativeFile = "$MANAGED_LYRICS_DIR/$fileName"
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, lyrics)) return@withStorageLock false

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
        true
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

    fun hasKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): Boolean = withStorageLock {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return@withStorageLock false
        entry.karaokeFile.isNotBlank() && LyricsFileStore.readManagedLyrics(context, entry.karaokeFile) != null
    }

    fun readKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): List<KaraokeLine> = withStorageLock {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return@withStorageLock emptyList()
        val rawJson = entry.karaokeFile
            .takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
            ?: return@withStorageLock emptyList()
        KaraokeLyricsCodec.parseJson(rawJson)
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

        return withStorageLock {
            val normalizedKey = SongIdentity.makeStorageKey(title, artist, duration)
            val existing = LyricsIndexStore.find(context, title, artist, duration)
            if (existing?.karaokeFile?.isNotBlank() == true && !overwrite) return@withStorageLock false

            val now = System.currentTimeMillis()
            val fileName = "${normalizedKey.take(16)}.karaoke.json"
            val relativeFile = "$MANAGED_LYRICS_DIR/$fileName"
            val json = KaraokeLyricsCodec.linesToJson(karaokeLines)
            if (!LyricsFileStore.writeManagedLyrics(context, fileName, json)) return@withStorageLock false

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
            true
        }
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

        return withStorageLock {
            val savedKaraoke = saveKaraokeLyrics(context, title, artist, duration, lines, album, SOURCE_MANUAL_IMPORT, "local", overwrite)
            if (!savedKaraoke) return@withStorageLock false

            if (!hasLocalLyrics(context, title, artist, duration)) {
                return@withStorageLock saveLyrics(
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

            true
        }
    }

    fun deleteLocalLyrics(context: Context, title: String, artist: String, duration: Long, mode: DeleteMode = DeleteMode.ALL): Boolean = withStorageLock {
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

        deletedAny
    }

    /** Test seams for pure karaoke codec coverage. */
    fun parseEnhancedLrcKaraokeForTest(rawLrc: String): List<KaraokeLine> = KaraokeLyricsCodec.parseEnhancedLrc(rawLrc)

    fun parseKaraokeJsonForTest(rawJson: String): List<KaraokeLine> = KaraokeLyricsCodec.parseJson(rawJson)

    fun karaokeLinesToJsonForTest(lines: List<KaraokeLine>): String = KaraokeLyricsCodec.linesToJson(lines)

    fun karaokeLinesToPlainLrcForTest(lines: List<KaraokeLine>): String = KaraokeLyricsCodec.linesToPlainLrc(lines)

    fun karaokeLinesToEnhancedLrcForTest(lines: List<KaraokeLine>): String = KaraokeLyricsCodec.linesToEnhancedLrc(lines)
}
