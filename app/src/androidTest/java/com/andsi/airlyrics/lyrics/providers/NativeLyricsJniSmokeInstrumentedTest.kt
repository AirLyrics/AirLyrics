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
            val canceledBeforeStart = fetchNeteasePlainLyricsValidation(CANCEL_BEFORE_START_LOOKUP_ID)
            assertNativePlainLyricsError(
                plainLyricsResult = canceledBeforeStart,
                expectedSource = "netease-rust",
                expectedMessage = "lookup canceled",
            )

            LyricsNativeCancellation.cancelLookup(MARKER_CONSUMPTION_LOOKUP_ID)
            val markerPending = fetchNeteasePlainLyricsValidation(MARKER_CONSUMPTION_LOOKUP_ID)
            assertNativePlainLyricsError(
                plainLyricsResult = markerPending,
                expectedSource = "netease-rust",
                expectedMessage = "lookup canceled",
            )
            val consumedCancellation = fetchNeteasePlainLyricsValidation(MARKER_CONSUMPTION_LOOKUP_ID)
            assertNativePlainLyricsError(
                plainLyricsResult = consumedCancellation,
                expectedSource = "netease-rust",
                expectedMessage = "empty title",
            )

            LyricsNativeCancellation.cancelLookup(EXPLICIT_CLEAR_LOOKUP_ID)
            LyricsNativeCancellation.clearLookup(EXPLICIT_CLEAR_LOOKUP_ID)
            val explicitlyClearedCancellation = fetchNeteasePlainLyricsValidation(EXPLICIT_CLEAR_LOOKUP_ID)
            assertNativePlainLyricsError(
                plainLyricsResult = explicitlyClearedCancellation,
                expectedSource = "netease-rust",
                expectedMessage = "empty title",
            )

            val musixmatchValidation = fetchMusixmatchPlainLyricsValidation()
            assertNativePlainLyricsError(
                plainLyricsResult = musixmatchValidation,
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

    private fun fetchNeteasePlainLyricsValidation(lookupId: Long): NativePlainLyricsJsonResult {
        return parseNativePlainLyricsResult(
            NeteaseLyricsNative.fetchBestLyricsJson(
                title = "",
                artist = "",
                album = "",
                durationMs = 0L,
                lookupId = lookupId,
            ),
            defaultSource = "netease-rust",
        )
    }

    private fun fetchMusixmatchPlainLyricsValidation(): NativePlainLyricsJsonResult {
        return parseNativePlainLyricsResult(
            MusixmatchLyricsNative.fetchBestLyricsJson(
                title = "",
                artist = "",
                album = "",
                durationMs = 0L,
                translationLanguageCode = "zh",
                lookupId = MUSIXMATCH_VALIDATION_LOOKUP_ID,
            ),
            defaultSource = "musixmatch-rust",
        )
    }

    private fun parseNativePlainLyricsResult(
        jsonText: String,
        defaultSource: String,
    ): NativePlainLyricsJsonResult {
        return NativePlainLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = defaultSource,
            fallbackTitle = "",
            fallbackArtist = "",
            fallbackAlbum = "",
            fallbackDurationMs = 0L,
        )
    }

    private fun assertNativePlainLyricsError(
        plainLyricsResult: NativePlainLyricsJsonResult,
        expectedSource: String,
        expectedMessage: String,
    ) {
        assertFalse(plainLyricsResult.ok)
        assertEquals(expectedSource, plainLyricsResult.plainSource)
        assertEquals("Unknown", plainLyricsResult.errorTypeName)
        assertEquals(LyricsLookupErrorType.Unknown, plainLyricsResult.errorType)
        assertEquals(expectedMessage, plainLyricsResult.errorMessage)
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
