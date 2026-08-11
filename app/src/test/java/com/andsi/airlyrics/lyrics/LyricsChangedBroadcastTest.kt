package com.andsi.airlyrics.lyrics

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsChangedBroadcastTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun lyricsChangedIntent_roundTripsSongIdentityAndIsPackageScoped() {
        val target = SongIdentity(
            title = "Codec Song",
            artist = "Codec Artist",
            album = "Codec Album",
            durationMs = 183_456L
        )

        val intent = LyricsChangedBroadcast.lyricsChangedIntent(context, target)

        assertEquals(context.packageName, intent?.`package`)
        assertEquals(LyricsChange.updated(target), LyricsChangedBroadcast.readChange(intent))
        assertEquals(target, LyricsChangedBroadcast.readTarget(intent))
        assertTrue(LyricsChangedBroadcast.lyricsChangedFilter().hasAction(intent?.action))
    }

    @Test
    fun deletedChange_roundTripsForOneSongOrTheWholeLibrary() {
        val target = SongIdentity(
            title = "Removed Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L
        )

        val targetedIntent = LyricsChangedBroadcast.lyricsChangedIntent(
            context,
            LyricsChange.deleted(target)
        )
        val globalIntent = LyricsChangedBroadcast.lyricsChangedIntent(
            context,
            LyricsChange.deleted()
        )

        assertEquals(LyricsChange.deleted(target), LyricsChangedBroadcast.readChange(targetedIntent))
        assertEquals(LyricsChange.deleted(), LyricsChangedBroadcast.readChange(globalIntent))
        assertNull(LyricsChangedBroadcast.readTarget(globalIntent))
    }

    @Test
    fun readTarget_rejectsWrongActionMalformedPayloadAndEmptyTarget() {
        val validTarget = SongIdentity(
            title = "Valid Song",
            artist = "Artist",
            album = "Album",
            durationMs = 120_000L
        )
        val valid = requireNotNull(
            LyricsChangedBroadcast.lyricsChangedIntent(context, validTarget)
        )
        val wrongAction = Intent("com.example.UNRELATED").putExtras(valid)
        val missingDuration = Intent(valid).apply { removeExtra("duration") }
        val invalidKind = Intent(valid).putExtra("changeKind", "UNKNOWN")
        val blankTitle = LyricsChangedBroadcast.lyricsChangedIntent(
            context,
            validTarget.copy(title = " ")
        )

        assertNull(LyricsChangedBroadcast.readTarget(wrongAction))
        assertNull(LyricsChangedBroadcast.readTarget(missingDuration))
        assertNull(LyricsChangedBroadcast.readChange(invalidKind))
        assertNull(blankTitle)
        assertNull(LyricsChangedBroadcast.readTarget(null))
    }
}
