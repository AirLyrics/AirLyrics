package com.andsi.airlyrics.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSearchSource
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
        val local = FakeProvider("local", Result.success(result("local", "[00:01.00]local")))
        val online = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(local = local, online = online)

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertEquals("local", found?.providerId)
        assertEquals(1, local.calls)
        assertEquals(0, online.calls)
    }

    @Test
    fun findLyrics_localOnlyDoesNotCallOnlineProvider() {
        val online = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = online,
            settings = settings(source = LyricsSearchSource.LOCAL_ONLY, autoSearchOnline = false)
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertNull(found)
        assertEquals(0, online.calls)
    }

    @Test
    fun findLyrics_respectsAutoSearchOnlineWhenNotIgnored() {
        val online = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online")))
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
        val netease = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = netease,
            settings = settings(source = LyricsSearchSource.MUSIXMATCH)
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertNull(found)
        assertEquals(0, netease.calls)
    }

    @Test
    fun findLyrics_ignoreAutoSearchSettingAllowsManualOnlineLookup() {
        val online = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online")))
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

        assertEquals("netease", found?.providerId)
        assertEquals(1, online.calls)
    }

    @Test
    fun findLyrics_bypassLocalCallsOnlineProvider() {
        val local = FakeProvider("local", Result.success(result("local", "[00:01.00]local")))
        val online = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(local = local, online = online)

        val found = engine.findLyrics(
            context = context,
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            bypassLocal = true
        ).getOrThrow()

        assertEquals("netease", found?.providerId)
        assertEquals(0, local.calls)
        assertEquals(1, online.calls)
    }

    @Test
    fun findLyrics_autoSavesSuccessfulOnlineResult() {
        val onlineResult = result(
            providerId = "netease",
            lyrics = "[00:01.00]hello",
            translatedLyrics = "[00:01.00]你好"
        )
        val saver = RecordingSaver()
        val engine = engine(
            online = FakeProvider("netease", Result.success(onlineResult)),
            localLyricsSaver = saver
        )

        engine.findLyrics(context, "Song", "Artist", album = "Album", durationMs = 180_000L).getOrThrow()

        assertEquals(1, saver.saved.size)
        assertSame(onlineResult, saver.saved.single().result)
        assertEquals("Album", saver.saved.single().album)
    }

    @Test
    fun findLyrics_forceSaveOnlineOverridesAutoSaveDisabled() {
        val saver = RecordingSaver()
        val engine = engine(
            online = FakeProvider("netease", Result.success(result("netease", "[00:01.00]online"))),
            settings = settings(autoSaveLocal = false),
            localLyricsSaver = saver
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
    fun findLyrics_attachesCachedKaraokeWhenEnabled() {
        val karaokeLine = KaraokeLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = "hello",
            tokens = listOf(KaraokeToken("hello", 1_000L, 2_000L))
        )
        val engine = engine(
            local = FakeProvider("local", Result.success(result("local", "[00:01.00]hello"))),
            settings = settings(karaokeLyricsEnabled = true),
            karaokeLyricsReader = KaraokeLyricsReader { _, _, _, _ -> listOf(karaokeLine) }
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertEquals(listOf(karaokeLine), found?.karaokeLines)
    }

    @Test
    fun findLyrics_returnsProviderFailureAndLogsIt() {
        val failure = IllegalStateException("provider down")
        val logger = RecordingLogger()
        val engine = engine(
            online = FakeProvider("netease", Result.failure(failure)),
            lookupLogger = logger
        )

        val result = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, logger.failures)
    }

    @Test
    fun findLyrics_stopsWhenCancellationTokenIsAlreadyCanceled() {
        val local = FakeProvider("local", Result.success(result("local", "[00:01.00]local")))
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
        local: LyricsProvider = FakeProvider("local", Result.success(null)),
        online: LyricsProvider = FakeProvider("netease", Result.success(null)),
        settings: LyricsSettings = settings(),
        localLyricsSaver: LocalLyricsSaver = RecordingSaver(),
        karaokeLyricsReader: KaraokeLyricsReader = KaraokeLyricsReader { _, _, _, _ -> emptyList() },
        lookupLogger: LyricsLookupLogger = RecordingLogger()
    ): LyricsRepositoryEngine {
        return LyricsRepositoryEngine(
            localProvider = local,
            onlineProviders = mapOf(LyricsSearchSource.NETEASE to online),
            settingsReader = { settings },
            localLyricsSaver = localLyricsSaver,
            karaokeLyricsReader = karaokeLyricsReader,
            lookupLogger = lookupLogger
        )
    }

    private fun settings(
        source: LyricsSearchSource = LyricsSearchSource.NETEASE,
        autoSearchOnline: Boolean = true,
        autoSaveLocal: Boolean = true,
        karaokeLyricsEnabled: Boolean = false
    ): LyricsSettings {
        return LyricsSettings(
            source = source,
            autoSearchOnline = autoSearchOnline,
            autoSaveLocal = autoSaveLocal,
            contentDisplayMode = LyricsContentDisplayMode.default,
            lineDisplayMode = LyricsLineDisplayMode.default,
            switchAnimationMode = LyricsSwitchAnimationMode.default,
            karaokeLyricsEnabled = karaokeLyricsEnabled
        )
    }

    private fun result(
        providerId: String,
        lyrics: String,
        translatedLyrics: String? = null
    ): LyricsProviderResult {
        return LyricsProviderResult(
            providerId = providerId,
            providerName = providerId,
            lyrics = lyrics,
            translatedLyrics = translatedLyrics
        )
    }

    private class FakeProvider(
        override val id: String,
        private val response: Result<LyricsProviderResult?>
    ) : LyricsProvider {
        override val name: String = id
        var calls: Int = 0
            private set

        override fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?> {
            calls++
            return response
        }
    }

    private class RecordingSaver : LocalLyricsSaver {
        val saved = mutableListOf<SavedLyrics>()

        override fun save(
            context: Context,
            title: String,
            artist: String,
            album: String,
            durationMs: Long,
            result: LyricsProviderResult
        ) {
            saved += SavedLyrics(title, artist, album, durationMs, result)
        }
    }

    private data class SavedLyrics(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val result: LyricsProviderResult
    )

    private class RecordingLogger : LyricsLookupLogger {
        var failures: Int = 0
            private set

        override fun logProviderFailure(
            provider: LyricsProvider,
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
