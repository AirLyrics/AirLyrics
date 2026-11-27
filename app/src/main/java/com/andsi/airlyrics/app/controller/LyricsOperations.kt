package com.andsi.airlyrics.app.controller

import android.net.Uri
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo

internal interface LyricsOperations {
    fun validatePickedDocument(uri: Uri): LyricsDocumentValidation

    fun importAvailability(target: SongIdentity): LyricsImportAvailability

    fun importLyricsForTarget(
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
    ): LyricsImportOutcome

    fun deleteLyricsForCurrentMedia(
        media: CurrentMediaInfo,
        mode: LyricsStorage.DeleteMode
    ): CurrentLyricsDeleteOutcome

    fun deleteAllSavedLyrics(): LyricsStorage.DeleteAllSavedLyricsResult

    fun getCurrentMediaInfo(): CurrentMediaInfo?

    fun lyricsDirectoryPath(): String

    fun setLyricsDirectory(uri: Uri): Boolean
}
