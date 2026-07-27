package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class NativeLyricsResultContractTest {
    @Test
    fun successFixture_mapsThroughNeteaseProductionProvider() {
        val nativeResult =
            requireNotNull(
                NeteaseLyricsProvider.mapNativeResultJson(
                    jsonText = fixtureCase("success"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackDurationMs = 60_000L,
                ),
            )
        val domainResult =
            requireNotNull(NeteaseLyricsProvider.toProviderResult(nativeResult))

        assertEquals("netease-rust", nativeResult.source)
        assertEquals("contract-track-42", nativeResult.songId)
        assertEquals("netease", domainResult.providerId)
        assertEquals("NetEase Lyrics", domainResult.providerName)
        assertEquals("[00:01.00]Original line", domainResult.lyrics)
        assertEquals("[00:01.00]Translated line", domainResult.translatedLyrics)
        assertEquals("Contract Song", domainResult.matchedTitle)
        assertEquals("Contract Artist", domainResult.matchedArtist)
        assertEquals("Contract Album", domainResult.matchedAlbum)
        assertEquals(123_000L, domainResult.matchedDurationMs)
    }

    @Test
    fun translatedFixture_mapsThroughMusixmatchProductionProviderWithRequestedLanguage() {
        ShadowLog.clear()
        val nativeResult =
            requireNotNull(
                MusixmatchLyricsProvider.mapNativeResultJson(
                    jsonText = fixtureCase("translated_success"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackAlbum = "Fallback Album",
                    fallbackDurationMs = 60_000L,
                    translationLanguageCode = "zh",
                ),
            )
        val domainResult =
            requireNotNull(MusixmatchLyricsProvider.toProviderResult(nativeResult))

        assertEquals("musixmatch-rust", nativeResult.source)
        assertEquals("musixmatch", domainResult.providerId)
        assertEquals("Musixmatch", domainResult.providerName)
        assertEquals("[00:03.00]Original subtitle", domainResult.lyrics)
        assertEquals("[00:03.00]Translated subtitle", domainResult.translatedLyrics)
        assertTrue(
            ShadowLog.getLogsForTag("AirLyricsLyrics")
                .any { it.msg.contains("Musixmatch translation found") },
        )
    }

    @Test
    fun providerErrorFixture_mapsThroughMusixmatchProductionProvider() {
        val exception =
            runCatching {
                MusixmatchLyricsProvider.mapNativeResultJson(
                    jsonText = fixtureCase("provider_error"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackAlbum = "Fallback Album",
                    fallbackDurationMs = 60_000L,
                    translationLanguageCode = "zh",
                )
            }.exceptionOrNull()

        assertTrue(exception is LyricsLookupException)
        exception as LyricsLookupException
        assertEquals("musixmatch", exception.providerId)
        assertEquals("Musixmatch", exception.providerName)
        assertEquals(LyricsLookupErrorType.RateLimited, exception.errorType)
        assertEquals("musixmatch rate limit exceeded", exception.detailMessage)
    }

    @Test
    fun nullableFixture_mapsFallbacksThroughMusixmatchProductionProvider() {
        ShadowLog.clear()
        val nativeResult =
            requireNotNull(
                MusixmatchLyricsProvider.mapNativeResultJson(
                    jsonText = fixtureCase("nullable_success"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackAlbum = "Fallback Album",
                    fallbackDurationMs = 60_000L,
                    translationLanguageCode = "zh",
                ),
            )
        val domainResult =
            requireNotNull(MusixmatchLyricsProvider.toProviderResult(nativeResult))

        assertEquals("musixmatch-rust", nativeResult.source)
        assertEquals("Fallback Album", nativeResult.album)
        assertEquals(60_000L, nativeResult.durationMs)
        assertNull(nativeResult.translatedLrc)
        assertNull(nativeResult.errorType)
        assertNull(nativeResult.errorMessage)
        assertEquals("musixmatch", domainResult.providerId)
        assertEquals("Musixmatch", domainResult.providerName)
        assertEquals("[00:02.00]Original only", domainResult.lyrics)
        assertNull(domainResult.translatedLyrics)
        assertTrue(
            ShadowLog.getLogsForTag("AirLyricsLyrics")
                .any { it.msg.contains("Musixmatch translation empty") },
        )
    }

    private fun fixtureCase(name: String): String = fixture.getJSONObject(name).toString()

    private val fixture: JSONObject by lazy {
        val stream =
            requireNotNull(
                javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE),
            ) {
                "Missing shared native result fixture: $FIXTURE_RESOURCE"
            }
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private companion object {
        const val FIXTURE_RESOURCE = "native-contract/native-results.json"
    }
}
