package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingServiceCommandTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun restoreCommand_roundTripsWithoutAction() {
        val intent = FloatingServiceCommand.Restore.toIntent(context)

        assertNull(intent.action)
        assertSame(FloatingServiceCommand.Restore, FloatingServiceCommand.fromIntent(intent))
        assertSame(FloatingServiceCommand.Restore, FloatingServiceCommand.fromIntent(null))
    }

    @Test
    fun simpleCommands_roundTripByIdentity() {
        val commands = listOf(
            FloatingServiceCommand.Show,
            FloatingServiceCommand.Hide,
            FloatingServiceCommand.Lock,
            FloatingServiceCommand.Unlock,
            FloatingServiceCommand.ClickThroughOn,
            FloatingServiceCommand.ClickThroughOff,
            FloatingServiceCommand.ToggleVisibleFromNotification,
            FloatingServiceCommand.ToggleLockFromNotification,
            FloatingServiceCommand.ToggleClickThroughFromNotification,
            FloatingServiceCommand.ToggleAdjustModeFromNotification,
            FloatingServiceCommand.ApplyAutoHideWhenPaused,
            FloatingServiceCommand.ApplyDisplayScope,
            FloatingServiceCommand.ApplyStyle,
            FloatingServiceCommand.ReloadLyrics
        )

        commands.forEach { command ->
            assertSame(command, FloatingServiceCommand.fromIntent(command.toIntent(context)))
        }
    }

    @Test
    fun valueCommands_roundTripByValue() {
        val uri = Uri.parse("content://com.example.lyrics/song.lrc")
        val commands = listOf(
            FloatingServiceCommand.ApplyLyricsOffset(1_250L),
            FloatingServiceCommand.SelectMediaSource("com.example.player"),
            FloatingServiceCommand.SelectMediaSource(null),
            FloatingServiceCommand.ImportPlainLyrics(uri = uri, overwrite = false)
        )

        commands.forEach { command ->
            assertEquals(command, FloatingServiceCommand.fromIntent(command.toIntent(context)))
        }
    }

    @Test
    fun fromIntent_ignoresUnknownOrMalformedCommand() {
        val missingUri = FloatingServiceCommand.ImportPlainLyrics(
            uri = Uri.parse("content://com.example.lyrics/song.lrc")
        ).toIntent(context).apply {
            data = null
        }

        assertNull(FloatingServiceCommand.fromIntent(Intent("com.example.UNKNOWN")))
        assertNull(FloatingServiceCommand.fromIntent(missingUri))
    }
}
