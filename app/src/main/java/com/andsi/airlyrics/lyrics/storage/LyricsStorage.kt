package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import com.andsi.airlyrics.lyrics.parser.LrcParser
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local lyrics storage and index.
 *
 * User selected directory is still respected. New managed lyrics are stored under a
 * small `lyrics/` subfolder with `lyrics_index.json` recording which song each
 * file belongs to. Legacy files saved by older builds are still readable.
 */
object LyricsStorage {
    private const val PREFS_NAME = "lyrics_storage"
    private const val KEY_TREE_URI = "lyrics_tree_uri"

    private const val FALLBACK_LYRICS_DIR = "lyrics"
    private const val MANAGED_LYRICS_DIR = "lyrics"
    private const val INDEX_FILE_NAME = "lyrics_index.json"

    const val SOURCE_MANUAL_IMPORT = "manual_import"
    const val SOURCE_DOWNLOADED = "downloaded"
    const val SOURCE_LEGACY = "legacy"

    enum class DeleteMode {
        PLAIN,
        KARAOKE,
        ALL
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

    private data class LyricsIndexEntry(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val file: String,
        val karaokeFile: String = "",
        val source: String,
        val provider: String,
        val karaokeProvider: String = "",
        val createdAt: Long,
        val updatedAt: Long
    )

