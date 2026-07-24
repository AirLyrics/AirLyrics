package com.andsi.airlyrics.core.model

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

data class SongIdentity(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long
) {
    val durationSeconds: Long
        get() = durationMs.toDurationSeconds()

    fun storageKey(): String {
        return storageKeyAtDurationSeconds(durationSeconds)
    }

    fun storageKeyAtDurationSeconds(durationSeconds: Long): String {
        return storageKeyForDurationSeconds(
            title = title,
            artist = artist,
            durationSeconds = durationSeconds
        )
    }

    internal fun legacyStorageKeysAtDurationSeconds(
        durationSeconds: Iterable<Long>,
        currentLocale: Locale
    ): List<String> {
        val locales = listOf(
            currentLocale,
            Locale.forLanguageTag("tr"),
            Locale.forLanguageTag("az"),
            Locale.forLanguageTag("lt")
        )
        return buildList {
            locales.forEach { locale ->
                durationSeconds.forEach { seconds ->
                    add(
                        storageKeyForDurationSeconds(
                            title = title,
                            artist = artist,
                            durationSeconds = seconds,
                            locale = locale
                        )
                    )
                }
            }
        }.distinct()
    }

    fun isSameSong(other: SongIdentity): Boolean {
        return isStrongSameSong(other) || isWeakSameSong(other)
    }

    fun isStrongSameSong(other: SongIdentity): Boolean {
        if (!isWeakSameSong(other)) return false
        if (durationMs <= 0L || other.durationMs <= 0L) return true
        return abs(durationMs - other.durationMs) <= DURATION_TOLERANCE_MS
    }

    fun isWeakSameSong(other: SongIdentity): Boolean {
        return normalizeText(title) == normalizeText(other.title) &&
            normalizeText(artist) == normalizeText(other.artist)
    }

    companion object {
        private const val DURATION_TOLERANCE_MS = 5_000L

        fun storageKeyForDurationSeconds(title: String, artist: String, durationSeconds: Long): String {
            return storageKeyForDurationSeconds(
                title = title,
                artist = artist,
                durationSeconds = durationSeconds,
                locale = Locale.ROOT
            )
        }

        private fun storageKeyForDurationSeconds(
            title: String,
            artist: String,
            durationSeconds: Long,
            locale: Locale
        ): String {
            val normalizedDuration = durationSeconds.coerceAtLeast(0L)
            val raw = "${normalizeText(title, locale)}|${normalizeText(artist, locale)}|$normalizedDuration"
            return sha1(raw)
        }

        fun normalizeText(text: String): String {
            return normalizeText(text, Locale.getDefault())
        }

        private fun normalizeText(text: String, locale: Locale): String {
            return text.trim().lowercase(locale).replace(Regex("\\s+"), " ")
        }

        private fun sha1(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

private fun Long.toDurationSeconds(): Long {
    return if (this > 0L) this / 1000L else 0L
}
