package com.andsi.airlyrics.media

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrentMediaBroadcastTest {
    @Test
    fun mediaUpdateIntent_roundTripsCurrentMediaInfo() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val media = CurrentMediaInfo(
            sourcePackage = "com.example.player",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            isPlaying = true,
            positionMs = 42_000L,
            snapshotSequence = 7L
        )

        val decoded = CurrentMediaBroadcast.readMediaUpdate(
            CurrentMediaBroadcast.mediaUpdateIntent(context, media)
        )

        assertEquals(media, decoded)
    }

    @Test
    fun mediaSourceLostIntent_roundTripsSourcePackage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = CurrentMediaBroadcast.mediaSourceLostIntent(context, "com.example.player")

        assertNotNull(intent)
        assertEquals("com.example.player", CurrentMediaBroadcast.readMediaSourceLost(intent))
    }

    @Test
    fun readMediaUpdate_ignoresWrongActionAndBlankTitle() {
        val wrongAction = Intent("com.example.UNRELATED")
        val blankTitle = CurrentMediaBroadcast.mediaUpdateIntent(
            ApplicationProvider.getApplicationContext(),
            CurrentMediaInfo(
                sourcePackage = "com.example.player",
                title = "",
                artist = "Artist",
                album = "Album",
                durationMs = 1L,
                isPlaying = false,
                positionMs = 0L
            )
        )

        assertNull(CurrentMediaBroadcast.readMediaUpdate(wrongAction))
        assertNull(CurrentMediaBroadcast.readMediaUpdate(blankTitle))
    }
}
