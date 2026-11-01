package com.andsi.airlyrics.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.net.Uri
import androidx.core.graphics.get

internal sealed interface MediaArtwork {
    val refreshKey: String

    data class BitmapImage(val bitmap: Bitmap) : MediaArtwork {
        override val refreshKey: String = bitmap.mediaArtworkRefreshKey()
    }

    data class UriImage(val uri: Uri) : MediaArtwork {
        override val refreshKey: String = "uri:$uri"
    }
}

/** MediaSession may parcel the same image into a new Bitmap on every read. */
internal fun Bitmap.mediaArtworkRefreshKey(contentKey: String = ""): String {
    val size = "${width}x${height}"
    if (config == Bitmap.Config.HARDWARE) return "bitmap:hardware:$size:$contentKey"

    // A bounded grid identifies visible artwork without scanning or copying the whole bitmap.
    var hash = -3750763034362895579L
    for (row in 0..6) {
        val y = (height - 1) * row / 6
        for (column in 0..6) {
            val x = (width - 1) * column / 6
            hash = (hash xor this[x, y].toLong()) * 1099511628211L
        }
    }
    return "bitmap:$size:${hash.toULong().toString(16)}"
}

internal fun MediaMetadata?.mediaArtwork(): MediaArtwork? {
    val metadata = this ?: return null
    metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let {
        return MediaArtwork.BitmapImage(it)
    }
    metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
        return MediaArtwork.BitmapImage(it)
    }
    metadata.localArtworkUri(MediaMetadata.METADATA_KEY_ART_URI)?.let {
        return MediaArtwork.UriImage(it)
    }
    metadata.localArtworkUri(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.let {
        return MediaArtwork.UriImage(it)
    }
    metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let {
        return MediaArtwork.BitmapImage(it)
    }
    return metadata.localArtworkUri(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        ?.let { MediaArtwork.UriImage(it) }
}

private fun MediaMetadata.localArtworkUri(key: String): Uri? = getString(key)
    ?.takeIf(String::isNotBlank)
    ?.let(Uri::parse)
    ?.takeIf { it.scheme in setOf("content", "file", "android.resource") }
