package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlainLyricsLookupErrorsTest {
    @Test
    fun toNativePlainLyricsLookupException_preservesStructuredErrorTypeAndMessage() {
        val nativeResult = parseNativePlainLyricsResult(
            jsonText = """
            {
              "ok": false,
              "error_type": "RateLimited",
              "error": "too many requests"
            }
            """.trimIndent()
        )

        val exception = nativeResult.toNativePlainLyricsLookupException(
            providerId = "netease",
            providerName = "NetEase Lyrics",
            defaultMessage = "NetEase lookup failed"
        )

        assertEquals("netease", exception.providerId)
        assertEquals("NetEase Lyrics", exception.providerName)
        assertEquals(LyricsLookupErrorType.RateLimited, exception.errorType)
        assertEquals("too many requests", exception.detailMessage)
    }

    @Test
    fun toNativePlainLyricsLookupException_usesDefaultMessageWhenNativeMessageIsBlank() {
        val nativeResult = parseNativePlainLyricsResult(
            jsonText = """
            {
              "ok": false,
              "error_type": "SerializeError",
              "error": ""
            }
            """.trimIndent()
        )

        val exception = nativeResult.toNativePlainLyricsLookupException(
            providerId = "netease",
            providerName = "NetEase Lyrics",
            defaultMessage = "NetEase lookup failed"
        )

        assertEquals(LyricsLookupErrorType.NativeError, exception.errorType)
        assertEquals("NetEase lookup failed", exception.detailMessage)
    }

    @Test
    fun nativePlainLyricsResultParser_extractsCommonSuccessFields() {
        val result = parseNativePlainLyricsResult(
            jsonText = """
            {
              "ok": true,
              "source": "provider-rust",
              "id": "42",
              "title": "Matched title",
              "artist": "Matched artist",
              "album": "Matched album",
              "duration_ms": 123000,
              "lrc": "[00:01]line",
              "merged_lrc": "[00:01]merged",
              "translated_lrc": "[00:01]translated"
            }
            """.trimIndent()
        )

        assertTrue(result.ok)
        assertEquals("provider-rust", result.plainSource)
        assertEquals("42", result.id)
        assertEquals("Matched title", result.title)
        assertEquals("Matched artist", result.artist)
        assertEquals("Matched album", result.album)
        assertEquals(123000L, result.durationMs)
        assertEquals("[00:01]line", result.primaryPlainLrc())
        assertEquals("[00:01]translated", result.translatedLrc)
    }

    @Test
    fun primaryPlainLrc_usesMergedAndOptionalTranslationFallbacks() {
        val mergedOnly = parseNativePlainLyricsResult(
            jsonText = """
            {
              "ok": true,
              "merged_lrc": "[00:01]merged"
            }
            """.trimIndent()
        )
        val translatedOnly = parseNativePlainLyricsResult(
            jsonText = """
            {
              "ok": true,
              "translated_lrc": "[00:01]translated"
            }
            """.trimIndent()
        )

        assertEquals("[00:01]merged", mergedOnly.primaryPlainLrc())
        assertEquals("", translatedOnly.primaryPlainLrc())
        assertEquals("[00:01]translated", translatedOnly.primaryPlainLrc(allowTranslatedFallback = true))
    }

    @Test
    fun recoverNativeLoadFailure_wrapsNativeInitializationFailures() {
        val cause = UnsatisfiedLinkError("no airlyrics_lyrics")
        val result = Result.failure<Unit>(ExceptionInInitializerError(cause))
            .recoverNativeLoadFailure(
                providerId = "musixmatch",
                providerName = "Musixmatch"
            )

        val exception = result.exceptionOrNull()
        assertTrue(exception is LyricsLookupException)
        exception as LyricsLookupException
        assertEquals("musixmatch", exception.providerId)
        assertEquals("Musixmatch", exception.providerName)
        assertEquals(LyricsLookupErrorType.NativeError, exception.errorType)
        assertEquals("Native lyrics core is missing or failed to load", exception.detailMessage)
        assertSame(cause, exception.cause?.cause)
    }

    private fun parseNativePlainLyricsResult(jsonText: String): NativePlainLyricsJsonResult {
        return NativePlainLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = "fallback-source",
            fallbackTitle = "Fallback title",
            fallbackArtist = "Fallback artist",
            fallbackAlbum = "Fallback album",
            fallbackDurationMs = 60_000L
        )
    }
}
