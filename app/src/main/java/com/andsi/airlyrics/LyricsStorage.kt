package com.andsi.airlyrics

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.MessageDigest

object LyricsStorage {
    private const val PREFS_NAME = "lyrics_storage"
    private const val KEY_TREE_URI = "lyrics_tree_uri"

    private const val FALLBACK_LYRICS_DIR = "lyrics"

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