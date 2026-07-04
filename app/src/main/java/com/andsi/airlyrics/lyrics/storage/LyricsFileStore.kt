package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

internal object LyricsFileStore {
    const val MAX_IMPORTED_LYRICS_BYTES = 30 * 1024 * 1024

    sealed class ReadTextResult {
        data class Success(val text: String) : ReadTextResult()
        object TooLarge : ReadTextResult()
        object Failed : ReadTextResult()
    }

    fun readTextFromUriWithResult(
        context: Context,
        uri: Uri,
        maxBytes: Int = MAX_IMPORTED_LYRICS_BYTES
    ): ReadTextResult {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break

                    if (read > maxBytes - total) return ReadTextResult.TooLarge
                    output.write(buffer, 0, read)
                    total += read
                }

                output.toByteArray()
            }
        }.getOrNull() ?: return ReadTextResult.Failed

        if (bytes.isEmpty()) return ReadTextResult.Success("")

        val utf8 = bytes.toString(Charsets.UTF_8)
        if ('\uFFFD' !in utf8) return ReadTextResult.Success(utf8)

        return ReadTextResult.Success(
            runCatching { bytes.toString(Charset.forName("GB18030")) }
                .getOrElse { utf8 }
        )
    }

    fun readLyricsFileByName(context: Context, fileName: String): String? {
        val safeName = fileName.substringAfterLast('/')
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            root.findFile(safeName)?.let { file ->
                return context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
            LyricsStoragePaths.managedDocumentDir(context)?.findFile(safeName)?.let { file ->
                return context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
            return null
        }

        val managedFile = File(LyricsStoragePaths.fallbackManagedLyricsDir(context), safeName)
        if (managedFile.exists()) return managedFile.readText()

        val legacyFile = File(LyricsStoragePaths.fallbackLyricsDir(context), safeName)
        if (legacyFile.exists()) return legacyFile.readText()

        return null
    }

    fun writeLyricsFileByName(context: Context, fileName: String, lyrics: String): Boolean {
        val safeName = fileName.substringAfterLast('/')
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

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

            val managedFile = LyricsStoragePaths.managedDocumentDir(context)?.findFile(safeName) ?: return false
            context.contentResolver.openOutputStream(managedFile.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(lyrics) }
                ?: return false
            return true
        }

        val managedFile = File(LyricsStoragePaths.fallbackManagedLyricsDir(context), safeName)
        if (managedFile.exists()) {
            managedFile.writeText(lyrics)
            return true
        }

        val legacyFile = File(LyricsStoragePaths.fallbackLyricsDir(context), safeName)
        if (legacyFile.exists()) {
            legacyFile.writeText(lyrics)
            return true
        }

        return false
    }

    fun readManagedLyrics(context: Context, relativeFile: String): String? {
        val fileName = relativeFile.substringAfterLast('/')
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        if (treeUri != null) {
            val file = LyricsStoragePaths.managedDocumentDir(context)?.findFile(fileName) ?: return null
            return context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }

        val file = File(LyricsStoragePaths.fallbackManagedLyricsDir(context), fileName)
        if (!file.exists()) return null
        return file.readText()
    }

    fun writeManagedLyrics(context: Context, fileName: String, lyrics: String): Boolean {
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        if (treeUri != null) {
            val dir = LyricsStoragePaths.managedDocumentDir(context) ?: return false
            val file = dir.findFile(fileName)
                ?: dir.createFile("application/octet-stream", fileName)
                ?: return false

            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(lyrics) }
                ?: return false
            return true
        }

        File(LyricsStoragePaths.fallbackManagedLyricsDir(context), fileName).writeText(lyrics)
        return true
    }

    fun deleteManagedLyrics(context: Context, relativeFile: String): Boolean {
        val fileName = relativeFile.substringAfterLast('/')
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        if (treeUri != null) {
            return LyricsStoragePaths.managedDocumentDir(context)?.findFile(fileName)?.delete() == true
        }

        val file = File(LyricsStoragePaths.fallbackManagedLyricsDir(context), fileName)
        return file.exists() && file.delete()
    }

    fun readLegacyLyrics(context: Context, title: String, artist: String, duration: Long): String? {
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)
        val fileName = LegacyLyricsFileName.make(title, artist, duration)

        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val file = dir.findFile(fileName) ?: return null

            return context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }

        val file = File(LyricsStoragePaths.fallbackLyricsDir(context), fileName)
        if (!file.exists()) return null
        return file.readText()
    }

    fun deleteLegacyLyrics(context: Context, fileName: String): Boolean {
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        if (treeUri != null) {
            return DocumentFile.fromTreeUri(context, treeUri)?.findFile(fileName)?.delete() == true
        }

        val file = File(LyricsStoragePaths.fallbackLyricsDir(context), fileName)
        return file.exists() && file.delete()
    }
}
