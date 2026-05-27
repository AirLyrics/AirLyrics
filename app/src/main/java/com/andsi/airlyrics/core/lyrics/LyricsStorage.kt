package com.andsi.airlyrics

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LyricsStorage {
    private const val PREFS_NAME = "lyrics_storage"
    private const val KEY_TREE_URI = "lyrics_tree_uri"

    private const val FALLBACK_LYRICS_DIR = "lyrics"


    data class LocalLyricsItem(
        val name: String,
        val modifiedTimeMillis: Long,
        val sizeBytes: Long
    )

    fun listRecentLyrics(context: Context, limit: Int = 8): List<LocalLyricsItem> {
        val treeUri = getLyricsDirUri(context)

        val items = if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            dir?.listFiles()
                ?.filter { it.isFile && it.name?.endsWith(".lrc", ignoreCase = true) == true }
                ?.map { file ->
                    LocalLyricsItem(
                        name = file.name.orEmpty(),
                        modifiedTimeMillis = file.lastModified(),
                        sizeBytes = file.length()
                    )
                }
                .orEmpty()
        } else {
            fallbackLyricsDir(context).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".lrc", ignoreCase = true) }
                ?.map { file ->
                    LocalLyricsItem(
                        name = file.name,
                        modifiedTimeMillis = file.lastModified(),
                        sizeBytes = file.length()
                    )
                }
                .orEmpty()
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

    private fun fallbackLyricsDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir

        return File(baseDir, FALLBACK_LYRICS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun makeKey(title: String, artist: String, duration: Long): String {
        val normalizedDuration = duration / 1000L
        val raw = "${title.trim().lowercase()}|${artist.trim().lowercase()}|$normalizedDuration"
        return md5(raw)
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun makeFileName(title: String, artist: String, duration: Long): String {
        val safeTitle = title
            .trim()
            .ifBlank { "Unknown Title" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val safeArtist = artist
            .trim()
            .ifBlank { "Unknown Artist" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val key = makeKey(title, artist, duration).take(8)

        return "$safeTitle - $safeArtist [$key].lrc"
    }

    fun readLocalLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): String? {
        val treeUri = getLyricsDirUri(context)
        val fileName = makeFileName(title, artist, duration)

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

    fun saveLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        lyrics: String
    ) {
        val treeUri = getLyricsDirUri(context)
        val fileName = makeFileName(title, artist, duration)

        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return
            val file = dir.findFile(fileName)
                ?: dir.createFile("application/octet-stream", fileName)
                ?: return

            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(lyrics) }

            return
        }

        val file = File(fallbackLyricsDir(context), fileName)
        file.writeText(lyrics)
    }

    fun importLyricsFromUri(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long
    ): Boolean {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull() ?: return false

        saveLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = duration,
            lyrics = text
        )

        return true
    }
}