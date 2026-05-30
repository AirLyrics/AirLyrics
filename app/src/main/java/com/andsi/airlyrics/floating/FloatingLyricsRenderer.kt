package com.andsi.airlyrics.floating

import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.andsi.airlyrics.lyrics.KaraokeLine
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
    private val switchAnimationModeProvider: () -> LyricsSwitchAnimationMode = { LyricsSwitchAnimationMode.default },
    private val karaokeEnabledProvider: () -> Boolean = { false },
    private val karaokeHighlightColorProvider: () -> Int = { Color.rgb(120, 220, 255) },
    private val noTranslationTextProvider: () -> String = { "当前歌词没有翻译" }
) {
    private var currentLyrics: List<LrcLine> = emptyList()
    private var currentKaraokeLines: List<KaraokeLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false
    private var lyricsOffsetMs: Long = 0L
    private var lastRenderedText: String? = null

    fun updatePlayback(positionMs: Long, isPlaying: Boolean) {
        currentPositionMs = positionMs
        currentIsPlaying = isPlaying
        lastPositionUpdateUptimeMs = SystemClock.uptimeMillis()
    }

    fun clear() {
        currentLyrics = emptyList()
        currentKaraokeLines = emptyList()
        currentPositionMs = 0L
        lastPositionUpdateUptimeMs = 0L
        currentIsPlaying = false
        lyricsOffsetMs = 0L
        lastRenderedText = null
        resetTextAnimationState()
    }

    fun show(text: String) {
        currentLyrics = emptyList()
        currentKaraokeLines = emptyList()
        setTextImmediately(text)
    }

    fun setLyricsOffset(offsetMs: Long) {
        lyricsOffsetMs = offsetMs
        refresh()
    }

    fun parseAndShow(
        lyrics: String,
        translatedLyrics: String? = null,
        karaokeLines: List<KaraokeLine> = emptyList(),
        emptyText: String
    ) {
        currentLyrics = LrcParser.parseWithTranslation(lyrics, translatedLyrics)
        currentKaraokeLines = karaokeLines

        val text = if (currentLyrics.isNotEmpty() || currentKaraokeLines.isNotEmpty()) {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: emptyText
        } else {
            emptyText
        }

        setTextImmediately(text)
    }

    fun tick() {
        if (currentLyrics.isEmpty() && currentKaraokeLines.isEmpty()) return

        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() } ?: return
        setTextWithOptionalAnimation(text)
    }

    fun isKaraokeActive(): Boolean {
        return karaokeEnabledProvider() && currentKaraokeLines.isNotEmpty()
    }

    fun refresh() {
        if (currentLyrics.isEmpty() && currentKaraokeLines.isEmpty()) return
        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() }
            ?: renderTextAtIndex(0).takeIf { it.isNotBlankText() }
            ?: return
        setTextImmediately(text)
    }

    private fun renderAtCurrentPosition(): CharSequence {
        val positionMs = getEstimatedPositionMs()
        val currentIndex = LrcParser.findCurrentIndex(currentLyrics, positionMs)

        if (currentIndex != null) {
            if (karaokeEnabledProvider() && currentKaraokeLines.isNotEmpty()) {
                renderTextAtIndexWithKaraoke(currentIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
            return renderTextAtIndex(currentIndex)
        }

        // Safety fallback for unusual provider payloads. Normal providers should always
        // return LRC lines; karaoke data is only an enhancement layer and must never make
        // the floating window blank.
        if (karaokeEnabledProvider() && currentKaraokeLines.isNotEmpty()) {
            val karaokeIndex = findCurrentKaraokeIndex(positionMs)
            if (karaokeIndex != null) {
                renderKaraokeOnlyAtIndex(karaokeIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
        }

        return ""
    }

    private fun renderTextAtIndex(index: Int): CharSequence {
        return LyricsDisplayFormatter.format(
            lines = currentLyrics,
            currentIndex = index,
            contentMode = contentModeProvider(),
            lineMode = lineModeProvider(),
            noTranslationText = noTranslationTextProvider()
        )
    }

    /**
     * Renders exactly the same content modes as [LyricsDisplayFormatter], but replaces only
     * the current original line with wrap-safe karaoke highlighting when a matching local word-by-word line exists.
     * This keeps “original only / translation only / original + translation” independent from
     * karaoke and prevents word-by-word text from leaking translations into original-only mode.
     */
    private fun renderTextAtIndexWithKaraoke(currentIndex: Int, positionMs: Long): CharSequence {
        if (currentLyrics.isEmpty() || currentIndex !in currentLyrics.indices) return ""

        val indexes = visibleLrcIndexes(currentIndex)
        if (indexes.isEmpty()) return ""

        val renderedLines = mutableListOf<CharSequence>()
        val contentMode = contentModeProvider()

        indexes.forEach { index ->
            val line = currentLyrics[index]
            val original = line.text.trim()
            val translation = line.translation.orEmpty().trim()
            val isCurrent = index == currentIndex
            val karaokeLine = if (isCurrent) findKaraokeLineForLrc(line, positionMs) else null

            when (contentMode) {
                LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> {
                    val block = SpannableStringBuilder()
                    if (original.isNotBlank()) {
                        block.append(
                            if (karaokeLine != null) karaokeLineSpan(karaokeLine, original, positionMs)
                            else original
                        )
                    }
                    if (translation.isNotBlank()) {
                        if (block.isNotEmpty()) block.append('\n')
                        block.append(translation)
                    }
                    if (block.isNotBlankText()) renderedLines += block
                }

                LyricsContentDisplayMode.ORIGINAL_ONLY -> {
                    if (original.isNotBlank()) {
                        renderedLines += if (karaokeLine != null) {
                            karaokeLineSpan(karaokeLine, original, positionMs)
                        } else {
                            original
                        }
                    }
                }

                LyricsContentDisplayMode.TRANSLATION_ONLY -> {
                    if (translation.isNotBlank()) renderedLines += translation
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

    private fun visibleLrcIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentLyrics.indices }
    }

    private fun findKaraokeLineForLrc(line: LrcLine, positionMs: Long): KaraokeLine? {
        if (currentKaraokeLines.isEmpty()) return null

        fun List<KaraokeLine>.bestCompatible(maxDistanceMs: Long): KaraokeLine? {
            return sortedBy { kotlin.math.abs(it.startMs - line.timeMs) }
                .firstOrNull { candidate ->
                    kotlin.math.abs(candidate.startMs - line.timeMs) <= maxDistanceMs &&
                        isTextCompatible(line.text, karaokeOriginalText(candidate))
                }
        }

        val aroundPosition = currentKaraokeLines
            .filter { positionMs in (it.startMs - 350L)..(it.endMs + 700L) }
            .bestCompatible(maxDistanceMs = 2_500L)
        if (aroundPosition != null) return aroundPosition

        return currentKaraokeLines.bestCompatible(maxDistanceMs = 1_500L)
    }

    private fun renderKaraokeOnlyAtIndex(index: Int, positionMs: Long): CharSequence {
        if (contentModeProvider() == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            return noTranslationTextProvider()
        }

        val renderedLines = visibleKaraokeIndexes(index).mapNotNull { visibleIndex ->
            val line = currentKaraokeLines.getOrNull(visibleIndex) ?: return@mapNotNull null
            val original = karaokeOriginalText(line)
            if (original.isBlank()) {
                null
            } else if (visibleIndex == index) {
                karaokeLineSpan(line, original, positionMs)
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

    private fun visibleKaraokeIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentKaraokeLines.indices }
    }

    private fun karaokeLineSpan(line: KaraokeLine, displayText: String, positionMs: Long): CharSequence {
        val text = displayText.trim()
        if (text.isBlank()) return ""

        val highlightEnd = karaokeHighlightEnd(line, text, positionMs)
            .coerceIn(0, text.length)
        val span = SpannableString(text)
        if (highlightEnd > 0) {
            span.setSpan(
                ForegroundColorSpan(karaokeHighlightColorProvider()),
                0,
                highlightEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    private fun karaokeOriginalText(line: KaraokeLine): String {
        return line.text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    private fun karaokeHighlightEnd(line: KaraokeLine, displayText: String, positionMs: Long): Int {
        if (displayText.isBlank()) return 0
        if (positionMs <= line.startMs) return 0
        if (positionMs >= line.endMs) return displayText.length

        var searchStart = 0
        var completedCharEnd = 0

        for (token in line.tokens) {
            val tokenText = token.text.trim()
            if (tokenText.isBlank()) continue

            val tokenStart = displayText.indexOf(tokenText, searchStart).takeIf { it >= 0 }
                ?: displayText.indexOf(tokenText).takeIf { it >= 0 }
                ?: continue
            val tokenEnd = tokenStart + tokenText.length
            searchStart = tokenEnd

            when {
                positionMs >= token.endMs -> completedCharEnd = tokenEnd
                positionMs in token.startMs until token.endMs -> {
                    val duration = (token.endMs - token.startMs).coerceAtLeast(1L)
                    val tokenProgress = ((positionMs - token.startMs).toFloat() / duration.toFloat())
                        .coerceIn(0f, 1f)
                    val eased = smoothStep(tokenProgress)
                    val currentEnd = tokenStart + kotlin.math.ceil(tokenText.length * eased).toInt()
                    return currentEnd.coerceAtLeast(completedCharEnd).coerceIn(0, displayText.length)
                }
                positionMs < token.startMs -> return completedCharEnd.coerceIn(0, displayText.length)
            }
        }

        val duration = (line.endMs - line.startMs).coerceAtLeast(1L)
        val lineProgress = ((positionMs - line.startMs).toFloat() / duration.toFloat())
            .coerceIn(0f, 1f)
        return kotlin.math.ceil(displayText.length * smoothStep(lineProgress)).toInt()
            .coerceIn(0, displayText.length)
    }

    private fun normalizeKaraokeMatchText(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
            .trim()
    }

    private fun isTextCompatible(lrcText: String, karaokeText: String): Boolean {
        val lrc = normalizeKaraokeMatchText(lrcText)
        val karaoke = normalizeKaraokeMatchText(karaokeText)
        if (lrc.isBlank() || karaoke.isBlank()) return false
        if (lrc == karaoke) return true
        if (lrc.length >= 2 && karaoke.length >= 2 && (lrc.contains(karaoke) || karaoke.contains(lrc))) {
            return true
        }

        val shorter = if (lrc.length <= karaoke.length) lrc else karaoke
        val longer = if (lrc.length <= karaoke.length) karaoke else lrc
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

    private fun findCurrentKaraokeIndex(positionMs: Long): Int? {
        if (currentKaraokeLines.isEmpty()) return null

        var left = 0
        var right = currentKaraokeLines.lastIndex
        var result: Int? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val line = currentKaraokeLines[mid]

            if (line.startMs <= positionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }

    private fun nearestLrcLine(timeMs: Long): LrcLine? {
        if (currentLyrics.isEmpty()) return null
        return currentLyrics.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
            ?.takeIf { kotlin.math.abs(it.timeMs - timeMs) <= 800L }
    }

    private fun setTextImmediately(text: CharSequence) {
        val view = textViewProvider() ?: return
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.text = text
        lastRenderedText = text.toString()
    }

    private fun setTextWithOptionalAnimation(text: CharSequence) {
        val textKey = text.toString()
        val isKaraokeTick = karaokeEnabledProvider() && currentKaraokeLines.isNotEmpty()
        if (!isKaraokeTick && textKey == lastRenderedText) return

        val view = textViewProvider() ?: return
        val mode = switchAnimationModeProvider()
        if (isKaraokeTick || mode == LyricsSwitchAnimationMode.NONE || lastRenderedText == null) {
            setTextImmediately(text)
            return
        }

        view.animate().cancel()
        view.text = text
        lastRenderedText = textKey

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

    private fun CharSequence.isNotBlankText(): Boolean = toString().isNotBlank()

    private fun dp(view: View, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }

    fun getEstimatedPositionMs(): Long {
        if (!currentIsPlaying || lastPositionUpdateUptimeMs == 0L) {
            return (currentPositionMs + lyricsOffsetMs).coerceAtLeast(0L)
        }

        val elapsedMs = SystemClock.uptimeMillis() - lastPositionUpdateUptimeMs
        return (currentPositionMs + elapsedMs.coerceAtLeast(0L) + lyricsOffsetMs).coerceAtLeast(0L)
    }
}
