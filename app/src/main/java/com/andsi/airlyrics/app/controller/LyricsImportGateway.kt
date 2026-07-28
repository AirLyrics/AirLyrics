package com.andsi.airlyrics.app.controller

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage

internal interface LyricsImportGateway {
    fun importPlainLyrics(
        context: Context,
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean
    ): LyricsStorage.ImportLyricsResult

    fun importWordByWordLyrics(
        context: Context,
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean
    ): LyricsStorage.ImportLyricsResult
}

internal class StorageLyricsImportGateway : LyricsImportGateway {
    override fun importPlainLyrics(
        context: Context,
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean
    ): LyricsStorage.ImportLyricsResult {
        return LyricsStorage.importLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = target.title,
            artist = target.artist,
            duration = target.durationMs,
            album = target.album,
            overwrite = overwrite
        )
    }

    override fun importWordByWordLyrics(
        context: Context,
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean
    ): LyricsStorage.ImportLyricsResult {
        return LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = uri,
            title = target.title,
            artist = target.artist,
            duration = target.durationMs,
            album = target.album,
            overwrite = overwrite
        )
    }
}
