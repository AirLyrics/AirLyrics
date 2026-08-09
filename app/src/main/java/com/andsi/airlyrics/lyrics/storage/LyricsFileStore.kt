package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import android.util.Log
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

    data class DeleteAllFilesResult(
        val foundAny: Boolean,
        val deletedAll: Boolean
    )

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

    fun readPlainLyricsFileByName(context: Context, plainFileName: String): String? {
        val safeName = plainFileName.substringAfterLast('/')
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

    fun writePlainLyricsFileByName(context: Context, plainFileName: String, plainLrc: String): Boolean {
        return runCatching {
            val safeName = plainFileName.substringAfterLast('/')
            val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

            if (treeUri != null) {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
                val rootFile = root.findFile(safeName)
                if (rootFile != null) {
                    context.contentResolver.openOutputStream(rootFile.uri, "wt")
                        ?.bufferedWriter()
                        ?.use { it.write(plainLrc) }
                        ?: return false
                    return true
                }

                val managedFile = LyricsStoragePaths.managedDocumentDir(context)?.findFile(safeName) ?: return false
                context.contentResolver.openOutputStream(managedFile.uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(plainLrc) }
                    ?: return false
                return true
            }

            val managedFile = File(LyricsStoragePaths.fallbackManagedLyricsDir(context), safeName)
            if (managedFile.exists()) {
                managedFile.writeText(plainLrc)
                return true
            }

            val legacyFile = File(LyricsStoragePaths.fallbackLyricsDir(context), safeName)
            if (legacyFile.exists()) {
                legacyFile.writeText(plainLrc)
                return true
            }

            false
        }.getOrDefault(false)
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

    fun managedLyricsExists(context: Context, relativeFile: String): Boolean {
        val fileName = relativeFile.substringAfterLast('/')
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)
        return if (treeUri != null) {
            LyricsStoragePaths.managedDocumentDir(context)?.findFile(fileName)?.isFile == true
        } else {
            File(LyricsStoragePaths.fallbackManagedLyricsDir(context), fileName).isFile
        }
    }

    fun writeManagedLyrics(context: Context, fileName: String, lyrics: String): Boolean {
        return runCatching {
            val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

            if (treeUri != null) {
                val dir = LyricsStoragePaths.managedDocumentDir(context) ?: return false
                val existingFile = dir.findFile(fileName)
                val file = existingFile
                    ?: dir.createFile("application/octet-stream", fileName)
                    ?: return false
                val createdForThisWrite = existingFile == null
                val written =
                    runCatching {
                        context.contentResolver.openOutputStream(file.uri, "wt")
                            ?.bufferedWriter()
                            ?.use { it.write(lyrics) }
                            ?: return@runCatching false
                        true
                    }.getOrDefault(false)

                if (!written && createdForThisWrite) {
                    val deleted = runCatching { file.delete() }.getOrDefault(false)
                    if (!deleted) {
                        Log.e(
                            "LyricsFileStore",
                            "Unable to remove newly created SAF file after write failure: $fileName",
                        )
                    }
                }
                return written
            }

            File(LyricsStoragePaths.fallbackManagedLyricsDir(context), fileName).writeText(lyrics)
            true
        }.getOrDefault(false)
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

    fun readLegacyPlainLyrics(context: Context, title: String, artist: String, duration: Long): String? {
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)
        val fileName = LyricsFileNaming.legacyPlainFileName(title, artist, duration)

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

    fun deleteLegacyPlainLyrics(context: Context, plainFileName: String): Boolean {
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        if (treeUri != null) {
            return DocumentFile.fromTreeUri(context, treeUri)?.findFile(plainFileName)?.delete() == true
        }

        val file = File(LyricsStoragePaths.fallbackLyricsDir(context), plainFileName)
        return file.exists() && file.delete()
    }

    fun deleteAllSavedLyricsFiles(
        context: Context,
        indexedRelativeFiles: Collection<String>
    ): DeleteAllFilesResult {
        val indexedFileNames = indexedRelativeFiles
            .asSequence()
            .filter { it.isNotBlank() }
            .map { it.substringAfterLast('/') }
            .toSet()
        val treeUri = LyricsStoragePaths.getLyricsDirUri(context)

        return if (treeUri != null) {
            deleteAllSavedLyricsDocuments(context, treeUri, indexedFileNames)
        } else {
            deleteAllSavedLyricsFallbackFiles(context, indexedFileNames)
        }
    }

    private fun deleteAllSavedLyricsDocuments(
        context: Context,
        treeUri: Uri,
        indexedFileNames: Set<String>
    ): DeleteAllFilesResult {
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?.takeIf { it.canRead() && it.canWrite() }
                ?: return DeleteAllFilesResult(foundAny = indexedFileNames.isNotEmpty(), deletedAll = false)
            val managedFiles = root.findFile(MANAGED_LYRICS_DIR)
                ?.takeIf { it.isDirectory }
                ?.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile && isManagedLyricsCandidate(file.name, indexedFileNames)
                }
            val legacyFiles = root.listFiles().filter { file ->
                file.isFile && LyricsFileNaming.isLegacyPlainLyricsFile(file.name)
            }
            val candidates = (managedFiles + legacyFiles).distinctBy { it.uri }
            DeleteAllFilesResult(
                foundAny = candidates.isNotEmpty(),
                deletedAll = candidates.map { file -> file.delete() }.all { it }
            )
        }.getOrElse {
            DeleteAllFilesResult(foundAny = indexedFileNames.isNotEmpty(), deletedAll = false)
        }
    }

    private fun deleteAllSavedLyricsFallbackFiles(
        context: Context,
        indexedFileNames: Set<String>
    ): DeleteAllFilesResult {
        return runCatching {
            val root = LyricsStoragePaths.fallbackLyricsDir(context)
            val managedFiles = File(root, MANAGED_LYRICS_DIR).listFiles().orEmpty()
                .filter { file -> file.isFile && isManagedLyricsCandidate(file.name, indexedFileNames) }
            val legacyFiles = root.listFiles().orEmpty()
                .filter { file -> file.isFile && LyricsFileNaming.isLegacyPlainLyricsFile(file.name) }
            val candidates = (managedFiles + legacyFiles).distinctBy { it.absolutePath }
            DeleteAllFilesResult(
                foundAny = candidates.isNotEmpty(),
                deletedAll = candidates.map { file -> file.delete() }.all { it }
            )
        }.getOrElse {
            DeleteAllFilesResult(foundAny = indexedFileNames.isNotEmpty(), deletedAll = false)
        }
    }

    private fun isManagedLyricsCandidate(fileName: String?, indexedFileNames: Set<String>): Boolean {
        return (fileName != null && fileName in indexedFileNames) ||
            LyricsFileNaming.isPlainLyricsFile(fileName) ||
            LyricsFileNaming.isWordByWordLyricsFile(fileName)
    }
}

internal interface ManagedLyricsIo {
    fun exists(context: Context, relativeFile: String): Boolean
    fun read(context: Context, relativeFile: String): String?
    fun write(context: Context, fileName: String, lyrics: String): Boolean
    fun delete(context: Context, relativeFile: String): Boolean
}

internal object AndroidManagedLyricsIo : ManagedLyricsIo {
    override fun exists(context: Context, relativeFile: String): Boolean {
        return LyricsFileStore.managedLyricsExists(context, relativeFile)
    }

    override fun read(context: Context, relativeFile: String): String? {
        return LyricsFileStore.readManagedLyrics(context, relativeFile)
    }

    override fun write(context: Context, fileName: String, lyrics: String): Boolean {
        return LyricsFileStore.writeManagedLyrics(context, fileName, lyrics)
    }

    override fun delete(context: Context, relativeFile: String): Boolean {
        return LyricsFileStore.deleteManagedLyrics(context, relativeFile)
    }
}
