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
    fun findLyrics_emptySourceOrderDoesNotCallOnlineProvider() {
        val online = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            online = online,
            settings = settings(plainLyricsSearchSources = emptyList())
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
    fun findLyrics_firstOnlineMatchStopsFallback() {
        val neteaseResult = result("netease", "[00:01.00]online")
        val netease = FakePlainLyricsProvider("netease", Result.success(neteaseResult))
        val musixmatch = FakePlainLyricsProvider(
            "musixmatch",
            Result.success(result("musixmatch", "[00:01.00]fallback"))
        )
        val engine = engine(
            onlineProviders = linkedMapOf(
                PlainLyricsSearchSource.MUSIXMATCH to musixmatch,
                PlainLyricsSearchSource.NETEASE to netease
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH
                )
            )
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertSame(neteaseResult, found)
        assertEquals(1, netease.calls)
        assertEquals(0, musixmatch.calls)
    }

    @Test
    fun findLyrics_translationOnlyMatchStopsFallback() {
        val neteaseResult = result(
            plainProviderId = "netease",
            plainLrc = "",
            translatedLrc = "[00:01.00]翻译"
        )
        val netease = FakePlainLyricsProvider("netease", Result.success(neteaseResult))
        val musixmatch = FakePlainLyricsProvider(
            "musixmatch",
            Result.success(result("musixmatch", "[00:01.00]fallback"))
        )
        val engine = engine(
            onlineProviders = linkedMapOf(
                PlainLyricsSearchSource.NETEASE to netease,
                PlainLyricsSearchSource.MUSIXMATCH to musixmatch
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH
                )
            )
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertSame(neteaseResult, found)
        assertEquals(1, netease.calls)
        assertEquals(0, musixmatch.calls)
    }

    @Test
    fun findLyrics_missingProviderReturnsConfigurationFailureWithoutFallback() {
        val netease = FakePlainLyricsProvider("netease", Result.success(result("netease", "[00:01.00]online")))
        val engine = engine(
            onlineProviders = mapOf(PlainLyricsSearchSource.NETEASE to netease),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.MUSIXMATCH,
                    PlainLyricsSearchSource.NETEASE
                )
            )
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L)

        assertTrue(found.exceptionOrNull() is IllegalStateException)
        assertTrue(found.exceptionOrNull()?.message.orEmpty().contains("musixmatch"))
        assertEquals(0, netease.calls)
    }

    @Test
    fun findLyrics_fallsBackInConfiguredOrderAndSavesWinnerOnce() {
        val callOrder = mutableListOf<String>()
        val netease = FakePlainLyricsProvider("netease") {
            callOrder += "netease"
            Result.success(null)
        }
        val musixmatchResult = result("musixmatch", "[00:01.00]winner")
        val musixmatch = FakePlainLyricsProvider("musixmatch") {
            callOrder += "musixmatch"
            Result.success(musixmatchResult)
        }
        val lrclib = FakePlainLyricsProvider("lrclib") {
            callOrder += "lrclib"
            Result.success(result("lrclib", "[00:01.00]unused"))
        }
        val saver = RecordingPlainLyricsSaver()
        val logger = RecordingLogger()
        val engine = engine(
            onlineProviders = linkedMapOf(
                PlainLyricsSearchSource.LRCLIB to lrclib,
                PlainLyricsSearchSource.MUSIXMATCH to musixmatch,
                PlainLyricsSearchSource.NETEASE to netease
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH,
                    PlainLyricsSearchSource.LRCLIB
                )
            ),
            localPlainLyricsSaver = saver,
            lookupLogger = logger
        )

        val found = engine.findLyrics(
            context,
            "Song",
            "Artist",
            album = "Album",
            durationMs = 180_000L
        ).getOrThrow()

        assertSame(musixmatchResult, found)
        assertEquals(listOf("netease", "musixmatch"), callOrder)
        assertEquals(0, lrclib.calls)
        assertEquals(1, saver.saved.size)
        assertSame(musixmatchResult, saver.saved.single().plainLyricsResult)
        assertEquals("Album", saver.saved.single().album)
        assertEquals(
            listOf(
                ProviderProgress("netease", 1, 3, LyricsLookupProviderStage.ATTEMPT),
                ProviderProgress("netease", 1, 3, LyricsLookupProviderStage.MISS),
                ProviderProgress("musixmatch", 2, 3, LyricsLookupProviderStage.ATTEMPT),
                ProviderProgress("musixmatch", 2, 3, LyricsLookupProviderStage.MATCH)
            ),
            logger.progress
        )
    }

    @Test
    fun findLyrics_fallsBackWhenProviderReturnsBlankLyrics() {
        val blank = FakePlainLyricsProvider("netease", Result.success(result("netease", "   ")))
        val fallbackResult = result("lrclib", "[00:01.00]fallback")
        val fallback = FakePlainLyricsProvider("lrclib", Result.success(fallbackResult))
        val engine = engine(
            onlineProviders = mapOf(
                PlainLyricsSearchSource.NETEASE to blank,
                PlainLyricsSearchSource.LRCLIB to fallback
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.LRCLIB
                )
            )
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertSame(fallbackResult, found)
        assertEquals(1, blank.calls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun findLyrics_returnsNullAfterAllProvidersMiss() {
        val callOrder = mutableListOf<String>()
        val providers = PlainLyricsSearchSource.onlineSources.associateWith { source ->
            FakePlainLyricsProvider(source.key) {
                callOrder += source.key
                Result.success(null)
            }
        }
        val saver = RecordingPlainLyricsSaver()
        val logger = RecordingLogger()
        val engine = engine(
            onlineProviders = providers,
            settings = settings(plainLyricsSearchSources = PlainLyricsSearchSource.onlineSources),
            localPlainLyricsSaver = saver,
            lookupLogger = logger
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertNull(found)
        assertEquals(PlainLyricsSearchSource.onlineSources.map { it.key }, callOrder)
        assertTrue(saver.saved.isEmpty())
        assertEquals(0, logger.failures)
    }

    @Test
    fun findLyrics_deduplicatesConfiguredSourcesBeforeFallback() {
        val netease = FakePlainLyricsProvider("netease", Result.success(null))
        val lrclibResult = result("lrclib", "[00:01.00]fallback")
        val lrclib = FakePlainLyricsProvider("lrclib", Result.success(lrclibResult))
        val logger = RecordingLogger()
        val engine = engine(
            onlineProviders = mapOf(
                PlainLyricsSearchSource.NETEASE to netease,
                PlainLyricsSearchSource.LRCLIB to lrclib
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.LRCLIB
                )
            ),
            lookupLogger = logger
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L).getOrThrow()

        assertSame(lrclibResult, found)
        assertEquals(1, netease.calls)
        assertEquals(1, lrclib.calls)
        assertEquals(
            listOf(
                ProviderProgress("netease", 1, 2, LyricsLookupProviderStage.ATTEMPT),
                ProviderProgress("netease", 1, 2, LyricsLookupProviderStage.MISS),
                ProviderProgress("lrclib", 2, 2, LyricsLookupProviderStage.ATTEMPT),
                ProviderProgress("lrclib", 2, 2, LyricsLookupProviderStage.MATCH)
            ),
            logger.progress
        )
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
    fun findLyrics_stopsFallbackAtProviderFailureAndLogsIt() {
        val failure = IllegalStateException("provider down")
        val logger = RecordingLogger()
        val netease = FakePlainLyricsProvider("netease", Result.success(null))
        val musixmatch = FakePlainLyricsProvider("musixmatch", Result.failure(failure))
        val lrclib = FakePlainLyricsProvider(
            "lrclib",
            Result.success(result("lrclib", "[00:01.00]unused"))
        )
        val engine = engine(
            onlineProviders = mapOf(
                PlainLyricsSearchSource.NETEASE to netease,
                PlainLyricsSearchSource.MUSIXMATCH to musixmatch,
                PlainLyricsSearchSource.LRCLIB to lrclib
            ),
            settings = settings(plainLyricsSearchSources = PlainLyricsSearchSource.onlineSources),
            lookupLogger = logger
        )

        val result = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L)

        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, netease.calls)
        assertEquals(1, musixmatch.calls)
        assertEquals(0, lrclib.calls)
        assertEquals(1, logger.failures)
    }

    @Test
    fun findLyrics_cancellationBetweenProvidersStopsFallbackWithoutSavingOrLoggingFailure() {
        val token = LyricsLookupCancellationToken(requestKey = "song", generation = 1L)
        val netease = FakePlainLyricsProvider("netease") {
            token.cancel()
            Result.success(null)
        }
        val musixmatch = FakePlainLyricsProvider(
            "musixmatch",
            Result.success(result("musixmatch", "[00:01.00]unused"))
        )
        val saver = RecordingPlainLyricsSaver()
        val logger = RecordingLogger()
        val engine = engine(
            onlineProviders = mapOf(
                PlainLyricsSearchSource.NETEASE to netease,
                PlainLyricsSearchSource.MUSIXMATCH to musixmatch
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH
                )
            ),
            localPlainLyricsSaver = saver,
            lookupLogger = logger
        )

        val result = engine.findLyrics(
            context = context,
            title = "Song",
            artist = "Artist",
            durationMs = 180_000L,
            cancellationToken = token
        )

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, netease.calls)
        assertEquals(0, musixmatch.calls)
        assertTrue(saver.saved.isEmpty())
        assertEquals(0, logger.failures)
    }

    @Test
    fun findLyrics_providerCancellationFailureStopsFallbackWithoutFailureLog() {
        val cancellation = CancellationException("provider canceled")
        val netease = FakePlainLyricsProvider(
            "netease",
            Result.failure(cancellation)
        )
        val musixmatch = FakePlainLyricsProvider(
            "musixmatch",
            Result.success(result("musixmatch", "[00:01.00]unused"))
        )
        val logger = RecordingLogger()
        val engine = engine(
            onlineProviders = mapOf(
                PlainLyricsSearchSource.NETEASE to netease,
                PlainLyricsSearchSource.MUSIXMATCH to musixmatch
            ),
            settings = settings(
                plainLyricsSearchSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH
                )
            ),
            lookupLogger = logger
        )

        val found = engine.findLyrics(context, "Song", "Artist", durationMs = 180_000L)

        assertSame(cancellation, found.exceptionOrNull())
        assertEquals(1, netease.calls)
        assertEquals(0, musixmatch.calls)
        assertEquals(0, logger.failures)
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
        onlineProviders: Map<PlainLyricsSearchSource, PlainLyricsProvider> =
            mapOf(PlainLyricsSearchSource.NETEASE to online),
        settings: LyricsSettings = settings(),
        localPlainLyricsSaver: LocalPlainLyricsSaver = RecordingPlainLyricsSaver(),
        wordByWordLyricsReader: WordByWordLyricsReader = WordByWordLyricsReader { _, _, _, _ -> emptyList() },
        lookupLogger: LyricsLookupLogger = RecordingLogger()
    ): LyricsRepositoryEngine {
        return LyricsRepositoryEngine(
            localPlainLyricsProvider = local,
            onlinePlainLyricsProviders = onlineProviders,
            settingsReader = { settings },
            localPlainLyricsSaver = localPlainLyricsSaver,
            wordByWordLyricsReader = wordByWordLyricsReader,
            lookupLogger = lookupLogger
        )
    }

    private fun settings(
        plainLyricsSearchSources: List<PlainLyricsSearchSource> =
            listOf(PlainLyricsSearchSource.NETEASE),
        autoSearchOnline: Boolean = true,
        autoSaveLocal: Boolean = true,
        wordByWordLyricsEnabled: Boolean = false
    ): LyricsSettings {
        return LyricsSettings(
            plainLyricsSearchSources = plainLyricsSearchSources,
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
        private val response: (PlainLyricsSearchRequest) -> Result<LyricsProviderResult?>
    ) : PlainLyricsProvider {
        constructor(
            id: String,
            response: Result<LyricsProviderResult?>
        ) : this(id, { response })

        override val name: String = id
        private val requests = mutableListOf<PlainLyricsSearchRequest>()
        val calls: Int
            get() = requests.size

        override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
            requests += request
            return response(request)
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

    private data class ProviderProgress(
        val providerId: String,
        val priority: Int,
        val total: Int,
        val stage: LyricsLookupProviderStage
    )

    private class RecordingLogger : LyricsLookupLogger {
        var failures: Int = 0
            private set
        val progress = mutableListOf<ProviderProgress>()

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

        override fun logProviderProgress(
            provider: PlainLyricsProvider,
            priority: Int,
            total: Int,
            stage: LyricsLookupProviderStage,
            debug: Boolean
        ) {
            progress += ProviderProgress(provider.id, priority, total, stage)
        }
    }
}
