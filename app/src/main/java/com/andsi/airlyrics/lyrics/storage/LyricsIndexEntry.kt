package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity

internal data class LyricsIndexEntry(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val plainFile: String,
    val wordByWordFile: String = "",
    val plainSource: String,
    val plainProvider: String,
    val wordByWordProvider: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    // Provenance of the online match that produced this file: what the source actually returned.
    // Empty on entries written by older versions (no provenance available).
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val matchedDurationMs: Long = 0L
)

internal fun LyricsIndexEntry.toSongIdentity(): SongIdentity {
    return SongIdentity(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )
}

internal fun LyricsIndexEntry.isSameSong(identity: SongIdentity): Boolean {
    return toSongIdentity().isSameSong(identity)
}

internal fun LyricsIndexEntry.isStrongSameSong(identity: SongIdentity): Boolean {
    return toSongIdentity().isStrongSameSong(identity)
}

internal fun LyricsIndexEntry.isWeakSameSong(identity: SongIdentity): Boolean {
    return toSongIdentity().isWeakSameSong(identity)
}
