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
            FloatingServiceCommand.ToggleAdjustModeFromNotification,
            FloatingServiceCommand.ApplyAutoHideSettings,
            FloatingServiceCommand.ApplyDisplayScope,
            FloatingServiceCommand.ApplyStyle,
            FloatingServiceCommand.ReloadLyrics,
            FloatingServiceCommand.SyncMediaSource
        )

        commands.forEach { command ->
            assertSame(command, FloatingServiceCommand.fromIntent(command.toIntent(context)))
        }
    }

    @Test
    fun lyricsOffsetCommand_roundTripsByValue() {
        val command = FloatingServiceCommand.ApplyLyricsOffset(1_250L)

        assertEquals(command, FloatingServiceCommand.fromIntent(command.toIntent(context)))
    }

    @Test
    fun fromIntent_ignoresUnknownCommand() {
        assertNull(FloatingServiceCommand.fromIntent(Intent("com.example.UNKNOWN")))
    }

    @Test
    fun retiredPlainImportIntentIsIgnored() {
        val intent = Intent("com.andsi.airlyrics.IMPORT_LYRICS").apply {
            data = Uri.parse("content://com.example.lyrics/song.lrc")
        }

        assertNull(FloatingServiceCommand.fromIntent(intent))
    }

    @Test
    fun retiredNotificationAndAutoHideCommandsAreIgnored() {
        val retired = listOf(
            "com.andsi.airlyrics.NOTIFICATION_TOGGLE_LOCK",
            "com.andsi.airlyrics.NOTIFICATION_TOGGLE_CLICK_THROUGH",
            "com.andsi.airlyrics.APPLY_AUTO_HIDE_WHEN_PAUSED",
        )

        retired.forEach { action ->
            assertNull(action, FloatingServiceCommand.fromIntent(Intent(action)))
        }
    }
}
