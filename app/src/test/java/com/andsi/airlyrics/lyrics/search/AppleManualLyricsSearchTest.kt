package com.andsi.airlyrics.lyrics.search

import com.andsi.airlyrics.apple.AppleMusicSession
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.providers.AppleManualLyricsRequest
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.LyricsStorageTestBase
import com.andsi.airlyrics.lyrics.storage.ManualLyricsSelectionStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import com.andsi.airlyrics.core.text.ChineseText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppleManualLyricsSearchTest : LyricsStorageTestBase() {
    @Before fun installChineseTable() { ChineseText.installTableForTest(mapOf('愛' to '爱')) }
    @After fun resetChineseTable() { ChineseText.resetForTest() }
    private val target = SongIdentity("Player title", "Artist", "Album", 200000)
    private val candidate = LyricsCandidate(PlainLyricsSearchSource.APPLE, "42",
        target.copy(title = "Catalogue title"), 0.9, "tr")
    private fun token() = LyricsLookupCancellationToken(target.storageKey(), 1)
    private val payload = """{"ok":true,"id":"42","title":"Catalogue title","artist":"Artist","album":"Album","duration_ms":200000,
        "lrc":"[00:01.00]愛你","translated_lrc":"[00:01.00]爱你",
        "word_by_word":[{"start_ms":1000,"end_ms":2000,"text":"愛你","translated_text":"爱你",
        "segments":[{"text":"愛你","start_ms":1000,"end_ms":2000}]}]}"""

    @Test fun selectedCatalogueIdentityAndTimingSurvivePreviewAndApply() {
        val requests = mutableListOf<JSONObject>()
        val service = ManualLyricsSearch(context) { raw, _ ->
            val request = JSONObject(raw)
            requests += request
            if (request.getString("operation") == "search")
                """{"ok":true,"candidates":[{"id":"42","title":"Catalogue title","artist":"Artist","album":"Album","duration_ms":200000,"score":0.9,"storefront":"tr"}]}"""
            else payload
        }
        val found = service.search(PlainLyricsSearchSource.APPLE, target, token()).single()
        assertEquals(candidate, found)
        val preview = (service.fetch(found, target, token()) as ManualLyricsFetchResult.Available).lyrics
        assertEquals("tr", requests.last().getString("storefront"))
        assertEquals("42", requests.last().getJSONObject("track").getString("id"))
        assertEquals(1, preview.wordByWordLines.size)
        assertTrue(preview.wordByWordLines.single().translatedSegments.isNotEmpty())
        assertNull(LyricsStorage.readPlainLyrics(context, target.title, target.artist, target.durationMs))
        assertTrue(service.apply(target, preview, token(), null))
        val restored = ManualLyricsSelectionStore.read(context, target)!!.result
        assertTrue(restored.selectedManually)
        assertEquals(preview.plainLrc, restored.plainLrc)
        assertEquals(preview.translatedLrc, restored.translatedLrc)
        assertEquals(preview.wordByWordLines, restored.wordByWordLines)
        assertNull(ManualLyricsSelectionStore.read(context, candidate.song))
    }

    @Test fun realTranslationDoesNotInventWordTiming() {
        val service = ManualLyricsSearch(context) { _, _ -> payload.replace("愛你", "Hello").replace("爱你", "你好") }
        val result = (service.fetch(candidate, target, token()) as ManualLyricsFetchResult.Available).lyrics
        assertEquals("你好", result.wordByWordLines.single().translatedText)
        assertTrue(result.wordByWordLines.single().translatedSegments.isEmpty())
    }

    @Test fun wrongIdAndMissingLyricsCannotBeApplied() {
        val wrong = ManualLyricsSearch(context) { _, _ -> payload.replace("\"id\":\"42\"", "\"id\":\"99\"") }
        val error = runCatching { wrong.fetch(candidate, target, token()) }.exceptionOrNull()
        assertTrue(error is LyricsLookupException)
        for (response in listOf("""{"ok":false,"error_type":"NotFound"}""",
            """{"ok":true,"id":"42","lrc":""}""",
            """{"ok":true,"content_state":"empty","reason":"no_usable_lines"}""")) {
            val service = ManualLyricsSearch(context) { _, _ -> response }
            assertTrue(service.fetch(candidate, target, token()) is ManualLyricsFetchResult.Unavailable)
        }
    }

    @Test fun credentialErrorsAndRateLimitsRemainDistinct() {
        for (kind in listOf(LyricsLookupErrorType.NeedCredential, LyricsLookupErrorType.RateLimited, LyricsLookupErrorType.NetworkError)) {
            val service = ManualLyricsSearch(context) { _, _ -> """{"ok":false,"error_type":"${kind.name}"}""" }
            val error = runCatching { service.fetch(candidate, target, token()) }.exceptionOrNull() as LyricsLookupException
            assertEquals(kind, error.errorType)
        }
    }

    @Test fun defaultAppleRoutingReportsMissingAccountBeforeLoadingJni() {
        com.andsi.airlyrics.apple.AppleMusicSessionStore.clear(context)
        val error = runCatching {
            ManualLyricsSearch(context).search(PlainLyricsSearchSource.APPLE, target, token())
        }.exceptionOrNull() as LyricsLookupException
        assertEquals(LyricsLookupErrorType.NeedCredential, error.errorType)
    }

    @Test fun credentialsAreSeparateAndSelectedStorefrontOverridesCurrentAccount() {
        val raw = """{"provider":"apple","operation":"fetch","storefront":"tr"}"""
        val session = AppleMusicSession("user-token", "us", 0)
        val result = AppleManualLyricsRequest.execute(context, raw, token(), { session }, { Result.success("developer-token") }) {
                request, user, developer, storefront, _ ->
            assertEquals(raw, request)
            assertEquals("user-token", user)
            assertEquals("developer-token", developer)
            assertEquals("tr", storefront)
            payload
        }
        assertEquals(payload, result)
    }

    @Test fun missingSessionLogoutAndCancellationNeverCallNative() {
        val raw = """{"provider":"apple","operation":"search"}"""
        val native: (String, String, String, String, LyricsLookupCancellationToken) -> String = { _, _, _, _, _ ->
            fail("Native must not run"); ""
        }
        val missing = runCatching {
            AppleManualLyricsRequest.execute(context, raw, token(), { null }, { fail("Token lookup must not run"); Result.success("") }, native)
        }.exceptionOrNull() as LyricsLookupException
        assertEquals(LyricsLookupErrorType.NeedCredential, missing.errorType)
        var session: AppleMusicSession? = AppleMusicSession("user", "us", 0)
        val loggedOut = runCatching {
            AppleManualLyricsRequest.execute(context, raw, token(), { session }, { session = null; Result.success("developer") }, native)
        }.exceptionOrNull() as LyricsLookupException
        assertEquals(LyricsLookupErrorType.NeedCredential, loggedOut.errorType)
        val cancelled = token().apply { cancel() }
        val error = runCatching {
            AppleManualLyricsRequest.execute(context, raw, cancelled, { fail("Session lookup must not run"); null }, { Result.success("") }, native)
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }
}
