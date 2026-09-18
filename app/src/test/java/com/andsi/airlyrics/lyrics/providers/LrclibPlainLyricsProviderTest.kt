package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import org.json.JSONObject
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

    @Test
    fun explicitTranslationIsPreservedWithoutReinterpretingEmbeddedRows() {
        val embeddedLrc =
            """
            [00:01.00]君の声
            [00:01.00]你的声音
            [00:02.00]夜を越えて
            [00:02.00]跨越黑夜
            [00:03.00]また会える
            [00:03.00]还会再见
            """.trimIndent()
        val explicitTranslation = "[00:01.00]Explicit translation"
        val json = JSONObject()
            .put("ok", true)
            .put("source", "lrclib-rust")
            .put("id", "42")
            .put("lrc", embeddedLrc)
            .put("translated_lrc", explicitTranslation)
            .put("merged_lrc", embeddedLrc)
            .toString()

        val result = requireNotNull(
            LrclibPlainLyricsProvider.mapNativePlainLyricsResultJson(
                jsonText = json,
                fallbackTitle = "Song",
                fallbackArtist = "Artist",
                fallbackAlbum = "Album",
                fallbackDurationMs = 60_000L,
            )
        )

        assertEquals(embeddedLrc, result.lrc)
        assertEquals(explicitTranslation, result.translatedLrc)
    }

    @Test
    fun embeddedTranslationRowsAreSplitAtTheProviderBoundary() {
        val embeddedLrc =
            """
            [00:01.00]君の声
            [00:01.00]你的声音
            [00:02.00]夜を越えて
            [00:02.00]跨越黑夜
            [00:03.00]また会える
            [00:03.00]还会再见
            """.trimIndent()
        val json = JSONObject()
            .put("ok", true)
            .put("source", "lrclib-rust")
            .put("id", "42")
            .put("lrc", embeddedLrc)
            .put("merged_lrc", embeddedLrc)
            .toString()

        val result = requireNotNull(
            LrclibPlainLyricsProvider.mapNativePlainLyricsResultJson(
                jsonText = json,
                fallbackTitle = "Song",
                fallbackArtist = "Artist",
                fallbackAlbum = "Album",
                fallbackDurationMs = 60_000L,
            )
        )

        assertEquals(
            "[00:01.00]君の声\n[00:02.00]夜を越えて\n[00:03.00]また会える",
            result.lrc
        )
        assertEquals(
            "[00:01.00]你的声音\n[00:02.00]跨越黑夜\n[00:03.00]还会再见",
            result.translatedLrc
        )
    }
}
