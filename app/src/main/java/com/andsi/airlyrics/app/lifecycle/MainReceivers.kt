package com.andsi.airlyrics.app.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.floating.FloatingWindowStateBroadcast
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangedBroadcast
import com.andsi.airlyrics.media.CurrentMediaBroadcast

/**
 * Owns main-screen broadcast receiver instances and their registration.
 *
 * MainGraph registers this group only while the activity is started. The receivers
 * stay thin: they validate the incoming Intent and forward it to graph/controller code.
 */
internal class MainReceivers(
    private val context: Context,
    private val onMediaChanged: (Intent) -> Unit,
    private val onFloatingStateChanged: (Intent) -> Unit,
    private val onLyricsChanged: (LyricsChange) -> Unit
) {
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(onMediaChanged)
        }
    }

    private val floatingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(onFloatingStateChanged)
        }
    }

    private val lyricsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LyricsChangedBroadcast.readChange(intent)?.let(onLyricsChanged)
        }
    }

    private var registered = false

    fun register() {
        if (registered) return

        ContextCompat.registerReceiver(
            context,
            floatingReceiver,
            FloatingWindowStateBroadcast.windowStateFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            mediaReceiver,
            CurrentMediaBroadcast.mediaStatusFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            lyricsChangedReceiver,
            LyricsChangedBroadcast.lyricsChangedFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        registered = true
    }

    fun unregister() {
        if (!registered) return

        runCatching { context.unregisterReceiver(floatingReceiver) }
        runCatching { context.unregisterReceiver(mediaReceiver) }
        runCatching { context.unregisterReceiver(lyricsChangedReceiver) }
        registered = false
    }
}
