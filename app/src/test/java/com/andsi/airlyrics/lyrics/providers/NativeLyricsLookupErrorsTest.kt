package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLyricsLookupErrorsTest {
    @Test
    fun toNativeLyricsLookupException_preservesStructuredErrorTypeAndMessage() {
        val exception = JSONObject(
            """
            {
              "ok": false,
              "error_type": "RateLimited",
              "error": "too many requests"
            }
            """.trimIndent()
        ).toNativeLyricsLookupException(
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
    fun toNativeLyricsLookupException_usesDefaultMessageWhenNativeMessageIsBlank() {
        val exception = JSONObject(
            """
            {
              "ok": false,
              "error_type": "SerializeError",
              "error": ""
            }
            """.trimIndent()
        ).toNativeLyricsLookupException(
            providerId = "netease",
            providerName = "NetEase Lyrics",
            defaultMessage = "NetEase lookup failed"
        )

        assertEquals(LyricsLookupErrorType.NativeError, exception.errorType)
        assertEquals("NetEase lookup failed", exception.detailMessage)
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
}
