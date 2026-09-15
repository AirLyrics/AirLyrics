package com.andsi.airlyrics.app.state

import android.net.Uri
import android.os.Bundle
import com.andsi.airlyrics.core.model.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingLyricsImportTest {
    @Test
    fun pendingImportBundle_preservesSchemaAndRejectsMalformedPayloads() {
        val bundle = pendingImport().toBundle()

        assertEquals(1, bundle.getInt("version"))
        assertEquals("Original song", bundle.getString("title"))
        assertEquals("Original artist", bundle.getString("artist"))
        assertEquals("Original album", bundle.getString("album"))
        assertEquals(185_000L, bundle.getLong("duration_ms"))
        assertEquals("WORD_BY_WORD", bundle.getString("type"))
        assertEquals(pendingImport(), persistedImportBundle().toPendingLyricsImport())

        val malformedBundles = listOf(
            Bundle(),
            Bundle(persistedImportBundle()).apply { putInt("version", 2) },
            Bundle(persistedImportBundle()).apply { putString("title", "  ") },
            Bundle(persistedImportBundle()).apply { putString("type", "UNKNOWN") }
        )

        malformedBundles.forEachIndexed { index, malformed ->
            assertNull("malformed import bundle #$index", malformed.toPendingLyricsImport())
        }
    }

    @Test
    fun pendingOverwriteBundle_preservesSchemaAndRejectsMalformedPayloads() {
        val bundle = pendingOverwrite().toBundle()

        assertEquals(1, bundle.getInt("version"))
        assertEquals("content://lyrics/original-request.lrc", bundle.getString("uri"))
        assertEquals("Original overwrite song", bundle.getString("title"))
        assertEquals("Original overwrite artist", bundle.getString("artist"))
        assertEquals("Original overwrite album", bundle.getString("album"))
        assertEquals(245_000L, bundle.getLong("duration_ms"))
        assertEquals("WORD_BY_WORD", bundle.getString("type"))
        assertEquals(pendingOverwrite(), persistedOverwriteBundle().toPendingLyricsOverwrite())

        val malformedBundles = listOf(
            Bundle(),
            Bundle(persistedOverwriteBundle()).apply { putInt("version", 2) },
            Bundle(persistedOverwriteBundle()).apply { putString("uri", "") },
            Bundle(persistedOverwriteBundle()).apply { putString("title", "") },
            Bundle(persistedOverwriteBundle()).apply { putString("type", "UNKNOWN") }
        )

        malformedBundles.forEachIndexed { index, malformed ->
            assertNull("malformed overwrite bundle #$index", malformed.toPendingLyricsOverwrite())
        }
    }

    private fun pendingImport(): PendingLyricsImport {
        return PendingLyricsImport(
            target = SongIdentity(
                title = "Original song",
                artist = "Original artist",
                album = "Original album",
                durationMs = 185_000L
            ),
            type = LyricsImportType.WORD_BY_WORD
        )
    }

    private fun persistedImportBundle(): Bundle {
        return Bundle().apply {
            putInt("version", 1)
            putString("title", "Original song")
            putString("artist", "Original artist")
            putString("album", "Original album")
            putLong("duration_ms", 185_000L)
            putString("type", "WORD_BY_WORD")
        }
    }

    private fun pendingOverwrite(): PendingLyricsOverwrite {
        return PendingLyricsOverwrite(
            uri = Uri.parse("content://lyrics/original-request.lrc"),
            target = SongIdentity(
                title = "Original overwrite song",
                artist = "Original overwrite artist",
                album = "Original overwrite album",
                durationMs = 245_000L
            ),
            type = LyricsImportType.WORD_BY_WORD
        )
    }

    private fun persistedOverwriteBundle(): Bundle {
        return Bundle().apply {
            putInt("version", 1)
            putString("uri", "content://lyrics/original-request.lrc")
            putString("title", "Original overwrite song")
            putString("artist", "Original overwrite artist")
            putString("album", "Original overwrite album")
            putLong("duration_ms", 245_000L)
            putString("type", "WORD_BY_WORD")
        }
    }
}
