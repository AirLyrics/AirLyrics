package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibPlainLyricsProviderTest {
    @Test
    fun successfulNativeResultMapsToProviderResult() {
        val nativeResult =
            requireNotNull(
                LrclibPlainLyricsProvider.mapNativePlainLyricsResultJson(
                    jsonText =
                        """
                        {
                          "ok": true,
                          "source": "lrclib-rust",
                          "id": "42",
                          "title": "Matched Song",
                          "artist": "Matched Artist",
                          "album": "Matched Album",
                          "duration_ms": 123456,
                          "lrc": "[00:01.00]First line",
                          "translated_lrc": null,
                          "merged_lrc": "[00:01.00]First line",
                          "error_type": null,
                          "error": null
                        }
                        """.trimIndent(),
                    fallbackTitle = "Fallback Song",
                    fallbackArtist = "Fallback Artist",
                    fallbackAlbum = "Fallback Album",
                    fallbackDurationMs = 60_000L,
                )
            )
        val providerResult =
            requireNotNull(LrclibPlainLyricsProvider.toProviderResult(nativeResult))

        assertEquals("lrclib-rust", nativeResult.plainSource)
        assertEquals("42", nativeResult.trackId)
        assertEquals("lrclib", providerResult.plainProviderId)
        assertEquals("LRCLIB", providerResult.plainProviderName)
        assertEquals("[00:01.00]First line", providerResult.plainLrc)
        assertNull(providerResult.translatedLrc)
        assertEquals("Matched Song", providerResult.matchedTitle)
        assertEquals("Matched Artist", providerResult.matchedArtist)
        assertEquals("Matched Album", providerResult.matchedAlbum)
        assertEquals(123_456L, providerResult.matchedDurationMs)
    }

    @Test
    fun notFoundNativeResultMapsToNoLyrics() {
        val result =
            LrclibPlainLyricsProvider.mapNativePlainLyricsResultJson(
                jsonText =
                    """
                    {
                      "ok": false,
                      "source": "lrclib-rust",
                      "error_type": "NotFound",
                      "error": "LRCLIB track not found"
                    }
                    """.trimIndent(),
                fallbackTitle = "Song",
                fallbackArtist = "Artist",
                fallbackAlbum = "Album",
                fallbackDurationMs = 60_000L,
            )

        assertNull(result)
    }

    @Test
    fun operationalNativeErrorPreservesProviderAndErrorType() {
        val error =
            runCatching {
                LrclibPlainLyricsProvider.mapNativePlainLyricsResultJson(
                    jsonText =
                        """
                        {
                          "ok": false,
                          "source": "lrclib-rust",
                          "error_type": "RateLimited",
                          "error": "LRCLIB rate limited"
                        }
                        """.trimIndent(),
                    fallbackTitle = "Song",
                    fallbackArtist = "Artist",
                    fallbackAlbum = "Album",
                    fallbackDurationMs = 60_000L,
                )
            }.exceptionOrNull()

        assertTrue(error is LyricsLookupException)
        error as LyricsLookupException
        assertEquals("lrclib", error.providerId)
        assertEquals("LRCLIB", error.providerName)
        assertEquals(LyricsLookupErrorType.RateLimited, error.errorType)
        assertEquals("LRCLIB rate limited", error.detailMessage)
    }
}
