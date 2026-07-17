package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.parser.KaraokeLrcParser
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.ParsedKaraokeImport

internal object KaraokeLyricsStorageOps {
    fun hasKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return false
        return entry.karaokeFile.isNotBlank() && LyricsFileStore.readManagedLyrics(context, entry.karaokeFile) != null
    }

    fun readKaraokeLyrics(context: Context, title: String, artist: String, duration: Long): List<KaraokeLine> {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return emptyList()
        val rawJson = entry.karaokeFile
            .takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
            ?: return emptyList()
        return KaraokeLyricsCodec.parseJson(rawJson)
    }

    fun saveKaraokeLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        karaokeLines: List<KaraokeLine>,
        album: String = "",
        source: String = LyricsStorage.SOURCE_DOWNLOADED,
        provider: String = "local",
        overwrite: Boolean = true,
        metadataLines: List<String> = emptyList()
    ): Boolean {
        if (karaokeLines.isEmpty()) return false

        val identity = SongIdentity(title = title, artist = artist, album = album, durationMs = duration)
        val normalizedKey = identity.storageKey()
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.karaokeFile?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = LyricsFileNaming.managedKaraokeFileName(identity)
        val relativeFile = LyricsFileNaming.managedRelativePath(fileName)
        val json = KaraokeLyricsCodec.linesToJson(karaokeLines, metadataLines)
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, json)) return false

        val entries = LyricsIndexStore.read(context)
            .filterNot { it.isSameSong(identity) || it.key == normalizedKey }
            .toMutableList()

        entries += LyricsIndexEntry(
            key = normalizedKey,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            file = existing?.file.orEmpty(),
            karaokeFile = relativeFile,
            source = existing?.source ?: source,
            provider = existing?.provider ?: "local",
            karaokeProvider = provider,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        return LyricsIndexStore.write(context, entries)
    }

    fun importKaraokeLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true
    ): LyricsStorage.ImportLyricsResult {
        if (hasBlockingPlainLyricsForKaraokeImport(context, title, artist, duration)) {
            return LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
        }

        val text = when (val result = LyricsFileStore.readTextFromUriWithResult(context, uri)) {
            is LyricsFileStore.ReadTextResult.Success -> result.text
            LyricsFileStore.ReadTextResult.TooLarge -> return LyricsStorage.ImportLyricsResult.TooLarge
            LyricsFileStore.ReadTextResult.Failed -> return LyricsStorage.ImportLyricsResult.ReadFailed
        }
        val document = KaraokeLyricsCodec.parseDocumentJson(text)
        val parsedImport = if (document.lines.isNotEmpty()) {
            document.toParsedKaraokeImport()
        } else {
            val validation = KaraokeLrcParser.validateForStorage(text)
            if (!validation.isValid) {
                return LyricsStorage.ImportLyricsResult.InvalidFormat(validation.invalidLineNumbers)
            }
            KaraokeLrcParser.parseImport(text)
        }
        val lines = parsedImport.karaokeLines
        if (lines.isEmpty()) return LyricsStorage.ImportLyricsResult.InvalidFormat()

        return LyricsStorage.withStorageLock {
            if (hasBlockingPlainLyricsForKaraokeImport(context, title, artist, duration)) {
                return@withStorageLock LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
            }

            val savedKaraoke = saveKaraokeLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                karaokeLines = lines,
                album = album,
                source = LyricsStorage.SOURCE_MANUAL_IMPORT,
                provider = "local",
                overwrite = overwrite,
                metadataLines = parsedImport.metadataLines
            )
            if (!savedKaraoke) return@withStorageLock LyricsStorage.ImportLyricsResult.SaveFailed

            val savedPlain = PlainLyricsStorageOps.saveLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                lyrics = parsedImport.plainLrc,
                album = album,
                source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
                provider = "local",
                overwrite = true
            )

            if (savedPlain) {
                LyricsStorage.ImportLyricsResult.Saved
            } else {
                LyricsStorage.ImportLyricsResult.SaveFailed
            }
        }
    }

    fun parseStoredKaraokeText(rawText: String): KaraokeLyricsCodec.KaraokeLyricsDocument {
        val document = KaraokeLyricsCodec.parseDocumentJson(rawText)
        if (document.lines.isNotEmpty()) return document

        val parsedLrc = KaraokeLrcParser.parseImport(rawText)
        return KaraokeLyricsCodec.KaraokeLyricsDocument(
            lines = parsedLrc.karaokeLines,
            metadataLines = parsedLrc.metadataLines
        )
    }

    fun buildGeneratedPlainFallback(
        context: Context,
        entry: LyricsIndexEntry,
        parsedKaraoke: ParsedKaraokeImport
    ): String {
        if (parsedKaraoke.hasTranslation) return parsedKaraoke.plainLrc

        val existingPlain = entry.file
            .takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
            .orEmpty()
        val translationsByStartMs = plainTranslationsByStartMs(existingPlain)
        if (translationsByStartMs.isEmpty()) return parsedKaraoke.plainLrc

        return KaraokeLrcParser.linesToPlainLrc(
            lines = parsedKaraoke.karaokeLines,
            metadataLines = parsedKaraoke.metadataLines,
            translationsByStartMs = translationsByStartMs
        )
    }

    private fun KaraokeLyricsCodec.KaraokeLyricsDocument.toParsedKaraokeImport(): ParsedKaraokeImport {
        return ParsedKaraokeImport(
            karaokeLines = lines,
            plainLrc = KaraokeLrcParser.linesToPlainLrc(lines, metadataLines),
            hasTranslation = false,
            metadataLines = metadataLines
        )
    }

    private fun hasBlockingPlainLyricsForKaraokeImport(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): Boolean {
        val localInfo = LyricsStorage.getLocalLyricsInfo(context, title, artist, duration) ?: return false
        return localInfo.source != LyricsStorage.SOURCE_KARAOKE_FALLBACK
    }

    private fun plainTranslationsByStartMs(plainLrc: String): Map<Long, List<String>> {
        if (plainLrc.isBlank()) return emptyMap()

        return LrcParser.parse(plainLrc)
            .asSequence()
            .filter { !it.isMetadata && it.hasTranslation() }
            .associate { line ->
                line.timeMs to line.translation.orEmpty()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            .filterValues { it.isNotEmpty() }
    }
}
