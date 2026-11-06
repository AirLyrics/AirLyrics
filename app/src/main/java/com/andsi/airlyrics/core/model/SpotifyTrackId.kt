package com.andsi.airlyrics.core.model

import java.net.URI

/** Accept track identities only, never episode, local-file or arbitrary-host links. */
object SpotifyTrackId {
    const val PACKAGE_NAME = "com.spotify.music"
    private val idPattern = Regex("[A-Za-z0-9]{22}")
    private val trackPath = Regex("/(?:intl-[A-Za-z-]+/)?track/([A-Za-z0-9]{22})/?")

    fun parse(value: String?): String? {
        val text = value?.trim()?.takeIf { it.length in 1..2048 } ?: return null
        if (idPattern.matches(text)) return text
        if (text.startsWith("spotify:track:")) {
            return text.removePrefix("spotify:track:").takeIf(idPattern::matches)
        }
        return runCatching {
            val uri = URI(text)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("open.spotify.com", ignoreCase = true) ||
                uri.rawUserInfo != null || uri.port != -1
            ) return null
            trackPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1)
        }.getOrNull()
    }

    fun fromMediaSession(sourcePackage: String, mediaId: String?, mediaUri: String?): String? {
        if (sourcePackage != PACKAGE_NAME) return null
        return parse(mediaId) ?: parse(mediaUri)
    }
}
