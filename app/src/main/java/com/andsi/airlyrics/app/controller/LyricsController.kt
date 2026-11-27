package com.andsi.airlyrics.app.controller

import android.content.Context
import android.net.Uri
import android.util.Log
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.importer.LyricsImportValidator
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity

internal sealed interface LyricsDocumentValidation {
    data object Valid : LyricsDocumentValidation
    data object UnsupportedFormat : LyricsDocumentValidation
    data object TooLarge : LyricsDocumentValidation
}

internal data class LyricsImportAvailability(
    val plainImportEnabled: Boolean,
    val wordByWordImportEnabled: Boolean
)

internal sealed interface LyricsImportOutcome {
    data class ConfirmationRequired(
        val request: PendingLyricsOverwrite
    ) : LyricsImportOutcome

    data class Finished(
        val result: LyricsStorage.ImportLyricsResult,
        val importAsWordByWord: Boolean
    ) : LyricsImportOutcome
}

internal data class CurrentLyricsDeleteOutcome(
    val deleted: Boolean,
    val mode: LyricsStorage.DeleteMode
)

/** Blocking lyrics operations. Callers own coroutine dispatching and UI outcomes. */
internal class LyricsController(
    private val context: Context,
    private val mediaControllerProvider: MediaControllerProvider,
    private val lyricsChangedPublisher: LyricsChangedPublisher,
    private val lyricsImportGateway: LyricsImportGateway = StorageLyricsImportGateway(),
) : LyricsOperations {
    override fun validatePickedDocument(
        uri: Uri
    ): LyricsDocumentValidation {
        if (!LyricsImportValidator.isLikelyLyricsDocument(context, uri)) {
            return LyricsDocumentValidation.UnsupportedFormat
        }
        if (LyricsImportValidator.isLyricsDocumentTooLarge(context, uri)) {
            return LyricsDocumentValidation.TooLarge
        }
        return LyricsDocumentValidation.Valid
    }

    override fun importAvailability(target: SongIdentity): LyricsImportAvailability {
        val localInfo = LyricsStorage.getLocalPlainLyricsInfo(
            context = context,
            title = target.title,
            artist = target.artist,
            duration = target.durationMs
        )
        val hasWordByWordLyrics = LyricsStorage.hasWordByWordLyrics(
            context = context,
            title = target.title,
            artist = target.artist,
            duration = target.durationMs
        )
        return LyricsImportAvailability(
            plainImportEnabled = localInfo?.plainSource == LyricsStorage.SOURCE_PROVIDER_CACHE || !hasWordByWordLyrics,
            wordByWordImportEnabled = localInfo == null || localInfo.plainSource == LyricsStorage.SOURCE_PROVIDER_CACHE ||
                localInfo.plainSource == LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
        )
    }

    override fun importLyricsForTarget(
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean,
        importAsWordByWord: Boolean
    ): LyricsImportOutcome {
        val blockedImportResult = blockedImportResult(target, importAsWordByWord)
        if (blockedImportResult != null) {
            return LyricsImportOutcome.Finished(blockedImportResult, importAsWordByWord)
        }

        if (!overwrite && hasLyricsForTarget(target, importAsWordByWord)) {
            return LyricsImportOutcome.ConfirmationRequired(
                PendingLyricsOverwrite(
                    uri = uri,
                    target = target,
                    type = if (importAsWordByWord) {
                        LyricsImportType.WORD_BY_WORD
                    } else {
                        LyricsImportType.PLAIN
                    }
                )
            )
        }

        val result = if (importAsWordByWord) {
            lyricsImportGateway.importWordByWordLyrics(
                context = context,
                uri = uri,
                target = target,
                overwrite = overwrite
            )
        } else {
            lyricsImportGateway.importPlainLyrics(
                context = context,
                uri = uri,
                target = target,
                overwrite = overwrite
            )
        }

        if (result == LyricsStorage.ImportLyricsResult.Saved) {
            lyricsChangedPublisher.publish(target)
        }
        if (result is LyricsStorage.ImportLyricsResult.RollbackFailed) {
            Log.e(
                "LyricsController",
                "Word-by-word lyrics import rollback failed after " +
                    "${result.originalFailureStep} (${result.originalFailureCause}); " +
                    "failed steps=${result.failedRollbackSteps}"
            )
        }
        return LyricsImportOutcome.Finished(result, importAsWordByWord)
    }

    override fun deleteLyricsForCurrentMedia(
        media: CurrentMediaInfo,
        mode: LyricsStorage.DeleteMode
    ): CurrentLyricsDeleteOutcome {
        val deleted = LyricsStorage.deleteLocalLyrics(
            context = context,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs,
            mode = mode
        )
        if (deleted) {
            lyricsChangedPublisher.publishDeleted(media.toSongIdentity())
        }
        return CurrentLyricsDeleteOutcome(deleted = deleted, mode = mode)
    }

    override fun deleteAllSavedLyrics(): LyricsStorage.DeleteAllSavedLyricsResult {
        val result = LyricsStorage.deleteAllSavedLyrics(context)
        if (result != LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE) {
            lyricsChangedPublisher.publishDeleted()
        }
        return result
    }

    override fun getCurrentMediaInfo(): CurrentMediaInfo? {
        val selectedPackage = MediaSourceStore.getSelectedPackage(context)
        val selectedController = CurrentMediaReader.selectedController(
            controllers = mediaControllerProvider.getActiveControllers(),
            selectedPackage = selectedPackage
        )
        return selectedController?.let(CurrentMediaReader::currentMediaFromController)
    }

    override fun lyricsDirectoryPath(): String = LyricsStorage.getLyricsDirRawPath(context)

    override fun setLyricsDirectory(uri: Uri): Boolean {
        if (!LyricsStorage.validateLyricsDir(context, uri)) return false
        LyricsStorage.saveLyricsDirUri(context, uri)
        return true
    }

    private fun blockedImportResult(
        target: SongIdentity,
        importAsWordByWord: Boolean
    ): LyricsStorage.ImportLyricsResult? {
        if (LyricsStorage.getLocalPlainLyricsInfo(context, target.title, target.artist, target.durationMs)?.plainSource == LyricsStorage.SOURCE_PROVIDER_CACHE) return null
        return if (importAsWordByWord) {
            val localInfo = LyricsStorage.getLocalPlainLyricsInfo(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
            if (localInfo != null &&
                localInfo.plainSource != LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
            ) {
                LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
            } else {
                null
            }
        } else if (
            LyricsStorage.hasWordByWordLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        ) {
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
        } else {
            null
        }
    }

    private fun hasLyricsForTarget(
        target: SongIdentity,
        importAsWordByWord: Boolean
    ): Boolean {
        return if (importAsWordByWord) {
            LyricsStorage.hasWordByWordLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        } else {
            LyricsStorage.hasPlainLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        }
    }
}
