package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import java.security.MessageDigest

internal object LyricsFileNaming {
    const val PLAIN_LYRICS_EXTENSION = ".lrc"
    // Persisted compatibility contract. Do not change the serialized value.
    const val WORD_BY_WORD_LYRICS_EXTENSION = ".karaoke.json"

    private const val MANAGED_KEY_LENGTH = 16
    private val invalidFileNameChars = Regex("[\\\\/:*?\"<>|]")
    private val legacyHashSuffix = Regex("\\s*\\[[0-9a-fA-F]{8}]$")
    private val legacyPlainFileName = Regex(".*\\s\\[[0-9a-fA-F]{8}]\\.lrc$", RegexOption.IGNORE_CASE)

    fun managedPlainFileName(identity: SongIdentity): String {
        return managedFileName(identity.storageKey(), PLAIN_LYRICS_EXTENSION)
    }

    fun managedWordByWordFileName(identity: SongIdentity): String {
        return managedFileName(identity.storageKey(), WORD_BY_WORD_LYRICS_EXTENSION)
    }

    fun legacyPlainFileName(title: String, artist: String, duration: Long): String {
        val safeTitle = title
            .trim()
            .ifBlank { "Unknown Title" }
            .replace(invalidFileNameChars, "_")

        val safeArtist = artist
            .trim()
            .ifBlank { "Unknown Artist" }
            .replace(invalidFileNameChars, "_")

        val key = md5("${title.trim().lowercase()}|${artist.trim().lowercase()}|${duration / 1000L}")
            .take(8)
        return "$safeTitle - $safeArtist [$key]$PLAIN_LYRICS_EXTENSION"
    }

    fun managedRelativePath(fileName: String): String {
        return "$MANAGED_LYRICS_DIR/${baseName(fileName)}"
    }

    fun baseName(fileName: String): String {
        return fileName.substringAfterLast('/')
    }

    fun isPlainLyricsFile(fileName: String?): Boolean {
        return fileName?.endsWith(PLAIN_LYRICS_EXTENSION, ignoreCase = true) == true
    }

    fun isWordByWordLyricsFile(fileName: String?): Boolean {
        return fileName?.endsWith(WORD_BY_WORD_LYRICS_EXTENSION, ignoreCase = true) == true
    }

    fun isLegacyPlainLyricsFile(fileName: String?): Boolean {
        return fileName?.let(legacyPlainFileName::matches) == true
    }

    fun friendlyDisplayName(fileName: String): String {
        val baseName = baseName(fileName)
        return baseName
            .removeKnownExtension()
            .replace(legacyHashSuffix, "")
            .replace('_', ' ')
            .trim()
            .ifBlank { baseName.ifBlank { fileName } }
    }

    private fun managedFileName(storageKey: String, extension: String): String {
        return "${storageKey.take(MANAGED_KEY_LENGTH)}$extension"
    }

    private fun String.removeKnownExtension(): String {
        return when {
            endsWith(WORD_BY_WORD_LYRICS_EXTENSION, ignoreCase = true) -> dropLast(WORD_BY_WORD_LYRICS_EXTENSION.length)
            endsWith(PLAIN_LYRICS_EXTENSION, ignoreCase = true) -> dropLast(PLAIN_LYRICS_EXTENSION.length)
            else -> this
        }
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
