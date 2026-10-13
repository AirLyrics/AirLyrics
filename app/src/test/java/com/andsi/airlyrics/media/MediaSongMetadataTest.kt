package com.andsi.airlyrics.media

import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaSongMetadataTest {
    @Test
    fun neteaseUsesCompleteDisplayPairInsteadOfBluetoothLyrics() {
        val metadata = metadata("再见太难", "利得汇")
        assertEquals(
            MediaSongMetadata("再见太难", "利得汇"),
            resolveMediaSongMetadata("com.netease.cloudmusic", metadata)
        )
    }

    @Test
    fun incompleteDisplayPairFallsBackTogether() {
        listOf(null, "", "  ").forEach { missing ->
            listOf(metadata(missing, "利得汇"), metadata("再见太难", missing)).forEach {
                assertEquals(
                    MediaSongMetadata("说再见太难", "再见太难 - 利得汇"),
                    resolveMediaSongMetadata("com.netease.cloudmusic", it)
                )
            }
        }
    }

    @Test
    fun otherPlayersKeepStandardFieldsEvenWithDisplayPair() {
        assertEquals(
            MediaSongMetadata("说再见太难", "再见太难 - 利得汇"),
            resolveMediaSongMetadata("other.player", metadata("再见太难", "利得汇"))
        )
    }

    @Test
    fun standardFieldsPreserveAlbumArtistFallbackAndEmptyArtist() {
        val base = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "偿还")
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "王菲")
        for (pkg in listOf("com.netease.cloudmusic", "other.player")) {
            assertEquals(MediaSongMetadata("偿还", "王菲"), resolveMediaSongMetadata(pkg, base.build()))
            assertEquals(
                MediaSongMetadata("偿还", ""),
                resolveMediaSongMetadata(pkg, MediaMetadata.Builder(base.build())
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "").build())
            )
            assertEquals(MediaSongMetadata(null, null), resolveMediaSongMetadata(pkg, null))
        }
    }

    private fun metadata(title: String?, artist: String?): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, "说再见太难")
        .putString(MediaMetadata.METADATA_KEY_ARTIST, "再见太难 - 利得汇")
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
        .build()
}
