package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.parser.LrcParser

internal object PlainLyricsStorageOps {
    fun getLocalPlainLyricsInfo(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LyricsStorage.LocalPlainLyricsInfo? {
        val indexed = LyricsIndexStore.find(context, title, artist, duration)
        if (indexed != null && indexed.plainFile.isNotBlank()) {
            return LyricsStorage.LocalPlainLyricsInfo(
                title = indexed.title,
                artist = indexed.artist,
                durationMs = indexed.durationMs,
                plainFileName = indexed.plainFile.substringAfterLast('/'),
                plainSource = indexed.plainSource,
                plainProvider = indexed.plainProvider,
                updatedAt = indexed.updatedAt,
                indexKey = indexed.key,
                album = indexed.album
            )
        }

        val legacyFileName = LyricsFileNaming.legacyPlainFileName(title, artist, duration)
        val legacyExists = LyricsFileStore.readLegacyPlainLyrics(context, title, artist, duration) != null

        return if (legacyExists) {
            LyricsStorage.LocalPlainLyricsInfo(
                title = title,
                artist = artist,
                durationMs = duration,
                plainFileName = legacyFileName,
                plainSource = LyricsStorage.SOURCE_LEGACY,
                plainProvider = "local",
                updatedAt = 0L
            )
        } else {
            null
        }
    }

    fun readPlainLyrics(context: Context, title: String, artist: String, duration: Long): String? {
        val entry = LyricsIndexStore.find(context, title, artist, duration)
        if (entry?.plainFile?.isNotBlank() == true) {
            LyricsFileStore.readManagedLyrics(context, entry.plainFile)?.let { return it }
        }
        return LyricsFileStore.readLegacyPlainLyrics(context, title, artist, duration)
    }

    fun savePlainLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        plainLrc: String,
        album: String = "",
        plainSource: String = LyricsStorage.SOURCE_DOWNLOADED,
        plainProvider: String = "local",
        overwrite: Boolean = true
    ): Boolean {
        val identity = SongIdentity(title = title, artist = artist, album = album, durationMs = duration)
        val normalizedKey = identity.storageKey()
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.plainFile?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = LyricsFileNaming.managedPlainFileName(identity)
        val relativeFile = LyricsFileNaming.managedRelativePath(fileName)
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, plainLrc)) return false

        val entries = LyricsIndexStore.read(context)
            .filterNot { it.isSameSong(identity) || it.key == normalizedKey }
            .toMutableList()

        entries += LyricsIndexEntry(
            key = normalizedKey,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            plainFile = relativeFile,
            wordByWordFile = existing?.wordByWordFile.orEmpty(),
            plainSource = plainSource,
            plainProvider = plainProvider,
            wordByWordProvider = existing?.wordByWordProvider.orEmpty(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        return LyricsIndexStore.write(context, entries)
    }

    fun importPlainLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): LyricsStorage.ImportLyricsResult {
        if (LyricsStorage.hasWordByWordLyrics(context, title, artist, duration)) {
            return LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
        }

        val plainImportLrc = when (val result = LyricsFileStore.readTextFromUriWithResult(context, uri)) {
            is LyricsFileStore.ReadTextResult.Success -> result.text
            LyricsFileStore.ReadTextResult.TooLarge -> return LyricsStorage.ImportLyricsResult.TooLarge
            LyricsFileStore.ReadTextResult.Failed -> return LyricsStorage.ImportLyricsResult.ReadFailed
        }

        val validation = LrcParser.validateForStorage(plainImportLrc)
        if (!validation.isValid) {
            return LyricsStorage.ImportLyricsResult.InvalidFormat(validation.invalidLineNumbers)
        }

        val normalizedPlainLrc = LrcParser.normalizeForStorage(plainImportLrc)
        if (normalizedPlainLrc.isBlank()) return LyricsStorage.ImportLyricsResult.InvalidFormat()

        return LyricsStorage.withStorageLock {
            if (WordByWordLyricsStorageOps.hasWordByWordLyrics(context, title, artist, duration)) {
                return@withStorageLock LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
            }

            if (savePlainLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                plainLrc = normalizedPlainLrc,
                album = album,
                plainSource = LyricsStorage.SOURCE_MANUAL_IMPORT,
                plainProvider = "local",
                overwrite = overwrite
            )) {
                LyricsStorage.ImportLyricsResult.Saved
            } else {
                LyricsStorage.ImportLyricsResult.SaveFailed
            }
        }
    }
}
