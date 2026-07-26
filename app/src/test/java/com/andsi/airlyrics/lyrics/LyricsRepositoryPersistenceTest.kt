package com.andsi.airlyrics.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSearchSource
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
    fun onlineResult_autoSave_persistsMergedLyricsAndIndexUnlessKaraokeExists() {
        val storedSongResult = providerResult(
            lyrics = "[00:01.00]hello",
            translatedLyrics = "[00:01.00]你好"
        )
        val karaokeProtectedResult = providerResult(
            lyrics = "[00:10.00]online replacement",
            translatedLyrics = "[00:10.00]线上替换"
        )
        val provider = object : LyricsProvider {
            override val id: String = "netease"
            override val name: String = "NetEase Lyrics"

            override fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?> {
                return Result.success(
                    if (request.title == STORED_TITLE) storedSongResult else karaokeProtectedResult
                )
            }
        }

        withNeteaseProvider(provider) {
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
                LyricsStorage.readLocalLyrics(
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
            assertEquals(LyricsStorage.SOURCE_DOWNLOADED, storedEntry.source)
            assertEquals("netease", storedEntry.provider)
            assertTrue(storedEntry.file.isNotBlank())
            assertEquals(
                "[00:01.00]hello / 你好",
                LyricsFileStore.readManagedLyrics(context, storedEntry.file)
            )

            val karaokeLines = listOf(
                KaraokeLine(
                    startMs = 10_000L,
                    endMs = 11_000L,
                    text = "protected karaoke",
                    tokens = listOf(
                        KaraokeToken(
                            text = "protected karaoke",
                            startMs = 10_000L,
                            endMs = 11_000L
                        )
                    )
                )
            )
            assertTrue(
                LyricsStorage.saveKaraokeLyrics(
                    context = context,
                    title = KARAOKE_TITLE,
                    artist = ARTIST,
                    duration = KARAOKE_DURATION_MS,
                    karaokeLines = karaokeLines,
                    album = ALBUM,
                    provider = "local-karaoke"
                )
            )

            val protectedFound = LyricsRepository.findLyrics(
                context = context,
                settings = onlineAutoSaveSettings(),
                title = KARAOKE_TITLE,
                artist = ARTIST,
                album = ALBUM,
                durationMs = KARAOKE_DURATION_MS
            ).getOrThrow()

            assertEquals(karaokeProtectedResult, protectedFound)
            assertNull(
                "Online auto-save must not add or replace plain lyrics when karaoke exists",
                LyricsStorage.readLocalLyrics(
                    context,
                    KARAOKE_TITLE,
                    ARTIST,
                    KARAOKE_DURATION_MS
                )
            )
            assertEquals(
                karaokeLines,
                LyricsStorage.readKaraokeLyrics(
                    context,
                    KARAOKE_TITLE,
                    ARTIST,
                    KARAOKE_DURATION_MS
                )
            )
            val protectedEntry = LyricsIndexStore.find(
                context,
                KARAOKE_TITLE,
                ARTIST,
                KARAOKE_DURATION_MS
            )
            assertNotNull(protectedEntry)
            assertTrue(protectedEntry!!.file.isBlank())
            assertTrue(protectedEntry.karaokeFile.isNotBlank())
            assertEquals("local-karaoke", protectedEntry.karaokeProvider)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> withNeteaseProvider(provider: LyricsProvider, block: () -> T): T {
        val field = LyricsRepository::class.java.getDeclaredField("onlineProviders").apply {
            isAccessible = true
        }
        val providers = field.get(LyricsRepository) as MutableMap<LyricsSearchSource, LyricsProvider>
        val previous = providers.put(LyricsSearchSource.NETEASE, provider)
        return try {
            block()
        } finally {
            if (previous == null) {
                providers.remove(LyricsSearchSource.NETEASE)
            } else {
                providers[LyricsSearchSource.NETEASE] = previous
            }
        }
    }

    private fun onlineAutoSaveSettings(): LyricsSettings {
        return LyricsSettings(
            source = LyricsSearchSource.NETEASE,
            autoSearchOnline = true,
            autoSaveLocal = true,
            contentDisplayMode = LyricsContentDisplayMode.default,
            lineDisplayMode = LyricsLineDisplayMode.default,
            switchAnimationMode = LyricsSwitchAnimationMode.default,
            karaokeLyricsEnabled = false
        )
    }

    private fun providerResult(
        lyrics: String,
        translatedLyrics: String
    ): LyricsProviderResult {
        return LyricsProviderResult(
            providerId = "netease",
            providerName = "NetEase Lyrics",
            lyrics = lyrics,
            translatedLyrics = translatedLyrics,
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
        const val KARAOKE_TITLE = "Repository Karaoke Song"
        const val ARTIST = "AndSi"
        const val ALBUM = "Repository Album"
        const val STORED_DURATION_MS = 180_000L
        const val KARAOKE_DURATION_MS = 200_000L
    }
}
