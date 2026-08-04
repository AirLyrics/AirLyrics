package com.andsi.airlyrics.floating

import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.display.PlainLyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.design.tokens.AirUiTokens

/**
 * Maintains parsed lyric lines and renders the line matching the current playback position.
 */
class FloatingLyricsRenderer(
    private val textViewProvider: () -> TextView?,
    private val contentModeProvider: () -> LyricsContentDisplayMode = { LyricsContentDisplayMode.default },
    private val lineModeProvider: () -> LyricsLineDisplayMode = { LyricsLineDisplayMode.default },
    private val switchAnimationModeProvider: () -> LyricsSwitchAnimationMode = { LyricsSwitchAnimationMode.default },
    private val wordByWordLyricsEnabledProvider: () -> Boolean = { false },
    private val wordByWordHighlightColorProvider: () -> Int = { Color.rgb(120, 220, 255) },
    private val noTranslationTextProvider: () -> String = { "No translation for this lyric" },
    private val uptimeMillisProvider: () -> Long = { SystemClock.uptimeMillis() }
) {
    private var currentPlainLines: List<LrcLine> = emptyList()
    private var currentWordByWordLines: List<WordByWordLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false
    private var lyricsOffsetMs: Long = 0L
    private var lastRenderedText: String? = null

    fun updatePlayback(positionMs: Long, isPlaying: Boolean) {
        val nowUptimeMs = uptimeMillisProvider()
        val incomingPositionMs = positionMs.coerceAtLeast(0L)
        val estimatedBeforeUpdateMs = getEstimatedPlaybackPositionMs(nowUptimeMs)
        currentPositionMs = if (isStalePlayingBacktrack(incomingPositionMs, isPlaying, estimatedBeforeUpdateMs)) {
            estimatedBeforeUpdateMs
        } else {
            incomingPositionMs
        }
        currentIsPlaying = isPlaying
        lastPositionUpdateUptimeMs = nowUptimeMs
    }

    fun clear() {
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        currentPositionMs = 0L
        lastPositionUpdateUptimeMs = 0L
        currentIsPlaying = false
        lyricsOffsetMs = 0L
        lastRenderedText = null
        resetTextAnimationState()
    }

    fun show(text: String) {
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        setTextImmediately(text)
    }

    /**
     * Updates the timing offset. Rendering is caller-controlled so parse/clear flows do
     * not repaint stale lyrics before replacing the renderer state.
     */
    fun setLyricsOffset(offsetMs: Long): Boolean {
        if (lyricsOffsetMs == offsetMs) return false
        lyricsOffsetMs = offsetMs
        return true
    }

    fun parseAndShow(
        plainLrc: String,
        translatedLrc: String? = null,
        wordByWordLines: List<WordByWordLine> = emptyList(),
        emptyText: String
    ) {
        currentPlainLines = LrcParser.parseWithTranslation(plainLrc, translatedLrc)
        currentWordByWordLines = wordByWordLines

        val text = if (currentPlainLines.isNotEmpty() || currentWordByWordLines.isNotEmpty()) {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: emptyText
        } else {
            emptyText
        }

        setTextImmediately(text)
    }

    fun tick() {
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) return

        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() } ?: return
        setTextWithOptionalAnimation(text)
    }

    fun isWordByWordActive(): Boolean {
        return wordByWordLyricsEnabledProvider() && currentWordByWordLines.isNotEmpty()
    }

    fun refresh() {
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) return
        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() }
            ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
            ?: return
        setTextImmediately(text)
    }

    private fun renderAtCurrentPosition(): CharSequence {
        val positionMs = getEstimatedPositionMs()
        val currentIndex = LrcParser.findCurrentIndex(currentPlainLines, positionMs)

        if (currentIndex != null) {
            if (wordByWordLyricsEnabledProvider() && currentWordByWordLines.isNotEmpty()) {
                renderTextAtIndexWithWordByWord(currentIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
            return renderPlainTextAtIndex(currentIndex)
        }

        // Safety fallback for unusual payloads. Word-by-word lyrics are independent segment
        // timing data, so they can still render when the accompanying plain LRC has no usable line.
        if (wordByWordLyricsEnabledProvider() && currentWordByWordLines.isNotEmpty()) {
            val wordByWordIndex = findCurrentWordByWordIndex(positionMs)
            if (wordByWordIndex != null) {
                renderWordByWordOnlyAtIndex(wordByWordIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
        }

        return ""
    }

    private fun renderPlainTextAtIndex(index: Int): CharSequence {
        return PlainLyricsDisplayFormatter.format(
            plainLines = currentPlainLines,
            currentIndex = index,
            contentMode = contentModeProvider(),
            lineMode = lineModeProvider(),
            noTranslationText = noTranslationTextProvider()
        )
    }

    /**
     * Renders exactly the same content modes as [PlainLyricsDisplayFormatter], but replaces only
     * the current original line with wrap-safe word-by-word highlighting when a matching local word-by-word line exists.
     * This keeps “original only / translation only / original + translation” independent of
     * word-by-word highlighting and prevents timed text from leaking translations into original-only mode.
     */
    private fun renderTextAtIndexWithWordByWord(currentIndex: Int, positionMs: Long): CharSequence {
        if (currentPlainLines.isEmpty() || currentIndex !in currentPlainLines.indices) return ""

        val indexes = visiblePlainLineIndexes(currentIndex)
        if (indexes.isEmpty()) return ""

        val renderedLines = mutableListOf<CharSequence>()
        val contentMode = contentModeProvider()

        indexes.forEach { index ->
            val line = currentPlainLines[index]
            val original = line.text.trim()
            val translation = line.translation.orEmpty().trim()
            if (line.isMetadata) {
                if (original.isNotBlank()) renderedLines += original
                return@forEach
            }

            val isCurrent = index == currentIndex
            val wordByWordLine = if (isCurrent) findWordByWordLineForPlainLine(line, positionMs) else null

            when (contentMode) {
                LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> {
                    val block = SpannableStringBuilder()
                    if (original.isNotBlank()) {
                        block.append(
                            if (wordByWordLine != null) wordByWordLineSpan(wordByWordLine, original, positionMs)
                            else original
                        )
                    }
                    if (line.hasTranslation()) {
                        if (block.isNotEmpty()) block.append('\n')
                        block.append(translation)
                    }
                    if (block.isNotBlankText()) renderedLines += block
                }

                LyricsContentDisplayMode.ORIGINAL_ONLY -> {
                    if (original.isNotBlank()) {
                        renderedLines += if (wordByWordLine != null) {
                            wordByWordLineSpan(wordByWordLine, original, positionMs)
                        } else {
                            original
                        }
                    }
                }

                LyricsContentDisplayMode.TRANSLATION_ONLY -> {
                    if (line.hasTranslation()) renderedLines += translation
                }
            }
        }

        if (renderedLines.isEmpty()) {
            return if (contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY) {
                noTranslationTextProvider()
            } else {
                ""
            }
        }

        return SpannableStringBuilder().apply {
            renderedLines.forEachIndexed { renderedIndex, renderedLine ->
                if (renderedIndex > 0) append('\n')
                append(renderedLine)
            }
        }
    }

    private fun visiblePlainLineIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentPlainLines.indices }
    }

    private fun findWordByWordLineForPlainLine(plainLine: LrcLine, positionMs: Long): WordByWordLine? {
        if (plainLine.isMetadata) return null
        if (currentWordByWordLines.isEmpty()) return null

        fun List<WordByWordLine>.bestCompatible(maxDistanceMs: Long): WordByWordLine? {
            return sortedBy { kotlin.math.abs(it.startMs - plainLine.timeMs) }
                .firstOrNull { candidate ->
                    kotlin.math.abs(candidate.startMs - plainLine.timeMs) <= maxDistanceMs &&
                        isTextCompatible(plainLine.text, wordByWordOriginalText(candidate))
                }
        }

        val aroundPosition = currentWordByWordLines
            .filter { positionMs in (it.startMs - 350L)..(it.endMs + 700L) }
            .bestCompatible(maxDistanceMs = 2_500L)
        if (aroundPosition != null) return aroundPosition

        return currentWordByWordLines.bestCompatible(maxDistanceMs = 1_500L)
    }

    private fun renderWordByWordOnlyAtIndex(index: Int, positionMs: Long): CharSequence {
        if (contentModeProvider() == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            return noTranslationTextProvider()
        }

        val renderedLines = visibleWordByWordIndexes(index).mapNotNull { visibleIndex ->
            val wordByWordLine = currentWordByWordLines.getOrNull(visibleIndex) ?: return@mapNotNull null
            val original = wordByWordOriginalText(wordByWordLine)
            if (original.isBlank()) {
                null
            } else if (visibleIndex == index) {
                wordByWordLineSpan(wordByWordLine, original, positionMs)
            } else {
                original
            }
        }

        if (renderedLines.isEmpty()) return ""

        return SpannableStringBuilder().apply {
            renderedLines.forEachIndexed { renderedIndex, renderedLine ->
                if (renderedIndex > 0) append('\n')
                append(renderedLine)
            }
        }
    }

    private fun visibleWordByWordIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentWordByWordLines.indices }
    }

    private fun wordByWordLineSpan(
        wordByWordLine: WordByWordLine,
        displayText: String,
        positionMs: Long
    ): CharSequence {
        val text = displayText.trim()
        if (text.isBlank()) return ""

        val highlightEnd = wordByWordHighlightEnd(wordByWordLine, text, positionMs)
            .coerceIn(0, text.length)
        val span = SpannableString(text)
        if (highlightEnd > 0) {
            span.setSpan(
                ForegroundColorSpan(wordByWordHighlightColorProvider()),
                0,
                highlightEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    private fun wordByWordOriginalText(wordByWordLine: WordByWordLine): String {
        return wordByWordLine.text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    private fun wordByWordHighlightEnd(
        wordByWordLine: WordByWordLine,
        displayText: String,
        positionMs: Long
    ): Int {
        if (displayText.isBlank()) return 0
        if (positionMs <= wordByWordLine.startMs) return 0
        if (positionMs >= wordByWordLine.endMs) return displayText.length

        var searchStart = 0
        var completedCharEnd = 0

        for (segment in wordByWordLine.segments) {
            val segmentText = segment.text.trim()
            if (segmentText.isBlank()) continue

            val segmentStart = displayText.indexOf(segmentText, searchStart).takeIf { it >= 0 }
                ?: displayText.indexOf(segmentText).takeIf { it >= 0 }
                ?: continue
            val segmentEnd = segmentStart + segmentText.length
            searchStart = segmentEnd

            when {
                positionMs >= segment.endMs -> completedCharEnd = segmentEnd
                positionMs in segment.startMs until segment.endMs -> {
                    val duration = (segment.endMs - segment.startMs).coerceAtLeast(1L)
                    val segmentProgress = ((positionMs - segment.startMs).toFloat() / duration.toFloat())
                        .coerceIn(0f, 1f)
                    val eased = smoothStep(segmentProgress)
                    val currentEnd = segmentStart + kotlin.math.ceil(segmentText.length * eased).toInt()
                    return currentEnd.coerceAtLeast(completedCharEnd).coerceIn(0, displayText.length)
                }
                else -> return completedCharEnd.coerceIn(0, displayText.length)
            }
        }

        val duration = (wordByWordLine.endMs - wordByWordLine.startMs).coerceAtLeast(1L)
        val lineProgress = ((positionMs - wordByWordLine.startMs).toFloat() / duration.toFloat())
            .coerceIn(0f, 1f)
        return kotlin.math.ceil(displayText.length * smoothStep(lineProgress)).toInt()
            .coerceIn(0, displayText.length)
    }

    private fun normalizeWordByWordMatchText(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
            .trim()
    }

    private fun isTextCompatible(lrcText: String, wordByWordText: String): Boolean {
        val lrc = normalizeWordByWordMatchText(lrcText)
        val wordByWordLyrics = normalizeWordByWordMatchText(wordByWordText)
        if (lrc.isBlank() || wordByWordLyrics.isBlank()) return false
        if (lrc == wordByWordLyrics) return true
        if (lrc.length >= 2 && wordByWordLyrics.length >= 2 && (lrc.contains(wordByWordLyrics) || wordByWordLyrics.contains(lrc))) {
            return true
        }

        val shorter = if (lrc.length <= wordByWordLyrics.length) lrc else wordByWordLyrics
        val longer = if (lrc.length <= wordByWordLyrics.length) wordByWordLyrics else lrc
        val minCommonLength = when {
            shorter.length >= 12 -> 8
            shorter.length >= 6 -> 4
            else -> return false
        }

        return (0..(shorter.length - minCommonLength)).any { start ->
            longer.contains(shorter.substring(start, start + minCommonLength))
        }
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun findCurrentWordByWordIndex(positionMs: Long): Int? {
        if (currentWordByWordLines.isEmpty()) return null

        var left = 0
        var right = currentWordByWordLines.lastIndex
        var result: Int? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val line = currentWordByWordLines[mid]

            if (line.startMs <= positionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }

    private fun setTextImmediately(text: CharSequence) {
        val view = textViewProvider() ?: return
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = AirUiTokens.Motion.RestScale
        view.scaleY = AirUiTokens.Motion.RestScale
        view.text = text
        lastRenderedText = text.toString()
    }

    private fun setTextWithOptionalAnimation(text: CharSequence) {
        val textKey = text.toString()
        val isWordByWordTick = wordByWordLyricsEnabledProvider() && currentWordByWordLines.isNotEmpty()
        if (!isWordByWordTick && textKey == lastRenderedText) return

        val mode = switchAnimationModeProvider()
        if (isWordByWordTick || lastRenderedText == null) {
            setTextImmediately(text)
            return
        }

        when (mode) {
            LyricsSwitchAnimationMode.NONE -> setTextImmediately(text)
            LyricsSwitchAnimationMode.FADE -> {
                val view = prepareTextSwitchAnimation(text, textKey) ?: return
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = AirUiTokens.Motion.RestScale
                view.scaleY = AirUiTokens.Motion.RestScale
                view.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .setDuration(AirUiTokens.Layout.LyricsFadeMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            LyricsSwitchAnimationMode.SLIDE_UP -> {
                val view = prepareTextSwitchAnimation(text, textKey) ?: return
                view.alpha = 0f
                view.translationY = AirUiTokens.Layout.LyricsSlideDistanceDp * view.resources.displayMetrics.density
                view.scaleX = AirUiTokens.Motion.RestScale
                view.scaleY = AirUiTokens.Motion.RestScale
                view.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .translationY(0f)
                    .setDuration(AirUiTokens.Layout.LyricsSlideMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            LyricsSwitchAnimationMode.SCALE_FADE -> {
                val view = prepareTextSwitchAnimation(text, textKey) ?: return
                view.alpha = 0f
                view.translationY = 0f
                view.scaleX = AirUiTokens.Layout.LyricsScaleStart
                view.scaleY = AirUiTokens.Layout.LyricsScaleStart
                view.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .scaleX(AirUiTokens.Motion.RestScale)
                    .scaleY(AirUiTokens.Motion.RestScale)
                    .setDuration(AirUiTokens.Layout.LyricsScaleFadeMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun prepareTextSwitchAnimation(text: CharSequence, textKey: String): TextView? {
        val view = textViewProvider() ?: return null
        view.animate().cancel()
        view.text = text
        lastRenderedText = textKey
        return view
    }

    private fun resetTextAnimationState() {
        textViewProvider()?.let { view ->
            view.animate().cancel()
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = AirUiTokens.Motion.RestScale
            view.scaleY = AirUiTokens.Motion.RestScale
        }
    }

    private fun CharSequence.isNotBlankText(): Boolean = toString().isNotBlank()

    fun getEstimatedPositionMs(): Long {
        return (getEstimatedPlaybackPositionMs() + lyricsOffsetMs).coerceAtLeast(0L)
    }

    private fun getEstimatedPlaybackPositionMs(nowUptimeMs: Long = uptimeMillisProvider()): Long {
        if (!currentIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return currentPositionMs.coerceAtLeast(0L)
        }

        val elapsedMs = nowUptimeMs - lastPositionUpdateUptimeMs
        return (currentPositionMs + elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    private fun isStalePlayingBacktrack(
        positionMs: Long,
        incomingIsPlaying: Boolean,
        estimatedBeforeUpdateMs: Long
    ): Boolean {
        if (!currentIsPlaying || !incomingIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return false
        }

        val backtrackMs = estimatedBeforeUpdateMs - positionMs
        return backtrackMs in 1L..STALE_PLAYING_BACKTRACK_MS
    }

    companion object {
        private const val STALE_PLAYING_BACKTRACK_MS = 1_500L
    }
}
