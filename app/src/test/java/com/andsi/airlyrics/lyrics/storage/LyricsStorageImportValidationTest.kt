package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageImportValidationTest {
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
    fun importPlainLyrics_reportsInvalidLineNumbers() {
        val result = LyricsStorage.importLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-invalid.lrc",
                text = """
                    [ar:Artist]
                    not timed
                    [00:01.00]valid
                """.trimIndent()
            ),
            title = "Plain Invalid",
            artist = "AndSi",
            duration = 1_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.InvalidFormat)
        assertEquals(
            listOf(2),
            (result as LyricsStorage.ImportLyricsResult.InvalidFormat).invalidLineNumbers
        )
    }

    @Test
    fun importPlainLyrics_preservesMetadataWhenValid() {
        val result = LyricsStorage.importLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-valid.lrc",
                text = """
                    [ar:Artist]
                    [ti:Title]
                    [00:01.00]valid
                """.trimIndent()
            ),
            title = "Plain Valid",
            artist = "AndSi",
            duration = 2_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[ar:Artist]\n[ti:Title]\n[00:01.00]valid",
            LyricsStorage.readLocalLyrics(context, "Plain Valid", "AndSi", 2_000L)
        )
    }

    @Test
    fun importKaraokeLyrics_reportsInvalidLineNumbers() {
        val result = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-invalid.lrc",
                text = """
                    [ar:Artist]
                    not timed
                    [00:10.00]<00:10.00>valid
                """.trimIndent()
            ),
            title = "Karaoke Invalid",
            artist = "AndSi",
            duration = 10_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.InvalidFormat)
        assertEquals(
            listOf(2),
            (result as LyricsStorage.ImportLyricsResult.InvalidFormat).invalidLineNumbers
        )
    }

    @Test
    fun importKaraokeLyrics_preservesMetadataInStoredEnhancedLrc() {
        val result = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-valid.lrc",
                text = """
                    [ar:Artist]
                    [00:10.00]<00:10.00>valid
                """.trimIndent()
            ),
            title = "Karaoke Valid",
            artist = "AndSi",
            duration = 10_000L
        )

        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Valid" }

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[ar:Artist]\n[00:10.00]<00:10.00>valid",
            LyricsStorage.readLocalLyricsItemText(
                context = context,
                item = item,
                target = LyricsStorage.LocalLyricsEditTarget.KARAOKE
            )
        )
    }

    @Test
    fun importKaraokeLyrics_marksGeneratedPlainFallbackSource() {
        val result = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-fallback-source.lrc",
                text = """
                    [00:10.00]<00:10.00>valid
                """.trimIndent()
            ),
            title = "Karaoke Fallback Source",
            artist = "AndSi",
            duration = 10_000L
        )

        val info = LyricsStorage.getLocalLyricsInfo(context, "Karaoke Fallback Source", "AndSi", 10_000L)

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(LyricsStorage.SOURCE_KARAOKE_FALLBACK, info?.source)
    }

    @Test
    fun updateKaraokeLyrics_syncsGeneratedPlainFallbackAndPreservesTranslation() {
        LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-fallback-sync.lrc",
                text = """
                    [00:10.00]<00:10.00>old
                    [00:10.00]旧翻译
                """.trimIndent()
            ),
            title = "Karaoke Fallback Sync",
            artist = "AndSi",
            duration = 10_000L
        )
        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Fallback Sync" }

        val update = LyricsStorage.updateKaraokeLyricsItemTextWithResult(
            context = context,
            item = item,
            text = "[00:10.00]<00:10.00>new"
        )

        assertTrue(update.saved)
        assertEquals(
            "[00:10.00]new / 旧翻译",
            LyricsStorage.readLocalLyrics(context, "Karaoke Fallback Sync", "AndSi", 10_000L)
        )
    }

    @Test
    fun updateKaraokeLyrics_doesNotOverwriteManualPlainLyrics() {
        val manualPlain = "[00:10.00]manual plain"
        assertTrue(
            LyricsStorage.saveLyrics(
                context = context,
                title = "Karaoke Manual Plain",
                artist = "AndSi",
                duration = 10_000L,
                lyrics = manualPlain,
                source = LyricsStorage.SOURCE_MANUAL_IMPORT
            )
        )
        val importResult = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-manual-plain.lrc",
                text = "[00:10.00]<00:10.00>karaoke"
            ),
            title = "Karaoke Manual Plain",
            artist = "AndSi",
            duration = 10_000L
        )
        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Manual Plain" }

        val update = LyricsStorage.updateKaraokeLyricsItemTextWithResult(
            context = context,
            item = item,
            text = "[00:10.00]<00:10.00>changed"
        )

        assertTrue(importResult is LyricsStorage.ImportLyricsResult.Saved)
        assertTrue(update.saved)
        assertFalse(LyricsStorage.getLocalLyricsInfo(context, "Karaoke Manual Plain", "AndSi", 10_000L)?.source == LyricsStorage.SOURCE_KARAOKE_FALLBACK)
        assertEquals(manualPlain, LyricsStorage.readLocalLyrics(context, "Karaoke Manual Plain", "AndSi", 10_000L))
    }

    private fun writeImportFile(name: String, text: String): Uri {
        val file = File(context.cacheDir, name)
        file.writeText(text)
        return Uri.fromFile(file)
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }
}
