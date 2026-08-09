package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
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
class LyricsStorageDeleteAllTest {
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
    fun deleteAllSavedLyrics_removesManagedOrphanAndLegacyLyrics_butPreservesOtherFiles() {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Plain song",
                artist = "AirLyrics",
                duration = 180_000L,
                plainLrc = "[00:01.00]plain"
            )
        )
        assertTrue(
            LyricsStorage.saveWordByWordLyrics(
                context = context,
                title = "Word song",
                artist = "AirLyrics",
                duration = 200_000L,
                wordByWordLines = listOf(
                    WordByWordLine(
                        startMs = 1_000L,
                        endMs = 2_000L,
                        text = "word",
                        segments = listOf(WordByWordSegment("word", 1_000L, 2_000L))
                    )
                )
            )
        )

        val root = LyricsStoragePaths.fallbackLyricsDir(context)
        val managedDir = LyricsStoragePaths.fallbackManagedLyricsDir(context)
        val legacyLyrics = File(
            root,
            LyricsFileNaming.legacyPlainFileName("Legacy song", "AirLyrics", 220_000L)
        ).apply { writeText("[00:01.00]legacy") }
        val orphanManagedLyrics = File(managedDir, "orphan.lrc").apply {
            writeText("[00:01.00]orphan")
        }
        val unrelatedRootLyrics = File(root, "personal-reference.lrc").apply {
            writeText("[00:01.00]keep")
        }
        val unrelatedManagedFile = File(managedDir, "notes.txt").apply {
            writeText("keep")
        }

        assertEquals(
            LyricsStorage.DeleteAllSavedLyricsResult.DELETED,
            LyricsStorage.deleteAllSavedLyrics(context)
        )

        assertTrue(LyricsIndexStore.read(context).isEmpty())
        assertFalse(legacyLyrics.exists())
        assertFalse(orphanManagedLyrics.exists())
        assertFalse(managedDir.listFiles().orEmpty().any { file ->
            LyricsFileNaming.isPlainLyricsFile(file.name) ||
                LyricsFileNaming.isWordByWordLyricsFile(file.name)
        })
        assertTrue(unrelatedRootLyrics.exists())
        assertTrue(unrelatedManagedFile.exists())
        assertEquals(
            LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE,
            LyricsStorage.deleteAllSavedLyrics(context)
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
}
