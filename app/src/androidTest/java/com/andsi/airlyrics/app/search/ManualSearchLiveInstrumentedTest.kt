package com.andsi.airlyrics.app.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import com.andsi.airlyrics.lyrics.providers.MusixmatchPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsProvider
import com.andsi.airlyrics.lyrics.search.ManualLyricsSearch
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in diagnostics: remote availability is not a prerequisite for the offline test suite. */
@RunWith(AndroidJUnit4::class)
class ManualSearchLiveInstrumentedTest {
    @Test fun reportLiveSearchAndExactCandidateFetch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLyricsSearch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = ManualLyricsSearch(context)
        val query = SongIdentity("Hello", "Adele", "", 295000)
        val lines = mutableListOf<String>()
        for (source in PlainLyricsSearchSource.onlineSources) {
            val token = LyricsLookupCancellationToken(query.storageKey(), 1)
            try {
                val candidates = service.search(source, query, token)
                lines += "${source.key}: ${candidates.size} candidates"
                val candidate = candidates.firstOrNull() ?: continue
                val fetched = service.fetch(candidate, query, token)
                if (fetched is ManualLyricsFetchResult.Unavailable) {
                    lines += "${source.key}: ${fetched.reason}"
                    continue
                }
                val result = (fetched as ManualLyricsFetchResult.Available).lyrics
                assertEquals(candidate.song.title, result.matchedTitle)
                assertEquals(candidate.song.artist, result.matchedArtist)
                assertTrue(result.plainLrc.isNotBlank())
                lines += "${source.key}: selected candidate preview passed (${result.precision})"
            } catch (error: LyricsLookupException) {
                lines += "${source.key}: ${error.errorType}"
                val baselineProvider = when (source) {
                    PlainLyricsSearchSource.MUSIXMATCH -> MusixmatchPlainLyricsProvider
                    PlainLyricsSearchSource.SPICY -> SpicyLyricsProvider
                    else -> null
                }
                baselineProvider?.let { provider ->
                    val baseline = provider.fetch(PlainLyricsSearchRequest(context, query.title, query.artist,
                        query.album, query.durationMs, LyricsLookupCancellationToken(query.storageKey(), 2)))
                    val failure = baseline.exceptionOrNull()
                    lines += "${source.key}: existing automatic interface = " + when {
                        failure is LyricsLookupException -> failure.errorType.name
                        failure != null -> failure.javaClass.simpleName
                        baseline.getOrNull() == null -> "NotFound"
                        else -> "Available"
                    }
                }
            }
        }
        PlatformTestStorageRegistry.getInstance().openOutputFile("manual-search-live.txt").bufferedWriter().use {
            it.write(lines.joinToString("\n"))
        }
    }
}
