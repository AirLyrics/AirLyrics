package com.andsi.airlyrics.app.state

import android.os.Bundle
import com.andsi.airlyrics.core.model.SongIdentity

internal enum class LyricsImportType {
    PLAIN,
    WORD_BY_WORD
}

/** Immutable import request kept while the system document picker is open. */
internal data class PendingLyricsImport(
    val target: SongIdentity,
    val type: LyricsImportType
)

internal fun PendingLyricsImport.toBundle(): Bundle {
    return Bundle().apply {
        putInt(KEY_VERSION, BUNDLE_VERSION)
        putString(KEY_TITLE, target.title)
        putString(KEY_ARTIST, target.artist)
        putString(KEY_ALBUM, target.album)
        putLong(KEY_DURATION_MS, target.durationMs)
        putString(KEY_TYPE, type.name)
    }
}

internal fun Bundle.toPendingLyricsImport(): PendingLyricsImport? {
    if (getInt(KEY_VERSION, 0) != BUNDLE_VERSION) return null

    val title = getString(KEY_TITLE).orEmpty()
    if (title.isBlank()) return null

    val type = getString(KEY_TYPE)
        ?.let { name -> runCatching { LyricsImportType.valueOf(name) }.getOrNull() }
        ?: return null

    return PendingLyricsImport(
        target = SongIdentity(
            title = title,
            artist = getString(KEY_ARTIST).orEmpty(),
            album = getString(KEY_ALBUM).orEmpty(),
            durationMs = getLong(KEY_DURATION_MS, 0L)
        ),
        type = type
    )
}

private const val BUNDLE_VERSION = 1
private const val KEY_VERSION = "version"
private const val KEY_TITLE = "title"
private const val KEY_ARTIST = "artist"
private const val KEY_ALBUM = "album"
private const val KEY_DURATION_MS = "duration_ms"
private const val KEY_TYPE = "type"
