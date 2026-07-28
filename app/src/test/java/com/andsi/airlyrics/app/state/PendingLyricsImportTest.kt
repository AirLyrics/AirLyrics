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
    fun bundleRoundTrip_preservesTargetAndType() {
        val request = PendingLyricsImport(
            target = SongIdentity(
                title = "Original song",
                artist = "Original artist",
                album = "Original album",
                durationMs = 185_000L
            ),
            type = LyricsImportType.WORD_BY_WORD
        )

        assertEquals(request, request.toBundle().toPendingLyricsImport())
    }

    @Test
    fun malformedBundle_doesNotRestoreRequest() {
        assertNull(Bundle().toPendingLyricsImport())
        assertNull(
            PendingLyricsImport(
                target = SongIdentity("Song", "Artist", durationMs = 1L),
                type = LyricsImportType.PLAIN
            ).toBundle().apply {
                putString("type", "UNKNOWN")
            }.toPendingLyricsImport()
        )
    }

    @Test
    fun consumePendingLyricsImport_returnsRequestOnlyOnce() {
        val request = PendingLyricsImport(
            target = SongIdentity("Song", "Artist", durationMs = 1L),
            type = LyricsImportType.PLAIN
        )
        val state = MainActivityState().apply {
            pendingLyricsImport = request
        }

        assertEquals(request, state.consumePendingLyricsImport())
        assertNull(state.consumePendingLyricsImport())
        assertNull(state.pendingLyricsImport)
    }

    @Test
    fun pendingOverwriteBundleRoundTrip_preservesUriTargetAndType() {
        val request = PendingLyricsOverwrite(
            uri = Uri.parse("content://lyrics/original-request.lrc"),
            target = SongIdentity(
                title = "Original overwrite song",
                artist = "Original overwrite artist",
                album = "Original overwrite album",
                durationMs = 245_000L
            ),
            type = LyricsImportType.WORD_BY_WORD
        )

        assertEquals(request, request.toBundle().toPendingLyricsOverwrite())
    }
}
