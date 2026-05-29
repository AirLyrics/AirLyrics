package com.andsi.airlyrics.floating

import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.andsi.airlyrics.lyrics.display.LyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode

/**
 * Maintains parsed lyric lines and renders the line matching the current playback position.
 */
class FloatingLyricsRenderer(
    private val textViewProvider: () -> TextView?,
    private val contentModeProvider: () -> LyricsContentDisplayMode = { LyricsContentDisplayMode.default },
    private val lineModeProvider: () -> LyricsLineDisplayMode = { LyricsLineDisplayMode.default },
    private val switchAnimationModeProvider: () -> LyricsSwitchAnimationMode = { LyricsSwitchAnimationMode.default }
) {
    private var currentLyrics: List<LrcLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false
    private var lastRenderedText: String? = null

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
        lastRenderedText = null
        resetTextAnimationState()
    }

    fun show(text: String) {
        setTextImmediately(text)
    }

    fun parseAndShow(
        lyrics: String,
        translatedLyrics: String? = null,
        emptyText: String
    ) {
        currentLyrics = LrcParser.parseWithTranslation(lyrics, translatedLyrics)

        val text = if (currentLyrics.isNotEmpty()) {
            renderTextAtCurrentPosition().takeIf { it.isNotBlank() }
                ?: renderTextAtIndex(0).takeIf { it.isNotBlank() }
                ?: emptyText
        } else {
            emptyText
        }

        setTextImmediately(text)
    }

    fun tick() {
        if (currentLyrics.isEmpty()) return

        val text = renderTextAtCurrentPosition().takeIf { it.isNotBlank() } ?: return
        setTextWithOptionalAnimation(text)
    }

    fun refresh() {
        if (currentLyrics.isEmpty()) return
        val text = renderTextAtCurrentPosition().takeIf { it.isNotBlank() }
            ?: renderTextAtIndex(0).takeIf { it.isNotBlank() }
            ?: return
        setTextImmediately(text)
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

    private fun setTextImmediately(text: String) {
        val view = textViewProvider() ?: return
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.text = text
        lastRenderedText = text
    }

    private fun setTextWithOptionalAnimation(text: String) {
        if (text == lastRenderedText) return

        val view = textViewProvider() ?: return
        val mode = switchAnimationModeProvider()
        if (mode == LyricsSwitchAnimationMode.NONE || lastRenderedText == null) {
            setTextImmediately(text)
            return
        }

        view.animate().cancel()
        view.text = text
        lastRenderedText = text

        when (mode) {
            LyricsSwitchAnimationMode.NONE -> Unit
            LyricsSwitchAnimationMode.FADE -> {
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.animate()
                    .alpha(1f)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            LyricsSwitchAnimationMode.SLIDE_UP -> {
                view.alpha = 0f
                view.translationY = dp(view, 8).toFloat()
                view.scaleX = 1f
                view.scaleY = 1f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(190L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            LyricsSwitchAnimationMode.SCALE_FADE -> {
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = 0.96f
                view.scaleY = 0.96f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun resetTextAnimationState() {
        textViewProvider()?.let { view ->
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    private fun dp(view: View, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }

    fun getEstimatedPositionMs(): Long {
        if (!currentIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return currentPositionMs
        }

        val elapsedMs = SystemClock.uptimeMillis() - lastPositionUpdateUptimeMs
        return currentPositionMs + elapsedMs.coerceAtLeast(0L)
    }
}
