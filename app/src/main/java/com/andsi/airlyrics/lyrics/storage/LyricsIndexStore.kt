package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object LyricsIndexStore {
    // Persisted compatibility contract. Do not change the serialized values.
    private const val WORD_BY_WORD_FILE_JSON_FIELD = "karaokeFile"
    private const val WORD_BY_WORD_PROVIDER_JSON_FIELD = "karaokeProvider"

    sealed class RawSnapshot {
        object Missing : RawSnapshot()
        object Unreadable : RawSnapshot()
        class Present(val bytes: ByteArray) : RawSnapshot()
    }

    fun read(context: Context): List<LyricsIndexEntry> {
        val text = if (LyricsStoragePaths.getLyricsDirUri(context) != null) {
            val file = LyricsStoragePaths.indexDocumentFile(context, create = false) ?: return emptyList()
            context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        } else {
            val file = File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)
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
                    plainFile = obj.optString("file"),
                    wordByWordFile = obj.optString(WORD_BY_WORD_FILE_JSON_FIELD),
                    plainSource = obj.optString("source", LyricsStorage.SOURCE_DOWNLOADED),
                    plainProvider = obj.optString("provider", "local"),
                    wordByWordProvider = obj.optString(WORD_BY_WORD_PROVIDER_JSON_FIELD),
                    createdAt = obj.optLong("createdAt"),
                    updatedAt = obj.optLong("updatedAt")
                )
            }.filter { it.key.isNotBlank() && (it.plainFile.isNotBlank() || it.wordByWordFile.isNotBlank()) }
        }.getOrDefault(emptyList())
    }

    fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean {
        val array = JSONArray()
        entries.sortedByDescending { it.updatedAt }.forEach { entry ->
            array.put(JSONObject().apply {
                put("key", entry.key)
                put("title", entry.title)
                put("artist", entry.artist)
                put("album", entry.album)
                put("durationMs", entry.durationMs)
                put("file", entry.plainFile)
                put(WORD_BY_WORD_FILE_JSON_FIELD, entry.wordByWordFile)
                put("source", entry.plainSource)
                put("provider", entry.plainProvider)
                put(WORD_BY_WORD_PROVIDER_JSON_FIELD, entry.wordByWordProvider)
                put("createdAt", entry.createdAt)
                put("updatedAt", entry.updatedAt)
            })
        }

        val text = array.toString(2)
        return runCatching {
            if (LyricsStoragePaths.getLyricsDirUri(context) != null) {
                val file = LyricsStoragePaths.indexDocumentFile(context, create = true) ?: return false
                context.contentResolver.openOutputStream(file.uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(text) }
                    ?: return false
                return true
            }

            File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME).writeText(text)
            true
        }.getOrDefault(false)
    }

    fun captureRaw(context: Context): RawSnapshot {
        return if (LyricsStoragePaths.getLyricsDirUri(context) != null) {
            val file = LyricsStoragePaths.indexDocumentFile(context, create = false)
                ?: return RawSnapshot.Missing
            val bytes = runCatching {
                context.contentResolver.openInputStream(file.uri)
                    ?.use { it.readBytes() }
            }.getOrNull() ?: return RawSnapshot.Unreadable
            RawSnapshot.Present(bytes)
        } else {
            val file = File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)
            if (!file.exists()) return RawSnapshot.Missing
            runCatching<RawSnapshot> { RawSnapshot.Present(file.readBytes()) }
                .getOrDefault(RawSnapshot.Unreadable)
        }
    }

    fun restoreRaw(context: Context, snapshot: RawSnapshot): Boolean {
        return when (snapshot) {
            RawSnapshot.Unreadable -> false
            RawSnapshot.Missing -> deleteRaw(context)
            is RawSnapshot.Present -> writeRaw(context, snapshot.bytes)
        }
    }

    private fun writeRaw(context: Context, bytes: ByteArray): Boolean {
        return runCatching {
            if (LyricsStoragePaths.getLyricsDirUri(context) != null) {
                val file = LyricsStoragePaths.indexDocumentFile(context, create = true) ?: return false
                context.contentResolver.openOutputStream(file.uri, "wt")
                    ?.use { it.write(bytes) }
                    ?: return false
                return true
            }

            File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME).writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    private fun deleteRaw(context: Context): Boolean {
        return if (LyricsStoragePaths.getLyricsDirUri(context) != null) {
            val file = LyricsStoragePaths.indexDocumentFile(context, create = false)
                ?: return true
            file.delete()
        } else {
            val file = File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)
            !file.exists() || file.delete()
        }
    }

    fun find(context: Context, title: String, artist: String, duration: Long): LyricsIndexEntry? {
        val entries = read(context)
        val identity = SongIdentity(title = title, artist = artist, durationMs = duration)
        return entries.firstOrNull { it.isStrongSameSong(identity) }
            ?: entries.firstOrNull { it.isWeakSameSong(identity) }
    }

    fun findByFileName(context: Context, fileName: String): LyricsIndexEntry? {
        val safeName = fileName.substringAfterLast('/')
        return read(context).firstOrNull {
            it.plainFile.substringAfterLast('/') == safeName || it.wordByWordFile.substringAfterLast('/') == safeName
        }
    }
}

internal interface LyricsIndexIo {
    fun read(context: Context): List<LyricsIndexEntry>
    fun find(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LyricsIndexEntry?
    fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean
    fun captureRaw(context: Context): LyricsIndexStore.RawSnapshot
    fun restoreRaw(context: Context, snapshot: LyricsIndexStore.RawSnapshot): Boolean
}

internal object AndroidLyricsIndexIo : LyricsIndexIo {
    override fun read(context: Context): List<LyricsIndexEntry> {
        return LyricsIndexStore.read(context)
    }

    override fun find(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LyricsIndexEntry? {
        return LyricsIndexStore.find(context, title, artist, duration)
    }

    override fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean {
        return LyricsIndexStore.write(context, entries)
    }

    override fun captureRaw(context: Context): LyricsIndexStore.RawSnapshot {
        return LyricsIndexStore.captureRaw(context)
    }

    override fun restoreRaw(
        context: Context,
        snapshot: LyricsIndexStore.RawSnapshot
    ): Boolean {
        return LyricsIndexStore.restoreRaw(context, snapshot)
    }
}
