package com.andsi.airlyrics.lyrics.storage

import java.security.MessageDigest
import java.util.Locale

internal object SongIdentity {
    fun makeStorageKey(title: String, artist: String, duration: Long): String {
        val normalizedDuration = if (duration > 0L) duration / 1000L else 0L
        val raw = "${normalizeText(title)}|${normalizeText(artist)}|$normalizedDuration"
        return sha1(raw)
    }

    fun makeLegacyFileName(title: String, artist: String, duration: Long): String {
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

    fun isSameSong(entry: LyricsIndexEntry, title: String, artist: String, duration: Long): Boolean {
        return isStrongSameSong(entry, title, artist, duration) || isWeakSameSong(entry, title, artist)
    }

    fun isStrongSameSong(entry: LyricsIndexEntry, title: String, artist: String, duration: Long): Boolean {
        if (!isWeakSameSong(entry, title, artist)) return false
        if (duration <= 0L || entry.durationMs <= 0L) return true
        return kotlin.math.abs(entry.durationMs - duration) <= 5_000L
    }

    fun isWeakSameSong(entry: LyricsIndexEntry, title: String, artist: String): Boolean {
        return normalizeText(entry.title) == normalizeText(title) &&
            normalizeText(entry.artist) == normalizeText(artist)
    }

    fun normalizeText(text: String): String {
        return text.trim().lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ")
    }

    private fun sha1(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
