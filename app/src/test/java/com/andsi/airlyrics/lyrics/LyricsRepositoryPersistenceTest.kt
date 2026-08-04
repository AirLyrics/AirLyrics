package com.andsi.airlyrics.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsFileStore
import com.andsi.airlyrics.lyrics.storage.LyricsIndexStore
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsRepositoryPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
    }

    @After
    fun tearDown() {
        resetStorage()
    }

    @Test
    fun onlineResult_autoSave_persistsMergedPlainLrcAndIndexUnlessWordByWordExists() {
        val storedSongResult = providerResult(
            plainLrc = "[00:01.00]hello",
            translatedLrc = "[00:01.00]你好"
        )
        val wordByWordProtectedResult = providerResult(
            plainLrc = "[00:10.00]online replacement",
            translatedLrc = "[00:10.00]线上替换"
        )
        val provider = object : PlainLyricsProvider {
            override val id: String = "netease"
            override val name: String = "NetEase Lyrics"

            override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
                return Result.success(
                    if (request.title == STORED_TITLE) storedSongResult else wordByWordProtectedResult
                )
            }
        }

        withNeteasePlainLyricsProvider(provider) {
            val found = LyricsRepository.findLyrics(
                context = context,
                settings = onlineAutoSaveSettings(),
                title = STORED_TITLE,
                artist = ARTIST,
                album = ALBUM,
                durationMs = STORED_DURATION_MS
            ).getOrThrow()

            assertEquals(storedSongResult, found)
            assertEquals(
                "[00:01.00]hello / 你好",
                LyricsStorage.readPlainLyrics(
                    context,
                    STORED_TITLE,
                    ARTIST,
                    STORED_DURATION_MS
                )
            )

            val storedEntry = LyricsIndexStore.find(
                context,
                STORED_TITLE,
                ARTIST,
                STORED_DURATION_MS
            )
            assertNotNull("Auto-save must create a managed index entry", storedEntry)
            storedEntry!!
            assertEquals(ALBUM, storedEntry.album)
            assertEquals(LyricsStorage.SOURCE_DOWNLOADED, storedEntry.plainSource)
            assertEquals("netease", storedEntry.plainProvider)
            assertTrue(storedEntry.plainFile.isNotBlank())
            assertEquals(
                "[00:01.00]hello / 你好",
                LyricsFileStore.readManagedLyrics(context, storedEntry.plainFile)
            )

            val wordByWordLines = listOf(
                WordByWordLine(
                    startMs = 10_000L,
                    endMs = 11_000L,
                    text = "protected karaoke",
                    segments = listOf(
                        WordByWordSegment(
                            text = "protected karaoke",
                            startMs = 10_000L,
                            endMs = 11_000L
                        )
                    )
                )
            )
            assertTrue(
                LyricsStorage.saveWordByWordLyrics(
                    context = context,
                    title = WORD_BY_WORD_TITLE,
                    artist = ARTIST,
                    duration = WORD_BY_WORD_DURATION_MS,
                    wordByWordLines = wordByWordLines,
                    album = ALBUM,
                    wordByWordProvider = "local-karaoke"
                )
            )

            val protectedFound = LyricsRepository.findLyrics(
                context = context,
                settings = onlineAutoSaveSettings(),
                title = WORD_BY_WORD_TITLE,
                artist = ARTIST,
                album = ALBUM,
                durationMs = WORD_BY_WORD_DURATION_MS
            ).getOrThrow()

            assertEquals(wordByWordProtectedResult, protectedFound)
            assertNull(
                "Online auto-save must not add or replace plain lyrics when word-by-word lyrics exist",
                LyricsStorage.readPlainLyrics(
                    context,
                    WORD_BY_WORD_TITLE,
                    ARTIST,
                    WORD_BY_WORD_DURATION_MS
                )
            )
            assertEquals(
                wordByWordLines,
                LyricsStorage.readWordByWordLyrics(
                    context,
                    WORD_BY_WORD_TITLE,
                    ARTIST,
                    WORD_BY_WORD_DURATION_MS
                )
            )
            val protectedEntry = LyricsIndexStore.find(
                context,
                WORD_BY_WORD_TITLE,
                ARTIST,
                WORD_BY_WORD_DURATION_MS
            )
            assertNotNull(protectedEntry)
            assertTrue(protectedEntry!!.plainFile.isBlank())
            assertTrue(protectedEntry.wordByWordFile.isNotBlank())
            assertEquals("local-karaoke", protectedEntry.wordByWordProvider)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> withNeteasePlainLyricsProvider(provider: PlainLyricsProvider, block: () -> T): T {
        val field = LyricsRepository::class.java.getDeclaredField("onlinePlainLyricsProviders").apply {
            isAccessible = true
        }
        val providers = field.get(LyricsRepository) as MutableMap<PlainLyricsSearchSource, PlainLyricsProvider>
        val previous = providers.put(PlainLyricsSearchSource.NETEASE, provider)
        return try {
            block()
        } finally {
            if (previous == null) {
                providers.remove(PlainLyricsSearchSource.NETEASE)
            } else {
                providers[PlainLyricsSearchSource.NETEASE] = previous
            }
        }
    }

    private fun onlineAutoSaveSettings(): LyricsSettings {
        return LyricsSettings(
            plainLyricsSearchSource = PlainLyricsSearchSource.NETEASE,
            autoSearchOnline = true,
            autoSaveLocal = true,
            contentDisplayMode = LyricsContentDisplayMode.default,
            lineDisplayMode = LyricsLineDisplayMode.default,
            switchAnimationMode = LyricsSwitchAnimationMode.default,
            wordByWordLyricsEnabled = false
        )
    }

    private fun providerResult(
        plainLrc: String,
        translatedLrc: String
    ): LyricsProviderResult {
        return LyricsProviderResult(
            plainProviderId = "netease",
            plainProviderName = "NetEase Lyrics",
            plainLrc = plainLrc,
            translatedLrc = translatedLrc,
            matchedTitle = "matched title",
            matchedArtist = "matched artist",
            matchedAlbum = "matched album",
            matchedDurationMs = 180_000L
        )
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    private companion object {
        const val STORED_TITLE = "Repository Persisted Song"
        const val WORD_BY_WORD_TITLE = "Repository Karaoke Song"
        const val ARTIST = "AndSi"
        const val ALBUM = "Repository Album"
        const val STORED_DURATION_MS = 180_000L
        const val WORD_BY_WORD_DURATION_MS = 200_000L
    }
}
