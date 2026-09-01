package com.andsi.airlyrics.app.controller

import android.content.Context
import android.net.Uri
import android.util.Log
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.LyricsLookupException
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

internal sealed interface OnlineLyricsSearchOutcome {
    data object Saved : OnlineLyricsSearchOutcome
    data object NotFound : OnlineLyricsSearchOutcome
    data class LookupFailed(val error: LyricsLookupException) : OnlineLyricsSearchOutcome
    data object Failed : OnlineLyricsSearchOutcome
}

/** Blocking lyrics operations. Callers own coroutine dispatching and UI outcomes. */
internal class LyricsController(
    private val context: Context,
    private val mediaControllerProvider: MediaControllerProvider,
    private val lyricsChangedPublisher: LyricsChangedPublisher,
    private val lyricsImportGateway: LyricsImportGateway = StorageLyricsImportGateway(),
    private val onlineLyricsLookupGateway: OnlineLyricsLookupGateway =
        RepositoryOnlineLyricsLookupGateway
) {
    fun validatePickedDocument(
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

    fun importAvailability(target: SongIdentity): LyricsImportAvailability {
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
            plainImportEnabled = !hasWordByWordLyrics,
            wordByWordImportEnabled = localInfo == null ||
                localInfo.plainSource == LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
        )
    }

    fun importLyricsForTarget(
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
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

    fun deleteLyricsForCurrentMedia(
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

    fun deleteSavedLyricsItem(
        item: LyricsStorage.LocalLyricsItem
    ): LyricsStorage.DeleteLocalLyricsItemResult {
        val currentMedia = getCurrentMediaInfo()
        val currentTarget = currentMedia?.toSongIdentity()
        val currentLyricsInfo = currentMedia?.let { media ->
            LyricsStorage.getLocalPlainLyricsInfo(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
        }
        val deletesCurrentItem = currentLyricsInfo?.let { info ->
            if (item.indexKey.isNotBlank()) {
                item.indexKey == info.indexKey
            } else {
                info.indexKey.isBlank() && item.name == info.plainFileName
            }
        } == true
        val result = LyricsStorage.deleteLocalLyricsItem(context, item)
        if (result is LyricsStorage.DeleteLocalLyricsItemResult.Deleted) {
            val deletedTarget = result.target
            val deletesCurrentWordByWordOnlyItem = currentLyricsInfo == null &&
                deletedTarget != null &&
                currentTarget?.isStrongSameSong(deletedTarget) == true
            if (currentTarget != null &&
                (deletesCurrentItem || deletesCurrentWordByWordOnlyItem)
            ) {
                lyricsChangedPublisher.publishDeleted(currentTarget)
            }
        }
        return result
    }

    fun deleteAllSavedLyrics(): LyricsStorage.DeleteAllSavedLyricsResult {
        val result = LyricsStorage.deleteAllSavedLyrics(context)
        if (result != LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE) {
            lyricsChangedPublisher.publishDeleted()
        }
        return result
    }

    fun searchOnlineLyricsForCurrentMedia(
        media: CurrentMediaInfo
    ): OnlineLyricsSearchOutcome {
        val result = onlineLyricsLookupGateway.findAndSave(context, media)
        val foundLyrics = result.getOrNull()?.takeIf { it.plainLrc.isNotBlank() }
        val lookupError = result.exceptionOrNull() as? LyricsLookupException
        return when {
            foundLyrics != null -> {
                lyricsChangedPublisher.publish(media.toSongIdentity())
                OnlineLyricsSearchOutcome.Saved
            }
            lookupError != null -> OnlineLyricsSearchOutcome.LookupFailed(lookupError)
            result.isFailure -> OnlineLyricsSearchOutcome.Failed
            else -> OnlineLyricsSearchOutcome.NotFound
        }
    }

    fun getCurrentMediaInfo(): CurrentMediaInfo? {
        val selectedPackage = MediaSourceStore.getSelectedPackage(context)
        val selectedController = CurrentMediaReader.selectedController(
            controllers = mediaControllerProvider.getActiveControllers(),
            selectedPackage = selectedPackage
        )
        return selectedController?.let(CurrentMediaReader::currentMediaFromController)
    }

    fun lyricsDirectoryPath(): String = LyricsStorage.getLyricsDirRawPath(context)

    fun setLyricsDirectory(uri: Uri): Boolean {
        if (!LyricsStorage.validateLyricsDir(context, uri)) return false
        LyricsStorage.saveLyricsDirUri(context, uri)
        return true
    }

    private fun blockedImportResult(
        target: SongIdentity,
        importAsWordByWord: Boolean
    ): LyricsStorage.ImportLyricsResult? {
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
