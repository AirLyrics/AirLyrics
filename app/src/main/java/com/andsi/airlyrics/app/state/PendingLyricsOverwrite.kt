package com.andsi.airlyrics.app.state

import android.net.Uri
import android.os.Bundle
import com.andsi.airlyrics.core.model.SongIdentity

/** Immutable overwrite request waiting for an explicit user decision. */
internal data class PendingLyricsOverwrite(
    val uri: Uri,
    val target: SongIdentity,
    val type: LyricsImportType
)

internal fun PendingLyricsOverwrite.toBundle(): Bundle {
    return Bundle().apply {
        putInt(KEY_VERSION, BUNDLE_VERSION)
        putString(KEY_URI, uri.toString())
        putString(KEY_TITLE, target.title)
        putString(KEY_ARTIST, target.artist)
        putString(KEY_ALBUM, target.album)
        putLong(KEY_DURATION_MS, target.durationMs)
        putString(KEY_TYPE, type.name)
    }
}

internal fun Bundle.toPendingLyricsOverwrite(): PendingLyricsOverwrite? {
    if (getInt(KEY_VERSION, 0) != BUNDLE_VERSION) return null

    val uri = getString(KEY_URI)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)
        ?: return null
    val title = getString(KEY_TITLE).orEmpty()
    if (title.isBlank()) return null
    val type = getString(KEY_TYPE)
        ?.let { name -> runCatching { LyricsImportType.valueOf(name) }.getOrNull() }
        ?: return null

    return PendingLyricsOverwrite(
        uri = uri,
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
private const val KEY_URI = "uri"
private const val KEY_TITLE = "title"
private const val KEY_ARTIST = "artist"
private const val KEY_ALBUM = "album"
private const val KEY_DURATION_MS = "duration_ms"
private const val KEY_TYPE = "type"
