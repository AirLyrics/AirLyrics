package com.andsi.airlyrics.lyrics.storage

import java.security.MessageDigest

internal object LegacyLyricsFileName {
    fun make(title: String, artist: String, duration: Long): String {
        val safeTitle = title
            .trim()
            .ifBlank { "Unknown Title" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val safeArtist = artist
            .trim()
            .ifBlank { "Unknown Artist" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

        val key = md5("${title.trim().lowercase()}|${artist.trim().lowercase()}|${duration / 1000L}").take(8)
        return "$safeTitle - $safeArtist [$key].lrc"
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
