package com.andsi.airlyrics.floating

import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.display.PlainLyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal enum class ParsedLyricsAvailability {
    AVAILABLE,
    EMPTY
}

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
    private var currentMessage: String? = null
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
        currentMessage = null
        lastRenderedText = null
        resetTextAnimationState()
    }

    fun show(text: String) {
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        currentMessage = text
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

    internal fun parseAndShow(
        plainLrc: String,
        translatedLrc: String? = null,
        wordByWordLines: List<WordByWordLine> = emptyList(),
        emptyText: String
    ): ParsedLyricsAvailability {
        currentPlainLines = LrcParser.parseWithTranslation(plainLrc, translatedLrc)
        currentWordByWordLines = wordByWordLines
        currentMessage = emptyText
        val availability = if (hasRenderableLyrics()) {
            ParsedLyricsAvailability.AVAILABLE
        } else {
            ParsedLyricsAvailability.EMPTY
        }

        val text = if (availability == ParsedLyricsAvailability.AVAILABLE) {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: emptyText
        } else {
            emptyText
        }

        setTextImmediately(text)
        return availability
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
        val text = if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) {
            currentMessage ?: return
        } else {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: currentMessage
                ?: return
        }
        setTextImmediately(text)
    }

    private fun hasRenderableLyrics(): Boolean {
        val hasPlainLyrics = currentPlainLines.any { line ->
            !line.isMetadata && (line.text.isNotBlank() || line.hasTranslation())
        }
        val hasWordByWordLyrics = currentWordByWordLines.any { line ->
            line.text.isNotBlank() || line.segments.any { it.text.isNotBlank() }
        }
        return hasPlainLyrics || hasWordByWordLyrics
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

        val progress = wordByWordHighlightProgress(wordByWordLine, text, positionMs)
        val span = SpannableString(text)
        if (progress.completedEnd > 0 || progress.hasActiveCharacter) {
            val highlightColor = wordByWordHighlightColorProvider()
            if (progress.completedEnd > 0) {
                span.setSpan(
                    ForegroundColorSpan(highlightColor),
                    0,
                    progress.completedEnd.coerceIn(0, text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (progress.hasActiveCharacter) {
                val baseColor = textViewProvider()?.currentTextColor ?: Color.WHITE
                val transitioningColor = ColorUtils.blendARGB(
                    baseColor,
                    highlightColor,
                    progress.activeFraction
                )
                span.setSpan(
                    ForegroundColorSpan(transitioningColor),
                    progress.activeStart.coerceIn(0, text.length),
                    progress.activeEnd.coerceIn(0, text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
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
        resetTextAnimationState(view)
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
            LyricsSwitchAnimationMode.FADE -> startTextSwitchAnimation(
                text = text,
                textKey = textKey,
                initialTranslationYDp = 0f,
                initialScale = AirUiTokens.Motion.RestScale,
                durationMs = AirUiTokens.Layout.LyricsFadeMs
            )

            LyricsSwitchAnimationMode.SLIDE_UP -> startTextSwitchAnimation(
                text = text,
                textKey = textKey,
                initialTranslationYDp = AirUiTokens.Layout.LyricsSlideDistanceDp.toFloat(),
                initialScale = AirUiTokens.Motion.RestScale,
                durationMs = AirUiTokens.Layout.LyricsSlideMs
            )

            LyricsSwitchAnimationMode.SCALE_FADE -> startTextSwitchAnimation(
                text = text,
                textKey = textKey,
                initialTranslationYDp = 0f,
                initialScale = AirUiTokens.Layout.LyricsScaleStart,
                durationMs = AirUiTokens.Layout.LyricsScaleFadeMs
            )
        }
    }

    private fun startTextSwitchAnimation(
        text: CharSequence,
        textKey: String,
        initialTranslationYDp: Float,
        initialScale: Float,
        durationMs: Long
    ) {
        val view = prepareTextSwitchAnimation(text, textKey) ?: return
        setTextAnimationState(
            view = view,
            alpha = 0f,
            translationY = initialTranslationYDp * view.resources.displayMetrics.density,
            scaleX = initialScale,
            scaleY = initialScale
        )
        animateTextToRest(view, durationMs)
    }

    private fun prepareTextSwitchAnimation(text: CharSequence, textKey: String): TextView? {
        val view = textViewProvider() ?: return null
        cancelTextAnimation(view)
        view.text = text
        lastRenderedText = textKey
        return view
    }

    private fun resetTextAnimationState() {
        textViewProvider()?.let(::resetTextAnimationState)
    }

    private fun resetTextAnimationState(view: TextView) {
        cancelTextAnimation(view)
        setTextAnimationState(
            view = view,
            alpha = AirUiTokens.Motion.RestAlpha,
            translationY = 0f,
            scaleX = AirUiTokens.Motion.RestScale,
            scaleY = AirUiTokens.Motion.RestScale
        )
    }

    private fun cancelTextAnimation(view: TextView) {
        view.animate().cancel()
        (view as? LyricsTextAnimationTarget)?.cancelLyricsTextAnimation()
    }

    private fun setTextAnimationState(
        view: TextView,
        alpha: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        val textTarget = view as? LyricsTextAnimationTarget
        if (textTarget != null) {
            // The TextView is also the WindowManager root. Keep its background and hit area fixed.
            view.alpha = AirUiTokens.Motion.RestAlpha
            view.translationY = 0f
            view.scaleX = AirUiTokens.Motion.RestScale
            view.scaleY = AirUiTokens.Motion.RestScale
            textTarget.setLyricsTextAnimationState(alpha, translationY, scaleX, scaleY)
        } else {
            view.alpha = alpha
            view.translationY = translationY
            view.scaleX = scaleX
            view.scaleY = scaleY
        }
    }

    private fun animateTextToRest(view: TextView, durationMs: Long) {
        val interpolator = DecelerateInterpolator()
        val textTarget = view as? LyricsTextAnimationTarget
        if (textTarget != null) {
            textTarget.animateLyricsTextToRest(durationMs, interpolator)
        } else {
            view.animate()
                .alpha(AirUiTokens.Motion.RestAlpha)
                .translationY(0f)
                .scaleX(AirUiTokens.Motion.RestScale)
                .scaleY(AirUiTokens.Motion.RestScale)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .start()
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
