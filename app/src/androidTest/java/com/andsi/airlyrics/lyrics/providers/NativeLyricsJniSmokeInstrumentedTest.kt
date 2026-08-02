package com.andsi.airlyrics.lyrics.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException

@RunWith(AndroidJUnit4::class)
class NativeLyricsJniSmokeInstrumentedTest {
    @Test
    fun nativeLibrary_loadsAndOfflineBindingsRoundTrip() {
        LyricsNativeLibrary.ensureLoaded()

        val failures = mutableListOf<Throwable>()
        try {
            TEST_LOOKUP_IDS.forEach { lookupId ->
                LyricsNativeCancellation.clearLookup(lookupId)
            }

            LyricsNativeCancellation.cancelLookup(CANCEL_BEFORE_START_LOOKUP_ID)
            val canceledBeforeStart = fetchNeteaseValidation(CANCEL_BEFORE_START_LOOKUP_ID)
            assertNativeError(
                result = canceledBeforeStart,
                expectedSource = "netease-rust",
                expectedMessage = "lookup canceled",
            )

            LyricsNativeCancellation.cancelLookup(MARKER_CONSUMPTION_LOOKUP_ID)
            val markerPending = fetchNeteaseValidation(MARKER_CONSUMPTION_LOOKUP_ID)
            assertNativeError(
                result = markerPending,
                expectedSource = "netease-rust",
                expectedMessage = "lookup canceled",
            )
            val consumedCancellation = fetchNeteaseValidation(MARKER_CONSUMPTION_LOOKUP_ID)
            assertNativeError(
                result = consumedCancellation,
                expectedSource = "netease-rust",
                expectedMessage = "empty title",
            )

            LyricsNativeCancellation.cancelLookup(EXPLICIT_CLEAR_LOOKUP_ID)
            LyricsNativeCancellation.clearLookup(EXPLICIT_CLEAR_LOOKUP_ID)
            val explicitlyClearedCancellation = fetchNeteaseValidation(EXPLICIT_CLEAR_LOOKUP_ID)
            assertNativeError(
                result = explicitlyClearedCancellation,
                expectedSource = "netease-rust",
                expectedMessage = "empty title",
            )

            val musixmatchValidation = fetchMusixmatchValidation()
            assertNativeError(
                result = musixmatchValidation,
                expectedSource = "musixmatch-rust",
                expectedMessage = "empty title",
            )
        } catch (failure: Throwable) {
            failures += failure
        } finally {
            TEST_LOOKUP_IDS.forEach { lookupId ->
                runCatching {
                    LyricsNativeCancellation.clearLookup(lookupId)
                }.exceptionOrNull()?.let(failures::add)
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    private fun fetchNeteaseValidation(lookupId: Long): NativeLyricsJsonResult {
        return parseNativeResult(
            NeteaseLyricsNative.fetchBestLyricsJson(
                title = "",
                artist = "",
                album = "",
                durationMs = 0L,
                lookupId = lookupId,
                requestKlyric = false,
            ),
            defaultSource = "netease-rust",
        )
    }

    private fun fetchMusixmatchValidation(): NativeLyricsJsonResult {
        return parseNativeResult(
            MusixmatchLyricsNative.fetchBestLyricsJson(
                title = "",
                artist = "",
                album = "",
                durationMs = 0L,
                translationLanguageCode = "zh",
                lookupId = MUSIXMATCH_VALIDATION_LOOKUP_ID,
                reserved = false,
            ),
            defaultSource = "musixmatch-rust",
        )
    }

    private fun parseNativeResult(
        jsonText: String,
        defaultSource: String,
    ): NativeLyricsJsonResult {
        return NativeLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = defaultSource,
            fallbackTitle = "",
            fallbackArtist = "",
            fallbackAlbum = "",
            fallbackDurationMs = 0L,
        )
    }

    private fun assertNativeError(
        result: NativeLyricsJsonResult,
        expectedSource: String,
        expectedMessage: String,
    ) {
        assertFalse(result.ok)
        assertEquals(expectedSource, result.source)
        assertEquals("Unknown", result.errorTypeName)
        assertEquals(LyricsLookupErrorType.Unknown, result.errorType)
        assertEquals(expectedMessage, result.errorMessage)
    }

    private companion object {
        const val CANCEL_BEFORE_START_LOOKUP_ID = Long.MAX_VALUE - 101L
        const val MARKER_CONSUMPTION_LOOKUP_ID = Long.MAX_VALUE - 102L
        const val EXPLICIT_CLEAR_LOOKUP_ID = Long.MAX_VALUE - 103L
        const val MUSIXMATCH_VALIDATION_LOOKUP_ID = Long.MAX_VALUE - 104L

        val TEST_LOOKUP_IDS =
            listOf(
                CANCEL_BEFORE_START_LOOKUP_ID,
                MARKER_CONSUMPTION_LOOKUP_ID,
                EXPLICIT_CLEAR_LOOKUP_ID,
                MUSIXMATCH_VALIDATION_LOOKUP_ID,
            )
    }
}
