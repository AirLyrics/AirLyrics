package com.andsi.airlyrics

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    data class LocalLyricsItem(
        val name: String,
        val modifiedTimeMillis: Long,
        val sizeBytes: Long,
        val title: String = "",
        val artist: String = "",
        val source: String = SOURCE_LEGACY,
        val provider: String = "local"
    ) {
        val displayTitle: String
            get() = title.ifBlank { name }

        val displaySubtitle: String
            get() {
                val artistPart = artist.ifBlank { "未知歌手" }
                val sourcePart = when (source) {
                    SOURCE_MANUAL_IMPORT -> "手动导入"
                    SOURCE_DOWNLOADED -> if (provider.isBlank() || provider == "local") "本地缓存" else "本地缓存 · $provider"
                    SOURCE_LEGACY -> "旧版本地文件"
                    else -> "本地歌词"
                }
                return "$artistPart · $sourcePart"
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
        val sourceText: String
            get() = when (source) {
                SOURCE_MANUAL_IMPORT -> "手动导入"
                SOURCE_DOWNLOADED -> if (provider.isBlank() || provider == "local") "本地缓存" else "本地缓存 · $provider"
                SOURCE_LEGACY -> "旧版本地文件"
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
        val source: String,
        val provider: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    fun listRecentLyrics(context: Context, limit: Int = 8): List<LocalLyricsItem> {
        val indexedByFileName = readIndex(context).associateBy { it.file.substringAfterLast('/') }
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
                    provider = entry?.provider ?: "local"
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
                    provider = entry?.provider ?: "local"
                )
            }
        }

        return items
            .sortedByDescending { it.modifiedTimeMillis }
            .take(limit.coerceAtLeast(1))
    }

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
        if (indexed != null) {
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
        return getLocalLyricsInfo(context, title, artist, duration) != null
    }

    fun readLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): String? {
        findIndexEntry(context, title, artist, duration)?.let { entry ->
            readManagedLyrics(context, entry.file)?.let { return it }
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
        if (existing != null && !overwrite) return false

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
            source = source,
            provider = provider,
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
        val text = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull() ?: return false

        return saveLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            lyrics = text,
            album = album,
            source = SOURCE_MANUAL_IMPORT,
            provider = "local",
            overwrite = overwrite
        )
    }

    fun deleteLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): Boolean {
        val entries = readIndex(context)
        val matched = entries.filter { isSameSong(it, title, artist, duration) }
        var deletedAny = false

        matched.forEach { entry ->
            deletedAny = deleteManagedLyrics(context, entry.file) || deletedAny
        }

        if (matched.isNotEmpty()) {
            writeIndex(context, entries.filterNot { candidate -> matched.any { it.key == candidate.key } })
            deletedAny = true
        }

        val legacyFileName = makeLegacyFileName(title, artist, duration)
        deletedAny = deleteLegacyLyrics(context, legacyFileName) || deletedAny

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
                    source = obj.optString("source", SOURCE_DOWNLOADED),
                    provider = obj.optString("provider", "local"),
                    createdAt = obj.optLong("createdAt"),
                    updatedAt = obj.optLong("updatedAt")
                )
            }.filter { it.key.isNotBlank() && it.file.isNotBlank() }
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
                put("source", entry.source)
                put("provider", entry.provider)
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
