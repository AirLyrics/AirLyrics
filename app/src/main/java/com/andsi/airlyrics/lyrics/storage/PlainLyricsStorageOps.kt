package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.parser.LrcParser

internal object PlainLyricsStorageOps {
    fun getLocalLyricsInfo(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): LyricsStorage.LocalLyricsInfo? {
        val indexed = LyricsIndexStore.find(context, title, artist, duration)
        if (indexed != null && indexed.file.isNotBlank()) {
            return LyricsStorage.LocalLyricsInfo(
                title = indexed.title,
                artist = indexed.artist,
                durationMs = indexed.durationMs,
                fileName = indexed.file.substringAfterLast('/'),
                source = indexed.source,
                provider = indexed.provider,
                updatedAt = indexed.updatedAt
            )
        }

        val legacyFileName = LyricsFileNaming.legacyPlainFileName(title, artist, duration)
        val legacyExists = LyricsFileStore.readLegacyLyrics(context, title, artist, duration) != null

        return if (legacyExists) {
            LyricsStorage.LocalLyricsInfo(
                title = title,
                artist = artist,
                durationMs = duration,
                fileName = legacyFileName,
                source = LyricsStorage.SOURCE_LEGACY,
                provider = "local",
                updatedAt = 0L
            )
        } else {
            null
        }
    }

    fun readLocalLyrics(context: Context, title: String, artist: String, duration: Long): String? {
        val entry = LyricsIndexStore.find(context, title, artist, duration)
        if (entry?.file?.isNotBlank() == true) {
            LyricsFileStore.readManagedLyrics(context, entry.file)?.let { return it }
        }
        return LyricsFileStore.readLegacyLyrics(context, title, artist, duration)
    }

    fun saveLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        lyrics: String,
        album: String = "",
        source: String = LyricsStorage.SOURCE_DOWNLOADED,
        provider: String = "local",
        overwrite: Boolean = true
    ): Boolean {
        val identity = SongIdentity(title = title, artist = artist, album = album, durationMs = duration)
        val normalizedKey = identity.storageKey()
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.file?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = LyricsFileNaming.managedPlainFileName(identity)
        val relativeFile = LyricsFileNaming.managedRelativePath(fileName)
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, lyrics)) return false

        val entries = LyricsIndexStore.read(context)
            .filterNot { it.isSameSong(identity) || it.key == normalizedKey }
            .toMutableList()

        entries += LyricsIndexEntry(
            key = normalizedKey,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            file = relativeFile,
            karaokeFile = existing?.karaokeFile.orEmpty(),
            source = source,
            provider = provider,
            karaokeProvider = existing?.karaokeProvider.orEmpty(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        return LyricsIndexStore.write(context, entries)
    }

    fun importLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): LyricsStorage.ImportLyricsResult {
        if (LyricsStorage.hasKaraokeLyrics(context, title, artist, duration)) {
            return LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
        }

        val text = when (val result = LyricsFileStore.readTextFromUriWithResult(context, uri)) {
            is LyricsFileStore.ReadTextResult.Success -> result.text
            LyricsFileStore.ReadTextResult.TooLarge -> return LyricsStorage.ImportLyricsResult.TooLarge
            LyricsFileStore.ReadTextResult.Failed -> return LyricsStorage.ImportLyricsResult.ReadFailed
        }

        val validation = LrcParser.validateForStorage(text)
        if (!validation.isValid) {
            return LyricsStorage.ImportLyricsResult.InvalidFormat(validation.invalidLineNumbers)
        }

        val normalizedLyrics = LrcParser.normalizeForStorage(text)
        if (normalizedLyrics.isBlank()) return LyricsStorage.ImportLyricsResult.InvalidFormat()

        return LyricsStorage.withStorageLock {
            if (KaraokeLyricsStorageOps.hasKaraokeLyrics(context, title, artist, duration)) {
                return@withStorageLock LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
            }

            if (saveLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                lyrics = normalizedLyrics,
                album = album,
                source = LyricsStorage.SOURCE_MANUAL_IMPORT,
                provider = "local",
                overwrite = overwrite
            )) {
                LyricsStorage.ImportLyricsResult.Saved
            } else {
                LyricsStorage.ImportLyricsResult.SaveFailed
            }
        }
    }
}
