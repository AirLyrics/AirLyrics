package com.andsi.airlyrics.floating

import android.os.SystemClock
import android.widget.TextView
import com.andsi.airlyrics.lyrics.display.LyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode

/**
 * Maintains parsed lyric lines and renders the line matching the current playback position.
 */
class FloatingLyricsRenderer(
    private val textViewProvider: () -> TextView?,
    private val contentModeProvider: () -> LyricsContentDisplayMode = { LyricsContentDisplayMode.default },
    private val lineModeProvider: () -> LyricsLineDisplayMode = { LyricsLineDisplayMode.default }
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

    fun parseAndShow(
        lyrics: String,
        translatedLyrics: String? = null,
        emptyText: String
    ) {
        currentLyrics = LrcParser.parseWithTranslation(lyrics, translatedLyrics)

        show(
            if (currentLyrics.isNotEmpty()) {
                renderTextAtCurrentPosition().takeIf { it.isNotBlank() }
                    ?: renderTextAtIndex(0).takeIf { it.isNotBlank() }
                    ?: emptyText
            } else {
                emptyText
            }
        )
    }

    fun tick() {
        if (currentLyrics.isEmpty()) return

        val text = renderTextAtCurrentPosition().takeIf { it.isNotBlank() } ?: return
        show(text)
    }

    fun refresh() {
        if (currentLyrics.isEmpty()) return
        val text = renderTextAtCurrentPosition().takeIf { it.isNotBlank() }
            ?: renderTextAtIndex(0).takeIf { it.isNotBlank() }
            ?: return
        show(text)
    }

    private fun renderTextAtCurrentPosition(): String {
        val currentIndex = LrcParser.findCurrentIndex(currentLyrics, getEstimatedPositionMs()) ?: return ""
        return renderTextAtIndex(currentIndex)
    }

    private fun renderTextAtIndex(index: Int): String {
        return LyricsDisplayFormatter.format(
            lines = currentLyrics,
            currentIndex = index,
            contentMode = contentModeProvider(),
            lineMode = lineModeProvider()
        )
    }

    fun getEstimatedPositionMs(): Long {
        if (!currentIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return currentPositionMs
        }

        val elapsedMs = SystemClock.uptimeMillis() - lastPositionUpdateUptimeMs
        return currentPositionMs + elapsedMs.coerceAtLeast(0L)
    }
}
