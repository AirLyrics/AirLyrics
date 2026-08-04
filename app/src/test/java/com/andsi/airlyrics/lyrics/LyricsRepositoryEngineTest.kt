package com.andsi.airlyrics.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsRepositoryEngineTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun findLyrics_returnsLocalLyricsBeforeOnlineProvider() {
        val local = FakePlainLyricsProvider("local", Result.success(result("local", "[00:01.00]local")))
        val online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(local = local, online = online)

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertEquals("local", found?.plainProviderId)
        assertEquals(1, local.calls)
        assertEquals(0, online.calls)
    }

    @Test
    fun findLyrics_localOnlyDoesNotCallOnlineProvider() {
        val online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = online,
            settings = settings(
                plainLyricsSearchSource = PlainLyricsSearchSource.LOCAL_ONLY,
                autoSearchOnline = false
            )
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertNull(found)
        assertEquals(0, online.calls)
    }

    @Test
    fun findLyrics_respectsAutoSearchOnlineWhenNotIgnored() {
        val online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = online,
            settings = settings(autoSearchOnline = false)
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertNull(found)
        assertEquals(0, online.calls)
    }

    @Test
    fun findLyrics_doesNotFallbackWhenSelectedOnlineProviderIsMissing() {
        val netease = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = netease,
            settings = settings(plainLyricsSearchSource = PlainLyricsSearchSource.MUSIXMATCH)
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertNull(found)
        assertEquals(0, netease.calls)
    }

    @Test
    fun findLyrics_ignoreAutoSearchSettingAllowsManualOnlineLookup() {
        val online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = online,
            settings = settings(autoSearchOnline = false)
        )

        val found = engine.findLyrics(
            context = context,
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            ignoreAutoSearchSetting = true
        ).getOrThrow()

        assertEquals("netease", found?.plainProviderId)
        assertEquals(1, online.calls)
    }

    @Test
    fun findLyrics_bypassLocalCallsOnlineProvider() {
        val local = FakePlainLyricsProvider("local", Result.success(result("local", "[00:01.00]local")))
        val online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(local = local, online = online)

        val found = engine.findLyrics(
            context = context,
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            bypassLocal = true
        ).getOrThrow()

        assertEquals("netease", found?.plainProviderId)
        assertEquals(0, local.calls)
        assertEquals(1, online.calls)
    }

    @Test
    fun findLyrics_autoSavesSuccessfulOnlineResult() {
        val onlinePlainLyricsResult = result(
            plainProviderId = "netease",
            plainLrc = "[00:01.00]hello",
            translatedLrc = "[00:01.00]你好"
        )
        val saver = RecordingPlainLyricsSaver()
        val engine = engine(
            online = FakePlainLyricsProvider("netease", Result.success(onlinePlainLyricsResult)),
            localPlainLyricsSaver = saver
        )

        engine.findLyrics(context, "Song", "Artist", album = "Album", durationMs = 180_000L).getOrThrow()

        assertEquals(1, saver.saved.size)
        assertSame(onlinePlainLyricsResult, saver.saved.single().plainLyricsResult)
        assertEquals("Album", saver.saved.single().album)
    }

    @Test
    fun findLyrics_forceSaveOnlineOverridesAutoSaveDisabled() {
        val saver = RecordingPlainLyricsSaver()
        val engine = engine(
            online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online"))),
            settings = settings(autoSaveLocal = false),
            localPlainLyricsSaver = saver
        )

        engine.findLyrics(
            context = context,
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            forceSaveOnline = true
        ).getOrThrow()

        assertEquals(1, saver.saved.size)
    }

    @Test
    fun findLyrics_attachesCachedWordByWordWhenEnabled() {
        val wordByWordLine = WordByWordLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = "hello",
            segments = listOf(WordByWordSegment("hello", 1_000L, 2_000L))
        )
        val engine = engine(
            local = FakePlainLyricsProvider("local", Result.success(result("local", "[00:01.00]hello"))),
            settings = settings(wordByWordLyricsEnabled = true),
            wordByWordLyricsReader = WordByWordLyricsReader { _, _, _, _ -> listOf(wordByWordLine) }
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertEquals(listOf(wordByWordLine), found?.wordByWordLines)
    }

    @Test
    fun findLyrics_returnsProviderFailureAndLogsIt() {
        val failure = IllegalStateException("provider down")
        val logger = RecordingLogger()
        val engine = engine(
            online = FakePlainLyricsProvider("netease", Result.failure(failure)),
            lookupLogger = logger
        )

        val result = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, logger.failures)
    }

    @Test
    fun findLyrics_stopsWhenCancellationTokenIsAlreadyCanceled() {
        val local = FakePlainLyricsProvider("local", Result.success(result("local", "[00:01.00]local")))
        val engine = engine(local = local)
        val token = LyricsLookupCancellationToken(requestKey = "song", generation = 1L)
        token.cancel()

        val result = engine.findLyrics(
            context = context,
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            cancellationToken = token
        )

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, local.calls)
    }

    private fun engine(
        local: PlainLyricsProvider = FakePlainLyricsProvider("local", Result.success(null)),
        online: PlainLyricsProvider = FakePlainLyricsProvider("netease", Result.success(null)),
        settings: LyricsSettings = settings(),
        localPlainLyricsSaver: LocalPlainLyricsSaver = RecordingPlainLyricsSaver(),
        wordByWordLyricsReader: WordByWordLyricsReader = WordByWordLyricsReader { _, _, _, _ -> emptyList() },
        lookupLogger: LyricsLookupLogger = RecordingLogger()
    ): LyricsRepositoryEngine {
        return LyricsRepositoryEngine(
            localPlainLyricsProvider = local,
            onlinePlainLyricsProviders = mapOf(PlainLyricsSearchSource.NETEASE to online),
            settingsReader = { settings },
            localPlainLyricsSaver = localPlainLyricsSaver,
            wordByWordLyricsReader = wordByWordLyricsReader,
            lookupLogger = lookupLogger
        )
    }

    private fun settings(
        plainLyricsSearchSource: PlainLyricsSearchSource = PlainLyricsSearchSource.NETEASE,
        autoSearchOnline: Boolean = true,
        autoSaveLocal: Boolean = true,
        wordByWordLyricsEnabled: Boolean = false
    ): LyricsSettings {
        return LyricsSettings(
            plainLyricsSearchSource = plainLyricsSearchSource,
            autoSearchOnline = autoSearchOnline,
            autoSaveLocal = autoSaveLocal,
            contentDisplayMode = LyricsContentDisplayMode.default,
            lineDisplayMode = LyricsLineDisplayMode.default,
            switchAnimationMode = LyricsSwitchAnimationMode.default,
            wordByWordLyricsEnabled = wordByWordLyricsEnabled
        )
    }

    private fun result(
        plainProviderId: String,
        plainLrc: String,
        translatedLrc: String? = null
    ): LyricsProviderResult {
        return LyricsProviderResult(
            plainProviderId = plainProviderId,
            plainProviderName = plainProviderId,
            plainLrc = plainLrc,
            translatedLrc = translatedLrc
        )
    }

    private class FakePlainLyricsProvider(
        override val id: String,
        private val response: Result<LyricsProviderResult?>
    ) : PlainLyricsProvider {
        override val name: String = id
        private val requests = mutableListOf<PlainLyricsSearchRequest>()
        val calls: Int
            get() = requests.size

        override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
            requests += request
            return response
        }
    }

    private class RecordingPlainLyricsSaver : LocalPlainLyricsSaver {
        val saved = mutableListOf<SavedPlainLyrics>()

        override fun save(
            context: Context,
            title: String,
            artist: String,
            album: String,
            durationMs: Long,
            plainLyricsResult: LyricsProviderResult
        ) {
            saved += SavedPlainLyrics(title, artist, album, durationMs, plainLyricsResult)
        }
    }

    private data class SavedPlainLyrics(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val plainLyricsResult: LyricsProviderResult
    )

    private class RecordingLogger : LyricsLookupLogger {
        var failures: Int = 0
            private set

        override fun logProviderFailure(
            provider: PlainLyricsProvider,
            title: String,
            artist: String,
            durationMs: Long,
            error: Throwable,
            debug: Boolean
        ) {
            failures++
        }
    }
}
