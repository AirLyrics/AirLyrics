package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LyricsStoragePaths {
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
            if (dirName.isNullOrBlank()) "已选择用户目录" else "已选择：$dirName"
        } else {
            fallbackLyricsDir(context).absolutePath
        }
    }

    fun getLyricsDirRawPath(context: Context): String {
        return getLyricsDirUri(context)?.toString() ?: fallbackLyricsDir(context).absolutePath
    }

    fun fallbackLyricsDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, FALLBACK_LYRICS_DIR).apply { if (!exists()) mkdirs() }
    }

    fun fallbackManagedLyricsDir(context: Context): File {
        return File(fallbackLyricsDir(context), MANAGED_LYRICS_DIR).apply { if (!exists()) mkdirs() }
    }

    fun documentRoot(context: Context): DocumentFile? {
        val uri = getLyricsDirUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    fun managedDocumentDir(context: Context): DocumentFile? {
        val root = documentRoot(context) ?: return null
        val existing = root.findFile(MANAGED_LYRICS_DIR)?.takeIf { it.isDirectory }
        return existing ?: root.createDirectory(MANAGED_LYRICS_DIR)
    }

    fun indexDocumentFile(context: Context, create: Boolean): DocumentFile? {
        val root = documentRoot(context) ?: return null
        val existing = root.findFile(INDEX_FILE_NAME)
        return existing ?: if (create) root.createFile("application/json", INDEX_FILE_NAME) else null
    }
}
