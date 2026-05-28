package com.andsi.airlyrics.floating

import android.os.SystemClock
import android.widget.TextView
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.lyrics.parser.LrcParser

/**
 * Maintains parsed lyric lines and renders the line matching the current playback position.
 */
class FloatingLyricsRenderer(
    private val textViewProvider: () -> TextView?
) {
    private var currentLyrics: List<LrcLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false

    fun updatePlayback(positionMs: Long, isPlaying: Boolean) {
        currentPositionMs = positionMs
        currentIsPlaying = isPlaying
        lastPositionUpdateUptimeMs = SystemClock.uptimeMillis()
    }

    fun clear() {
        currentLyrics = emptyList()
        currentPositionMs = 0L
        lastPositionUpdateUptimeMs = 0L
        currentIsPlaying = false
    }

    fun show(text: String) {
        textViewProvider()?.text = text
    }

    fun parseAndShow(lyrics: String, emptyText: String) {
        currentLyrics = LrcParser.parse(lyrics)

        show(
            if (currentLyrics.isNotEmpty()) {
                LrcParser.findCurrentLine(currentLyrics, getEstimatedPositionMs())?.text
                    ?: currentLyrics.first().text
            } else {
                emptyText
            }
        )
    }

    fun tick() {
        if (currentLyrics.isEmpty()) return

        val line = LrcParser.findCurrentLine(currentLyrics, getEstimatedPositionMs()) ?: return
        show(line.text)
    }

    fun getEstimatedPositionMs(): Long {
        if (!currentIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return currentPositionMs
        }

        val elapsedMs = SystemClock.uptimeMillis() - lastPositionUpdateUptimeMs
        return currentPositionMs + elapsedMs.coerceAtLeast(0L)
    }
}
