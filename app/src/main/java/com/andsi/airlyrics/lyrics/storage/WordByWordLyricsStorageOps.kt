package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.ParsedWordByWordLyrics

internal object WordByWordLyricsStorageOps {
    fun hasWordByWordLyrics(context: Context, title: String, artist: String, duration: Long): Boolean {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return false
        return entry.wordByWordFile.isNotBlank() && LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile) != null
    }

    fun readWordByWordLyrics(context: Context, title: String, artist: String, duration: Long): List<WordByWordLine> {
        val entry = LyricsIndexStore.find(context, title, artist, duration) ?: return emptyList()
        val wordByWordJson = entry.wordByWordFile
            .takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
            ?: return emptyList()
        return WordByWordLyricsJsonCodec.parseWordByWordLinesJson(wordByWordJson)
    }

    fun saveWordByWordLyrics(
        context: Context,
        title: String,
        artist: String,
        duration: Long,
        wordByWordLines: List<WordByWordLine>,
        album: String = "",
        wordByWordSource: String = LyricsStorage.SOURCE_DOWNLOADED,
        wordByWordProvider: String = "local",
        overwrite: Boolean = true,
        metadataLines: List<String> = emptyList()
    ): Boolean {
        if (wordByWordLines.isEmpty()) return false

        val identity = SongIdentity(title = title, artist = artist, album = album, durationMs = duration)
        val normalizedKey = identity.storageKey()
        val existing = LyricsIndexStore.find(context, title, artist, duration)
        if (existing?.wordByWordFile?.isNotBlank() == true && !overwrite) return false

        val now = System.currentTimeMillis()
        val fileName = LyricsFileNaming.managedWordByWordFileName(identity)
        val relativeFile = LyricsFileNaming.managedRelativePath(fileName)
        val wordByWordJson = WordByWordLyricsJsonCodec.wordByWordLinesToJson(wordByWordLines, metadataLines)
        if (!LyricsFileStore.writeManagedLyrics(context, fileName, wordByWordJson)) return false

        val entries = LyricsIndexStore.read(context)
            .filterNot { it.isSameSong(identity) || it.key == normalizedKey }
            .toMutableList()

        entries += LyricsIndexEntry(
            key = normalizedKey,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration,
            plainFile = existing?.plainFile.orEmpty(),
            wordByWordFile = relativeFile,
            plainSource = existing?.plainSource ?: wordByWordSource,
            plainProvider = existing?.plainProvider ?: "local",
            wordByWordProvider = wordByWordProvider,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        return LyricsIndexStore.write(context, entries)
    }

    fun importWordByWordLyricsFromUriWithResult(
        context: Context,
        uri: Uri,
        title: String,
        artist: String,
        duration: Long,
        album: String = "",
        overwrite: Boolean = true,
        managedLyricsIo: ManagedLyricsIo,
        indexIo: LyricsIndexIo
    ): LyricsStorage.ImportLyricsResult {
        if (hasBlockingPlainLyricsForWordByWordImport(context, title, artist, duration)) {
            return LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
        }

        val wordByWordImportText = when (val result = LyricsFileStore.readTextFromUriWithResult(context, uri)) {
            is LyricsFileStore.ReadTextResult.Success -> result.text
            LyricsFileStore.ReadTextResult.TooLarge -> return LyricsStorage.ImportLyricsResult.TooLarge
            LyricsFileStore.ReadTextResult.Failed -> return LyricsStorage.ImportLyricsResult.ReadFailed
        }
        val document = WordByWordLyricsJsonCodec.parseWordByWordDocumentJson(wordByWordImportText)
        val parsedWordByWordLyrics = if (document.wordByWordLines.isNotEmpty()) {
            document.toParsedWordByWordLyrics()
        } else {
            val validation = WordByWordLrcParser.validateForStorage(wordByWordImportText)
            if (!validation.isValid) {
                return LyricsStorage.ImportLyricsResult.InvalidFormat(validation.invalidLineNumbers)
            }
            WordByWordLrcParser.parseImport(wordByWordImportText)
        }
        val wordByWordLines = parsedWordByWordLyrics.wordByWordLines
        if (wordByWordLines.isEmpty()) return LyricsStorage.ImportLyricsResult.InvalidFormat()

        return LyricsStorage.withStorageLock {
            if (hasBlockingPlainLyricsForWordByWordImport(context, title, artist, duration)) {
                return@withStorageLock LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
            }

            val identity = SongIdentity(
                title = title,
                artist = artist,
                album = album,
                durationMs = duration
            )
            val existing = indexIo.find(context, title, artist, duration)
            if (existing?.wordByWordFile?.isNotBlank() == true && !overwrite) {
                return@withStorageLock LyricsStorage.ImportLyricsResult.SaveFailed
            }
            val importSnapshot = WordByWordImportSnapshot.capture(
                context = context,
                identity = identity,
                managedLyricsIo = managedLyricsIo,
                indexIo = indexIo
            ) ?: return@withStorageLock LyricsStorage.ImportLyricsResult.SnapshotFailed

            val wordByWordFileName = LyricsFileNaming.managedWordByWordFileName(identity)
            val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
            val wordByWordJson =
                WordByWordLyricsJsonCodec.wordByWordLinesToJson(
                    wordByWordLines,
                    parsedWordByWordLyrics.metadataLines
                )
            if (!managedLyricsIo.write(context, wordByWordFileName, wordByWordJson)) {
                return@withStorageLock importSnapshot.rollback(
                    context = context,
                    managedLyricsIo = managedLyricsIo,
                    indexIo = indexIo,
                    originalFailureStep = LyricsStorage.WordByWordImportFailureStep.WORD_BY_WORD_FILE_WRITE,
                    restoreIndex = false
                )
            }
            if (!managedLyricsIo.write(context, plainFileName, parsedWordByWordLyrics.plainLrc)) {
                return@withStorageLock importSnapshot.rollback(
                    context = context,
                    managedLyricsIo = managedLyricsIo,
                    indexIo = indexIo,
                    originalFailureStep = LyricsStorage.WordByWordImportFailureStep.PLAIN_FALLBACK_FILE_WRITE,
                    restoreIndex = false
                )
            }

            val now = System.currentTimeMillis()
            val normalizedKey = identity.storageKey()
            val entries = importSnapshot.indexEntries
                .filterNot { it.isSameSong(identity) || it.key == normalizedKey }
                .toMutableList()
            val committedEntry = LyricsIndexEntry(
                key = normalizedKey,
                title = title.trim(),
                artist = artist.trim(),
                album = album.trim(),
                durationMs = duration,
                plainFile = LyricsFileNaming.managedRelativePath(plainFileName),
                wordByWordFile = LyricsFileNaming.managedRelativePath(wordByWordFileName),
                plainSource = LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
                plainProvider = "local",
                wordByWordProvider = "local",
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            entries += committedEntry

            if (!indexIo.write(context, entries)) {
                return@withStorageLock importSnapshot.rollback(
                    context = context,
                    managedLyricsIo = managedLyricsIo,
                    indexIo = indexIo,
                    originalFailureStep = LyricsStorage.WordByWordImportFailureStep.INDEX_WRITE,
                    restoreIndex = true,
                    committedEntry = committedEntry
                )
            }
            LyricsStorage.ImportLyricsResult.Saved
        }
    }

    fun parseStoredWordByWordText(wordByWordText: String): WordByWordLyricsJsonCodec.WordByWordLyricsDocument {
        val document = WordByWordLyricsJsonCodec.parseWordByWordDocumentJson(wordByWordText)
        if (document.wordByWordLines.isNotEmpty()) return document

        val parsedWordByWordLyrics = WordByWordLrcParser.parseImport(wordByWordText)
        return WordByWordLyricsJsonCodec.WordByWordLyricsDocument(
            wordByWordLines = parsedWordByWordLyrics.wordByWordLines,
            metadataLines = parsedWordByWordLyrics.metadataLines
        )
    }

    fun buildGeneratedPlainFallback(
        context: Context,
        entry: LyricsIndexEntry,
        parsedWordByWordLyrics: ParsedWordByWordLyrics
    ): String {
        if (parsedWordByWordLyrics.hasTranslation) return parsedWordByWordLyrics.plainLrc

        val existingPlainLrc = entry.plainFile
            .takeIf { it.isNotBlank() }
            ?.let { LyricsFileStore.readManagedLyrics(context, it) }
            .orEmpty()
        val translationsByStartMs = plainTranslationsByStartMs(existingPlainLrc)
        if (translationsByStartMs.isEmpty()) return parsedWordByWordLyrics.plainLrc

        return WordByWordLrcParser.wordByWordLinesToPlainLrc(
            wordByWordLines = parsedWordByWordLyrics.wordByWordLines,
            metadataLines = parsedWordByWordLyrics.metadataLines,
            translationsByStartMs = translationsByStartMs
        )
    }

    private fun WordByWordLyricsJsonCodec.WordByWordLyricsDocument.toParsedWordByWordLyrics(): ParsedWordByWordLyrics {
        return ParsedWordByWordLyrics(
            wordByWordLines = wordByWordLines,
            plainLrc = WordByWordLrcParser.wordByWordLinesToPlainLrc(wordByWordLines, metadataLines),
            hasTranslation = false,
            metadataLines = metadataLines
        )
    }

    private fun hasBlockingPlainLyricsForWordByWordImport(
        context: Context,
        title: String,
        artist: String,
        duration: Long
    ): Boolean {
        val localInfo = LyricsStorage.getLocalPlainLyricsInfo(context, title, artist, duration) ?: return false
        return localInfo.plainSource != LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
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

    private data class WordByWordImportSnapshot(
        val indexEntries: List<LyricsIndexEntry>,
        val rawIndex: LyricsIndexStore.RawSnapshot,
        val plainFile: ManagedFileSnapshot,
        val wordByWordFile: ManagedFileSnapshot
    ) {
        fun rollback(
            context: Context,
            managedLyricsIo: ManagedLyricsIo,
            indexIo: LyricsIndexIo,
            originalFailureStep: LyricsStorage.WordByWordImportFailureStep,
            restoreIndex: Boolean,
            committedEntry: LyricsIndexEntry? = null
        ): LyricsStorage.ImportLyricsResult {
            val failedSteps = mutableListOf<LyricsStorage.WordByWordRollbackFailureStep>()
            val indexRestoreFailed = restoreIndex && !indexIo.restoreRaw(context, rawIndex)
            if (indexRestoreFailed) {
                failedSteps += LyricsStorage.WordByWordRollbackFailureStep.RESTORE_INDEX
            }
            val newEntryRemainsAuthoritative =
                indexRestoreFailed &&
                    committedEntry != null &&
                    indexIo.read(context).any { entry -> entry == committedEntry }
            if (!newEntryRemainsAuthoritative) {
                if (!plainFile.restore(context, managedLyricsIo)) {
                    failedSteps += LyricsStorage.WordByWordRollbackFailureStep.RESTORE_PLAIN_FILE
                }
                if (!wordByWordFile.restore(context, managedLyricsIo)) {
                    failedSteps += LyricsStorage.WordByWordRollbackFailureStep.RESTORE_WORD_BY_WORD_FILE
                }
            }
            return if (failedSteps.isEmpty()) {
                LyricsStorage.ImportLyricsResult.SaveFailed
            } else {
                LyricsStorage.ImportLyricsResult.RollbackFailed(
                    originalFailureStep = originalFailureStep,
                    originalFailureCause =
                        LyricsStorage.WordByWordImportFailureCause.IO_OPERATION_RETURNED_FALSE,
                    failedRollbackSteps = failedSteps
                )
            }
        }

        companion object {
            fun capture(
                context: Context,
                identity: SongIdentity,
                managedLyricsIo: ManagedLyricsIo,
                indexIo: LyricsIndexIo
            ): WordByWordImportSnapshot? {
                val rawIndex = indexIo.captureRaw(context)
                if (rawIndex is LyricsIndexStore.RawSnapshot.Unreadable) return null
                val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
                val wordByWordFileName = LyricsFileNaming.managedWordByWordFileName(identity)
                return WordByWordImportSnapshot(
                    indexEntries = indexIo.read(context),
                    rawIndex = rawIndex,
                    plainFile = ManagedFileSnapshot.capture(
                        context,
                        plainFileName,
                        managedLyricsIo
                    ) ?: return null,
                    wordByWordFile = ManagedFileSnapshot.capture(
                        context,
                        wordByWordFileName,
                        managedLyricsIo
                    ) ?: return null
                )
            }
        }
    }

    private sealed class ManagedFileSnapshot {
        abstract val relativeFile: String

        data class Missing(
            override val relativeFile: String
        ) : ManagedFileSnapshot()

        data class Present(
            override val relativeFile: String,
            val content: String
        ) : ManagedFileSnapshot()

        fun restore(context: Context, managedLyricsIo: ManagedLyricsIo): Boolean {
            return when (this) {
                is Missing -> {
                    !managedLyricsIo.exists(context, relativeFile) ||
                        managedLyricsIo.delete(context, relativeFile)
                }
                is Present -> managedLyricsIo.write(
                    context = context,
                    fileName = relativeFile.substringAfterLast('/'),
                    lyrics = content
                )
            }
        }

        companion object {
            fun capture(
                context: Context,
                fileName: String,
                managedLyricsIo: ManagedLyricsIo
            ): ManagedFileSnapshot? {
                val relativeFile = LyricsFileNaming.managedRelativePath(fileName)
                if (!managedLyricsIo.exists(context, relativeFile)) {
                    return Missing(relativeFile)
                }
                val content = managedLyricsIo.read(context, relativeFile) ?: return null
                return Present(relativeFile, content)
            }
        }
    }
}
