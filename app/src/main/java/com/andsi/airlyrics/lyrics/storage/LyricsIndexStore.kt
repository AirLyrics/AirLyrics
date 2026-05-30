package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object LyricsIndexStore {
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
                    file = obj.optString("file"),
                    karaokeFile = obj.optString("karaokeFile"),
                    source = obj.optString("source", LyricsStorage.SOURCE_DOWNLOADED),
                    provider = obj.optString("provider", "local"),
                    karaokeProvider = obj.optString("karaokeProvider"),
                    createdAt = obj.optLong("createdAt"),
                    updatedAt = obj.optLong("updatedAt")
                )
            }.filter { it.key.isNotBlank() && (it.file.isNotBlank() || it.karaokeFile.isNotBlank()) }
        }.getOrDefault(emptyList())
    }

    fun write(context: Context, entries: List<LyricsIndexEntry>) {
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
        if (LyricsStoragePaths.getLyricsDirUri(context) != null) {
            val file = LyricsStoragePaths.indexDocumentFile(context, create = true) ?: return
            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(text) }
            return
        }

        File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME).writeText(text)
    }

    fun find(context: Context, title: String, artist: String, duration: Long): LyricsIndexEntry? {
        val entries = read(context)
        return entries.firstOrNull { SongIdentity.isStrongSameSong(it, title, artist, duration) }
            ?: entries.firstOrNull { SongIdentity.isWeakSameSong(it, title, artist) }
    }

    fun findByFileName(context: Context, fileName: String): LyricsIndexEntry? {
        val safeName = fileName.substringAfterLast('/')
        return read(context).firstOrNull {
            it.file.substringAfterLast('/') == safeName || it.karaokeFile.substringAfterLast('/') == safeName
        }
    }
}
