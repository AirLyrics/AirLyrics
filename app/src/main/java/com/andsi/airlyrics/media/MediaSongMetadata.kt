package com.andsi.airlyrics.media

import android.media.MediaMetadata

internal data class MediaSongMetadata(val title: String?, val artist: String?)

internal fun resolveMediaSongMetadata(
    packageName: String,
    metadata: MediaMetadata?
): MediaSongMetadata {
    if (packageName == "com.netease.cloudmusic") {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        // Bluetooth lyrics replace TITLE with the lyric and ARTIST with "song - artist".
        // NetEase keeps the actual song identity in the paired display fields.
        if (!title.isNullOrBlank() && !artist.isNullOrBlank()) {
            return MediaSongMetadata(title, artist)
        }
    }
    return MediaSongMetadata(
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    )
}
