package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class NativePlainLyricsResultContractTest {
    @Test
    fun sharedFixture_omitsRemovedKaraokeJsonField() {
        FIXTURE_CASES.forEach { caseName ->
            assertFalse(
                "$caseName must not contain removed karaoke_json field",
                fixture.getJSONObject(caseName).has("karaoke_json"),
            )
        }
    }

    @Test
    fun successFixture_mapsThroughNeteasePlainLyricsProductionProvider() {
        val nativeResult =
            requireNotNull(
                NeteasePlainLyricsProvider.mapNativePlainLyricsResultJson(
                    jsonText = fixtureCase("success"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackDurationMs = 60_000L,
                ),
            )
        val domainResult =
            requireNotNull(NeteasePlainLyricsProvider.toProviderResult(nativeResult))

        assertEquals("netease-rust", nativeResult.plainSource)
        assertEquals("contract-track-42", nativeResult.songId)
        assertEquals("netease", domainResult.plainProviderId)
        assertEquals("NetEase Lyrics", domainResult.plainProviderName)
        assertEquals("[00:01.00]Original line", domainResult.plainLrc)
        assertEquals("[00:01.00]Translated line", domainResult.translatedLrc)
        assertEquals("Contract Song", domainResult.matchedTitle)
        assertEquals("Contract Artist", domainResult.matchedArtist)
        assertEquals("Contract Album", domainResult.matchedAlbum)
        assertEquals(123_000L, domainResult.matchedDurationMs)
    }

    @Test
    fun translatedFixture_mapsThroughMusixmatchPlainLyricsProductionProviderWithRequestedLanguage() {
        ShadowLog.clear()
        val nativeResult =
            requireNotNull(
                MusixmatchPlainLyricsProvider.mapNativePlainLyricsResultJson(
                    jsonText = fixtureCase("translated_success"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackAlbum = "Fallback Album",
                    fallbackDurationMs = 60_000L,
                    translationLanguageCode = "zh",
                ),
            )
        val domainResult =
            requireNotNull(MusixmatchPlainLyricsProvider.toProviderResult(nativeResult))

        assertEquals("musixmatch-rust", nativeResult.plainSource)
        assertEquals("musixmatch", domainResult.plainProviderId)
        assertEquals("Musixmatch", domainResult.plainProviderName)
        assertEquals("[00:03.00]Original subtitle", domainResult.plainLrc)
        assertEquals("[00:03.00]Translated subtitle", domainResult.translatedLrc)
        assertTrue(
            ShadowLog.getLogsForTag("AirLyricsLyrics")
                .any { it.msg.contains("Musixmatch translation found") },
        )
    }

    @Test
    fun providerErrorFixture_mapsThroughMusixmatchPlainLyricsProductionProvider() {
        val exception =
            runCatching {
                MusixmatchPlainLyricsProvider.mapNativePlainLyricsResultJson(
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
    fun nullableFixture_mapsFallbacksThroughMusixmatchPlainLyricsProductionProvider() {
        ShadowLog.clear()
        val nativeResult =
            requireNotNull(
                MusixmatchPlainLyricsProvider.mapNativePlainLyricsResultJson(
                    jsonText = fixtureCase("nullable_success"),
                    fallbackTitle = "Fallback Title",
                    fallbackArtist = "Fallback Artist",
                    fallbackAlbum = "Fallback Album",
                    fallbackDurationMs = 60_000L,
                    translationLanguageCode = "zh",
                ),
            )
        val domainResult =
            requireNotNull(MusixmatchPlainLyricsProvider.toProviderResult(nativeResult))

        assertEquals("musixmatch-rust", nativeResult.plainSource)
        assertEquals("Fallback Album", nativeResult.album)
        assertEquals(60_000L, nativeResult.durationMs)
        assertNull(nativeResult.translatedLrc)
        assertNull(nativeResult.errorType)
        assertNull(nativeResult.errorMessage)
        assertEquals("musixmatch", domainResult.plainProviderId)
        assertEquals("Musixmatch", domainResult.plainProviderName)
        assertEquals("[00:02.00]Original only", domainResult.plainLrc)
        assertNull(domainResult.translatedLrc)
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
        val FIXTURE_CASES =
            listOf("success", "translated_success", "provider_error", "nullable_success")
    }
}
