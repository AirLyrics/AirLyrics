package com.andsi.airlyrics.lyrics.storage

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStoragePlainImportTest : LyricsStorageTestBase() {
    @Test
    fun importPlainLyrics_reportsReadFailedWhenUriCannotBeOpened() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = Uri.fromFile(File(context.cacheDir, "missing-plain.lrc")),
            title = "Plain Missing",
            artist = "AndSi",
            duration = 1_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.ReadFailed)
    }

    @Test
    fun importPlainLyrics_reportsSaveFailedWhenStorageCannotWrite() {
        File(context.getExternalFilesDir(null) ?: context.filesDir, FALLBACK_LYRICS_DIR)
            .writeText("not a directory")

        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-save-failed.lrc",
                text = "[00:01.00]valid"
            ),
            title = "Plain Save Failed",
            artist = "AndSi",
            duration = 1_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.SaveFailed)
    }

    @Test
    fun importPlainLyrics_reportsInvalidLineNumbers() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
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
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
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
            LyricsStorage.readPlainLyrics(context, "Plain Valid", "AndSi", 2_000L)
        )
    }

    @Test
    fun importPlainLyrics_preservesTimedEmptyLine() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-timed-empty-line.lrc",
                text = "[04:45.03]final lyric\n[04:48.92] "
            ),
            title = "Plain Timed Empty Line",
            artist = "AndSi",
            duration = 300_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[04:45.03]final lyric\n[04:48.92]",
            LyricsStorage.readPlainLyrics(
                context,
                "Plain Timed Empty Line",
                "AndSi",
                300_000L
            )
        )
    }

    @Test
    fun importPlainLyrics_convertsLineTimedTtmlToNormalizedLrc() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile("plain-line-timed.ttml", lineTimedTtml),
            title = "Plain Line TTML",
            artist = "AndSi",
            duration = 6_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[00:01.20]First line / 第一行\n[00:03.50]Second line",
            LyricsStorage.readPlainLyrics(context, "Plain Line TTML", "AndSi", 6_000L)
        )
    }

    @Test
    fun importPlainLyrics_flattensWordTimedTtmlWithoutSavingWordTiming() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile("plain-word-timed.ttml", wordTimedTtml),
            title = "Plain Word TTML",
            artist = "AndSi",
            duration = 13_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[00:10.00]hello world / 你好，世界",
            LyricsStorage.readPlainLyrics(context, "Plain Word TTML", "AndSi", 13_000L)
        )
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Plain Word TTML", "AndSi", 13_000L))
        assertTrue(LyricsStorage.readWordByWordLyrics(context, "Plain Word TTML", "AndSi", 13_000L).isEmpty())
    }

    @Test
    fun importPlainLyrics_decodesUtf16TtmlWithBom() {
        val encodings = listOf(
            "LE" to (byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + lineTimedTtml.toByteArray(Charsets.UTF_16LE)),
            "BE" to (byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + lineTimedTtml.toByteArray(Charsets.UTF_16BE))
        )

        encodings.forEachIndexed { index, (label, bytes) ->
            val title = "Plain UTF-16 $label TTML"
            val result = LyricsStorage.importPlainLyricsFromUriWithResult(
                context = context,
                uri = writeImportBytes("plain-utf16-${label.lowercase()}.ttml", bytes),
                title = title,
                artist = "AndSi",
                duration = 7_000L + index
            )

            assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
            assertEquals(
                "[00:01.20]First line / 第一行\n[00:03.50]Second line",
                LyricsStorage.readPlainLyrics(context, title, "AndSi", 7_000L + index)
            )
        }
    }

}
