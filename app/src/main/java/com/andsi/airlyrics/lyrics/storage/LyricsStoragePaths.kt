package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal object LyricsStoragePaths {
    fun saveLyricsDirUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_TREE_URI, uri.toString())
        }
    }

    fun getLyricsDirUri(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
        return value?.toUri()
    }


    fun validateLyricsDir(context: Context, uri: Uri): Boolean {
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, uri) ?: return false
            if (!root.canRead() || !root.canWrite()) return false

            val existingManagedDir = root.findFile(MANAGED_LYRICS_DIR)
            val managedDir = when {
                existingManagedDir == null -> root.createDirectory(MANAGED_LYRICS_DIR) ?: return false
                existingManagedDir.isDirectory -> existingManagedDir
                else -> return false
            }
            if (!managedDir.canRead() || !managedDir.canWrite()) return false

            val existingIndex = root.findFile(INDEX_FILE_NAME)
            if (existingIndex == null) {
                val createdIndex = root.createFile("application/json", INDEX_FILE_NAME) ?: return false
                context.contentResolver.openOutputStream(createdIndex.uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write("[]") }
                    ?: return false
            } else {
                if (!existingIndex.isFile) return false
                context.contentResolver.openInputStream(existingIndex.uri)
                    ?.use { }
                    ?: return false
            }

            true
        }.getOrDefault(false)
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
        val existing = root.findFile(MANAGED_LYRICS_DIR)
        return when {
            existing == null -> root.createDirectory(MANAGED_LYRICS_DIR)
            existing.isDirectory -> existing
            else -> null
        }
    }

    fun indexDocumentFile(context: Context, create: Boolean): DocumentFile? {
        val root = documentRoot(context) ?: return null
        val existing = root.findFile(INDEX_FILE_NAME)
        return when {
            existing == null && create -> root.createFile("application/json", INDEX_FILE_NAME)
            existing == null -> null
            existing.isFile -> existing
            else -> null
        }
    }
}