    fun listRecentLyrics(context: Context, limit: Int = 8): List<LocalLyricsItem> {
        val indexEntries = readIndex(context)
        val indexedByFileName = indexEntries.associateBy { it.file.substringAfterLast('/') }
        val treeUri = getLyricsDirUri(context)

        val items = if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            val managedDir = root?.findFile(MANAGED_LYRICS_DIR)?.takeIf { it.isDirectory }
            val rootItems = root?.listFiles().orEmpty()
                .filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
            val managedItems = managedDir?.listFiles().orEmpty()
                .filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
            (rootItems + managedItems).map { file ->
                val entry = indexedByFileName[file.name.orEmpty()]
                LocalLyricsItem(
                    name = file.name.orEmpty(),
                    modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
                    sizeBytes = file.length(),
                    title = entry?.title.orEmpty(),
                    artist = entry?.artist.orEmpty(),
                    source = entry?.source ?: SOURCE_LEGACY,
                    provider = entry?.provider ?: "local",
                    hasPlainLyrics = true,
                    hasKaraokeLyrics = entry?.karaokeFile?.isNotBlank() == true
                )
            }
        } else {
            val root = fallbackLyricsDir(context)
            val managedDir = File(root, MANAGED_LYRICS_DIR)
            val rootItems = root.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".lrc", ignoreCase = true) }
            val managedItems = managedDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".lrc", ignoreCase = true) }
            (rootItems + managedItems).map { file ->
                val entry = indexedByFileName[file.name]
                LocalLyricsItem(
                    name = file.name,
                    modifiedTimeMillis = file.lastModified().takeIf { it > 0L } ?: entry?.updatedAt ?: 0L,
                    sizeBytes = file.length(),
                    title = entry?.title.orEmpty(),
                    artist = entry?.artist.orEmpty(),
                    source = entry?.source ?: SOURCE_LEGACY,
                    provider = entry?.provider ?: "local",
                    hasPlainLyrics = true,
                    hasKaraokeLyrics = entry?.karaokeFile?.isNotBlank() == true
                )
            }
        }

        val indexedOnlyKaraokeItems = indexEntries
            .filter { it.file.isBlank() && it.karaokeFile.isNotBlank() }
            .map { entry ->
                LocalLyricsItem(
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
        val entry = findIndexEntryByFileName(context, item.name)
        if (item.hasPlainLyrics && entry?.file?.isNotBlank() == true) {
            readManagedLyrics(context, entry.file)?.let { return it }
        }
        if (!item.hasPlainLyrics && item.hasKaraokeLyrics && entry?.karaokeFile?.isNotBlank() == true) {
            readManagedLyrics(context, entry.karaokeFile)?.let { return it }
        }

        return readLyricsFileByName(context, item.name)
    }

    fun updateLocalLyricsItemText(context: Context, item: LocalLyricsItem, text: String): Boolean {
        if (!item.hasPlainLyrics || item.name.endsWith(".karaoke.json", ignoreCase = true)) return false
        if (!looksLikeTimedLrc(text)) return false

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return false

        val entry = findIndexEntryByFileName(context, item.name)
        val fileName = entry?.file?.substringAfterLast('/') ?: item.name
        val saved = if (entry?.file?.isNotBlank() == true) {
            writeManagedLyrics(context, fileName, normalizedLyrics)
        } else {
            writeLyricsFileByName(context, item.name, normalizedLyrics)
        }
        if (!saved) return false

        if (entry != null) {
            val updated = readIndex(context).map { indexed ->
                if (indexed.key == entry.key) indexed.copy(updatedAt = System.currentTimeMillis()) else indexed
            }
            writeIndex(context, updated)
        }
        return true
    }


    fun saveLyricsDirUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    fun getLyricsDirUri(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)

        return value?.let { Uri.parse(it) }
    }

    @Deprecated("UI should compose the save-folder label with a keyed formatter instead of translating the full path.")
    fun getLyricsDirDisplayPath(context: Context): String {
        val treeUri = getLyricsDirUri(context)

        return if (treeUri != null) {
            val dirName = DocumentFile.fromTreeUri(context, treeUri)?.name
            if (dirName.isNullOrBlank()) {
                "已选择用户目录"
            } else {
                "已选择：$dirName"
            }
        } else {
            fallbackLyricsDir(context).absolutePath
        }
    }

    fun getLyricsDirRawPath(context: Context): String {
        return getLyricsDirUri(context)?.toString() ?: fallbackLyricsDir(context).absolutePath
    }

    fun getLocalLyricsInfo(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LocalLyricsInfo? {
        val indexed = findIndexEntry(context, title, artist, duration)
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

        val legacyFileName = makeLegacyFileName(title, artist, duration)
        val legacyExists = if (getLyricsDirUri(context) != null) {
            DocumentFile.fromTreeUri(context, getLyricsDirUri(context)!!)?.findFile(legacyFileName) != null
        } else {
            File(fallbackLyricsDir(context), legacyFileName).exists()
        }

        return if (legacyExists) {
            LocalLyricsInfo(
                title = title,
                artist = artist,
                durationMs = duration,
                fileName = legacyFileName,
                source = SOURCE_LEGACY,
                provider = "local",
                updatedAt = 0L
            )
        } else {
            null
        }
    }

    fun hasLocalLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        return readLocalLyrics(context, title, artist, duration) != null
    }

    fun readLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): String? {
        findIndexEntry(context, title, artist, duration)?.let { entry ->
            if (entry.file.isNotBlank()) {
                readManagedLyrics(context, entry.file)?.let { return it }
            }
        }

        return readLegacyLyrics(context, title, artist, duration)
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
        val normalizedKey = makeKey(title, artist, duration)
        val existing = findIndexEntry(context, title, artist, duration)
        if (existing?.file?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = "${normalizedKey.take(16)}.lrc"
        val relativeFile = "$MANAGED_LYRICS_DIR/$fileName"

        if (!writeManagedLyrics(context, fileName, lyrics)) return false

        val entries = readIndex(context)
            .filterNot { isSameSong(it, title, artist, duration) || it.key == normalizedKey }
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

        writeIndex(context, entries)
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
        val text = readTextFromUri(context, uri) ?: return false
        if (!looksLikeTimedLrc(text)) return false

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return false

        return saveLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            lyrics = normalizedLyrics,
            album = album,
            source = SOURCE_MANUAL_IMPORT,
            provider = "local",
            overwrite = overwrite
        )
    }


    private fun readTextFromUri(context: Context, uri: Uri): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        if (bytes.isEmpty()) return ""

        val utf8 = bytes.toString(Charsets.UTF_8)
        if ('\uFFFD' !in utf8) return utf8

        return runCatching {
            bytes.toString(Charset.forName("GB18030"))
        }.getOrElse { utf8 }
    }

    private fun looksLikeTimedLrc(text: String): Boolean {
        return Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]""").containsMatchIn(text)
    }

    fun hasKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        val entry = findIndexEntry(context, title, artist, duration) ?: return false
        return entry.karaokeFile.isNotBlank() && readManagedLyrics(context, entry.karaokeFile) != null
    }

    fun readKaraokeLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): List<KaraokeLine> {
        val entry = findIndexEntry(context, title, artist, duration) ?: return emptyList()
        val rawJson = entry.karaokeFile
            .takeIf { it.isNotBlank() }
            ?.let { readManagedLyrics(context, it) }
            ?: return emptyList()

        return parseKaraokeJson(rawJson)
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

        val normalizedKey = makeKey(title, artist, duration)
        val existing = findIndexEntry(context, title, artist, duration)
        if (existing?.karaokeFile?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = "${normalizedKey.take(16)}.karaoke.json"
        val relativeFile = "$MANAGED_LYRICS_DIR/$fileName"
        val json = karaokeLinesToJson(karaokeLines)

        if (!writeManagedLyrics(context, fileName, json)) return false

        val entries = readIndex(context)
            .filterNot { isSameSong(it, title, artist, duration) || it.key == normalizedKey }
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

        writeIndex(context, entries)
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
        val text = readTextFromUri(context, uri) ?: return false

        val lines = parseKaraokeJson(text).ifEmpty { parseEnhancedLrcKaraoke(text) }
        if (lines.isEmpty()) return false

        val savedKaraoke = saveKaraokeLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            karaokeLines = lines,
            album = album,
            source = SOURCE_MANUAL_IMPORT,
            provider = "local",
            overwrite = overwrite
        )
        if (!savedKaraoke) return false

        // A word-by-word LRC also contains normal line text. Save a plain LRC shadow
        // when the current song does not already have ordinary local lyrics, so a
        // user can import only one enhanced .lrc and still get stable display text.
        if (!hasLocalLyrics(context, title, artist, duration)) {
            return saveLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                lyrics = karaokeLinesToPlainLrc(lines),
                album = album,
                source = SOURCE_MANUAL_IMPORT,
                provider = "local",
                overwrite = false
            )
        }

        return true
    }

    fun deleteLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        mode: DeleteMode = DeleteMode.ALL
    ): Boolean {
        val entries = readIndex(context)
        val matched = entries.filter { isSameSong(it, title, artist, duration) }
        val matchedKeys = matched.map { it.key }.toSet()
        var deletedAny = false
        val now = System.currentTimeMillis()

        matched.forEach { entry ->
            if ((mode == DeleteMode.PLAIN || mode == DeleteMode.ALL) && entry.file.isNotBlank()) {
                // Treat the index change as a successful delete even if the physical
                // file has already disappeared, so stale entries can be cleaned up.
                deleteManagedLyrics(context, entry.file)
                deletedAny = true
            }
            if ((mode == DeleteMode.KARAOKE || mode == DeleteMode.ALL) && entry.karaokeFile.isNotBlank()) {
                deleteManagedLyrics(context, entry.karaokeFile)
                deletedAny = true
            }
        }

        val updatedEntries = entries.mapNotNull { entry ->
            if (entry.key !in matchedKeys) return@mapNotNull entry

            val plainFile = if (mode == DeleteMode.PLAIN || mode == DeleteMode.ALL) "" else entry.file
            val karaokeFile = if (mode == DeleteMode.KARAOKE || mode == DeleteMode.ALL) "" else entry.karaokeFile

            if (plainFile.isBlank() && karaokeFile.isBlank()) {
                null
            } else {
                entry.copy(
                    file = plainFile,
                    karaokeFile = karaokeFile,
                    updatedAt = now
                )
            }
        }

        if (updatedEntries.size != entries.size || updatedEntries != entries) {
            writeIndex(context, updatedEntries)
        }

        if (mode == DeleteMode.PLAIN || mode == DeleteMode.ALL) {
            val legacyFileName = makeLegacyFileName(title, artist, duration)
            deletedAny = deleteLegacyLyrics(context, legacyFileName) || deletedAny
        }

        return deletedAny
    }

    private fun fallbackLyricsDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir

        return File(baseDir, FALLBACK_LYRICS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun fallbackManagedLyricsDir(context: Context): File {
        return File(fallbackLyricsDir(context), MANAGED_LYRICS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun documentRoot(context: Context): DocumentFile? {
        val uri = getLyricsDirUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    private fun managedDocumentDir(context: Context): DocumentFile? {
        val root = documentRoot(context) ?: return null
        val existing = root.findFile(MANAGED_LYRICS_DIR)?.takeIf { it.isDirectory }
        return existing ?: root.createDirectory(MANAGED_LYRICS_DIR)
    }

    private fun indexDocumentFile(context: Context, create: Boolean): DocumentFile? {
        val root = documentRoot(context) ?: return null
        val existing = root.findFile(INDEX_FILE_NAME)
        return existing ?: if (create) root.createFile("application/json", INDEX_FILE_NAME) else null
    }

    private fun findIndexEntryByFileName(context: Context, fileName: String): LyricsIndexEntry? {
        val normalizedName = fileName.substringAfterLast('/')
        return readIndex(context).firstOrNull { entry ->
            entry.file.substringAfterLast('/') == normalizedName ||
                entry.karaokeFile.substringAfterLast('/') == normalizedName
        }
    }

    private fun readLyricsFileByName(context: Context, fileName: String): String? {
        val safeName = fileName.substringAfterLast('/')
        val treeUri = getLyricsDirUri(context)

        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            root.findFile(safeName)?.let { file ->
                return context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
            managedDocumentDir(context)?.findFile(safeName)?.let { file ->
                return context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
            return null
        }

        val managedFile = File(fallbackManagedLyricsDir(context), safeName)
        if (managedFile.exists()) return managedFile.readText()

        val legacyFile = File(fallbackLyricsDir(context), safeName)
        if (legacyFile.exists()) return legacyFile.readText()

        return null
    }

    private fun writeLyricsFileByName(context: Context, fileName: String, lyrics: String): Boolean {
        val safeName = fileName.substringAfterLast('/')
        val treeUri = getLyricsDirUri(context)

        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val rootFile = root.findFile(safeName)
            if (rootFile != null) {
                context.contentResolver.openOutputStream(rootFile.uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(lyrics) }
                    ?: return false
                return true
            }

            val managedFile = managedDocumentDir(context)?.findFile(safeName) ?: return false
            context.contentResolver.openOutputStream(managedFile.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(lyrics) }
                ?: return false
            return true
        }

        val managedFile = File(fallbackManagedLyricsDir(context), safeName)
        if (managedFile.exists()) {
            managedFile.writeText(lyrics)
            return true
        }

        val legacyFile = File(fallbackLyricsDir(context), safeName)
        if (legacyFile.exists()) {
            legacyFile.writeText(lyrics)
            return true
        }

        return false
    }

    private fun readManagedLyrics(context: Context, relativeFile: String): String? {
        val fileName = relativeFile.substringAfterLast('/')
        val treeUri = getLyricsDirUri(context)

        if (treeUri != null) {
            val file = managedDocumentDir(context)?.findFile(fileName) ?: return null
            return context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }

        val file = File(fallbackManagedLyricsDir(context), fileName)
        if (!file.exists()) return null
        return file.readText()
    }

    private fun writeManagedLyrics(context: Context, fileName: String, lyrics: String): Boolean {
        val treeUri = getLyricsDirUri(context)

        if (treeUri != null) {
            val dir = managedDocumentDir(context) ?: return false
            val file = dir.findFile(fileName)
                ?: dir.createFile("application/octet-stream", fileName)
                ?: return false

            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(lyrics) }
                ?: return false
            return true
        }

        File(fallbackManagedLyricsDir(context), fileName).writeText(lyrics)
        return true
    }

    private fun deleteManagedLyrics(context: Context, relativeFile: String): Boolean {
        val fileName = relativeFile.substringAfterLast('/')
        val treeUri = getLyricsDirUri(context)

        if (treeUri != null) {
            return managedDocumentDir(context)?.findFile(fileName)?.delete() == true
        }

        val file = File(fallbackManagedLyricsDir(context), fileName)
        return file.exists() && file.delete()
    }

    private fun readLegacyLyrics(context: Context, title: String, artist: String, duration: Long): String? {
        val treeUri = getLyricsDirUri(context)
        val fileName = makeLegacyFileName(title, artist, duration)

        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val file = dir.findFile(fileName) ?: return null

            return context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }

        val file = File(fallbackLyricsDir(context), fileName)
        if (!file.exists()) return null

        return file.readText()
    }

    private fun deleteLegacyLyrics(context: Context, fileName: String): Boolean {
        val treeUri = getLyricsDirUri(context)

        if (treeUri != null) {
            return DocumentFile.fromTreeUri(context, treeUri)?.findFile(fileName)?.delete() == true
        }

        val file = File(fallbackLyricsDir(context), fileName)
        return file.exists() && file.delete()
    }

    private fun readIndex(context: Context): List<LyricsIndexEntry> {
        val text = if (getLyricsDirUri(context) != null) {
            val file = indexDocumentFile(context, create = false) ?: return emptyList()
            context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        } else {
            val file = File(fallbackLyricsDir(context), INDEX_FILE_NAME)
            if (!file.exists()) return emptyList()
            file.readText()
        } ?: return emptyList()

        return runCatching {
            val array = JSONArray(text)
            List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                LyricsIndexEntry(
                    key = obj.optString("key"),
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album"),
                    durationMs = obj.optLong("durationMs"),
                    file = obj.optString("file"),
                    karaokeFile = obj.optString("karaokeFile"),
                    source = obj.optString("source", SOURCE_DOWNLOADED),
                    provider = obj.optString("provider", "local"),
                    karaokeProvider = obj.optString("karaokeProvider"),
                    createdAt = obj.optLong("createdAt"),
                    updatedAt = obj.optLong("updatedAt")
                )
            }.filter { it.key.isNotBlank() && (it.file.isNotBlank() || it.karaokeFile.isNotBlank()) }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, entries: List<LyricsIndexEntry>) {
        val array = JSONArray()
        entries.sortedByDescending { it.updatedAt }.forEach { entry ->
            array.put(JSONObject().apply {
                put("key", entry.key)
                put("title", entry.title)
                put("artist", entry.artist)
                put("album", entry.album)
                put("durationMs", entry.durationMs)
                put("file", entry.file)
                put("karaokeFile", entry.karaokeFile)
                put("source", entry.source)
                put("provider", entry.provider)
                put("karaokeProvider", entry.karaokeProvider)
                put("createdAt", entry.createdAt)
                put("updatedAt", entry.updatedAt)
            })
        }

        val text = array.toString(2)
        if (getLyricsDirUri(context) != null) {
            val file = indexDocumentFile(context, create = true) ?: return
            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(text) }
            return
        }

        File(fallbackLyricsDir(context), INDEX_FILE_NAME).writeText(text)
    }

    private fun findIndexEntry(context: Context, title: String, artist: String, duration: Long): LyricsIndexEntry? {
        val entries = readIndex(context)
        return entries.firstOrNull { isStrongSameSong(it, title, artist, duration) }
            ?: entries.firstOrNull { isWeakSameSong(it, title, artist) }
    }

    private fun isSameSong(entry: LyricsIndexEntry, title: String, artist: String, duration: Long): Boolean {
        return isStrongSameSong(entry, title, artist, duration) || isWeakSameSong(entry, title, artist)
    }

    private fun isStrongSameSong(entry: LyricsIndexEntry, title: String, artist: String, duration: Long): Boolean {
        if (!isWeakSameSong(entry, title, artist)) return false
        if (duration <= 0L || entry.durationMs <= 0L) return true
        return kotlin.math.abs(entry.durationMs - duration) <= 5_000L
    }

    private fun isWeakSameSong(entry: LyricsIndexEntry, title: String, artist: String): Boolean {
        return normalizeText(entry.title) == normalizeText(title) &&
            normalizeText(entry.artist) == normalizeText(artist)
    }

    private fun makeKey(title: String, artist: String, duration: Long): String {
        val normalizedDuration = if (duration > 0L) duration / 1000L else 0L
        val raw = "${normalizeText(title)}|${normalizeText(artist)}|$normalizedDuration"
        return sha1(raw)
    }

    private fun karaokeLinesToJson(lines: List<KaraokeLine>): String {
        val array = JSONArray()
        lines.sortedBy { it.startMs }.forEach { line ->
            array.put(JSONObject().apply {
                put("startMs", line.startMs)
                put("endMs", line.endMs)
                put("text", line.text)
                put("tokens", JSONArray().apply {
                    line.tokens.forEach { token ->
                        put(JSONObject().apply {
                            put("text", token.text)
                            put("startMs", token.startMs)
                            put("endMs", token.endMs)
                        })
                    }
                })
            })
        }
        return array.toString(2)
    }

    private fun parseKaraokeJson(rawJson: String): List<KaraokeLine> {
        if (rawJson.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (lineIndex in 0 until array.length()) {
                    val line = array.optJSONObject(lineIndex) ?: continue
                    val startMs = line.optLong("startMs", -1L)
                    val endMs = line.optLong("endMs", -1L)
                    val text = line.optString("text", "").trim()
                    val tokenArray = line.optJSONArray("tokens") ?: JSONArray()
                    if (startMs < 0L || endMs <= startMs || text.isBlank() || tokenArray.length() == 0) continue

                    val tokens = buildList {
                        for (tokenIndex in 0 until tokenArray.length()) {
                            val token = tokenArray.optJSONObject(tokenIndex) ?: continue
                            val tokenText = token.optString("text", "").trim()
                            val tokenStartMs = token.optLong("startMs", -1L)
                            val tokenEndMs = token.optLong("endMs", -1L)
                            if (tokenText.isNotBlank() && tokenStartMs >= startMs && tokenEndMs > tokenStartMs) {
                                add(KaraokeToken(tokenText, tokenStartMs, tokenEndMs))
                            }
                        }
                    }

                    if (tokens.isNotEmpty()) {
                        add(KaraokeLine(startMs, endMs, text, tokens))
                    }
                }
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }



    private fun karaokeLinesToPlainLrc(lines: List<KaraokeLine>): String {
        return lines
            .sortedBy { it.startMs }
            .joinToString("\n") { line ->
                "[${formatLrcTimeTag(line.startMs)}]${line.text.trim()}"
            }
    }

    private fun formatLrcTimeTag(timeMs: Long): String {
        val minutes = timeMs / 60_000L
        val seconds = (timeMs % 60_000L) / 1_000L
        val centiseconds = (timeMs % 1_000L) / 10L
        return "%02d:%02d.%02d".format(Locale.getDefault(), minutes, seconds, centiseconds)
    }

    private fun parseEnhancedLrcKaraoke(rawLrc: String): List<KaraokeLine> {
        if (rawLrc.isBlank()) return emptyList()

        data class RawEnhancedLine(
            val startMs: Long,
            val content: String,
            val tokens: List<Pair<String, Long>>
        )

        val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val wordTimeTagRegex = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

        val rawLines = rawLrc
            .lineSequence()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) return@mapNotNull null

                val lineStart = timeTagRegex.find(line)?.let { parseLrcTimeTag(it) }
                    ?: return@mapNotNull null
                val content = line.replace(timeTagRegex, "").trim()
                val wordTags = wordTimeTagRegex.findAll(content).toList()
                if (wordTags.isEmpty()) return@mapNotNull null

                val tokens = wordTags.mapIndexedNotNull { index, match ->
                    val startMs = parseLrcTimeTag(match) ?: return@mapIndexedNotNull null
                    val textStart = match.range.last + 1
                    val textEndExclusive = wordTags.getOrNull(index + 1)?.range?.first ?: content.length
                    if (textStart > textEndExclusive || textStart > content.length) return@mapIndexedNotNull null
                    val tokenText = content.substring(textStart, textEndExclusive)
                        .replace(wordTimeTagRegex, "")
                    if (tokenText.isBlank()) return@mapIndexedNotNull null
                    tokenText to startMs
                }.filter { (_, startMs) -> startMs >= lineStart }

                if (tokens.isEmpty()) null else RawEnhancedLine(lineStart, content, tokens)
            }
            .sortedBy { it.startMs }
            .toList()

        if (rawLines.isEmpty()) return emptyList()

        return rawLines.mapIndexedNotNull { index, rawLine ->
            val nextLineStart = rawLines.getOrNull(index + 1)?.startMs
            val tokenList = rawLine.tokens.mapIndexed { tokenIndex, (tokenText, tokenStartMs) ->
                val nextTokenStart = rawLine.tokens.getOrNull(tokenIndex + 1)?.second
                    ?: nextLineStart
                    ?: (tokenStartMs + 400L)
                KaraokeToken(
                    text = tokenText,
                    startMs = tokenStartMs,
                    endMs = nextTokenStart.coerceAtLeast(tokenStartMs + 1L)
                )
            }

            val lineText = tokenList.joinToString(separator = "") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (lineText.isBlank() || tokenList.isEmpty()) return@mapIndexedNotNull null

            val lineEnd = nextLineStart ?: tokenList.last().endMs
            if (lineEnd <= rawLine.startMs) return@mapIndexedNotNull null

            KaraokeLine(
                startMs = rawLine.startMs,
                endMs = lineEnd,
                text = lineText,
                tokens = tokenList
            )
        }
    }

    private fun parseLrcTimeTag(match: MatchResult): Long? {
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
        val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
        val millis = when (fractionRaw.length) {
            0 -> 0L
            1 -> fractionRaw.toLongOrNull()?.times(100L) ?: return null
            2 -> fractionRaw.toLongOrNull()?.times(10L) ?: return null
            else -> fractionRaw.take(3).toLongOrNull() ?: return null
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun makeLegacyFileName(title: String, artist: String, duration: Long): String {
        val safeTitle = title
            .trim()
            .ifBlank { "Unknown Title" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val safeArtist = artist
            .trim()
            .ifBlank { "Unknown Artist" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val key = md5("${title.trim().lowercase()}|${artist.trim().lowercase()}|${duration / 1000L}").take(8)

        return "$safeTitle - $safeArtist [$key].lrc"
    }

    private fun normalizeText(text: String): String {
        return text.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
    }

    private fun sha1(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
