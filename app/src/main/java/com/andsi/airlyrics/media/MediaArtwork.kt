package com.andsi.airlyrics.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.net.Uri

internal sealed interface MediaArtwork {
    val refreshKey: String

    data class BitmapImage(val bitmap: Bitmap) : MediaArtwork {
        override val refreshKey: String = "bitmap:${bitmap.generationId}"
    }

    data class UriImage(val uri: Uri) : MediaArtwork {
        override val refreshKey: String = "uri:$uri"
    }
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
