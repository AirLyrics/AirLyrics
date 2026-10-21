package com.andsi.airlyrics.floating

import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.AbsoluteSizeSpan
import com.andsi.airlyrics.core.model.displayText
import com.andsi.airlyrics.lyrics.LyricsAccess
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.display.PlainLyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.core.text.LyricsDisplayPolicy
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
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
    private val scriptPreferenceProvider: () -> LyricsScriptPreference = { LyricsScriptPreference.default },
    private val lineModeProvider: () -> LyricsLineDisplayMode = { LyricsLineDisplayMode.default },
    private val switchAnimationModeProvider: () -> LyricsSwitchAnimationMode = { LyricsSwitchAnimationMode.default },
    private val wordByWordLyricsEnabledProvider: () -> Boolean = { false },
    private val wordByWordHighlightColorProvider: () -> Int = { Color.rgb(120, 220, 255) },
    private val noTranslationTextProvider: () -> String = { "No translation for this lyric" },
    private val uptimeMillisProvider: () -> Long = { SystemClock.uptimeMillis() }
) {
    private var access: LyricsAccess? = null

    private val expiryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expiryCheck = Runnable { expireIfNeeded() }

    fun setLyricsAccess(value: LyricsAccess?) {
        expiryHandler.removeCallbacks(expiryCheck)
        access = value
        value?.let { expiryHandler.postDelayed(expiryCheck, (it.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    private fun expireIfNeeded(): Boolean {
        if (access?.isExpired() != true) return false
        clear()
        show { textViewProvider()?.context?.getString(com.andsi.airlyrics.R.string.ui_lyrics_expired).orEmpty() }
        return true
    }

    private fun credited(text: CharSequence): CharSequence {
        val credit = access?.attribution ?: return text
        val view = textViewProvider() ?: return text
        return SpannableStringBuilder(text).apply {
            append("\n")
            val start = length
            append(credit.displayText())
            val size = (11 * view.resources.configuration.fontScale).toInt().coerceAtLeast(10)
            setSpan(AbsoluteSizeSpan(size, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(view.currentTextColor, 180)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private var currentPlainLines: List<LrcLine> = emptyList()
    private var backgroundLines: List<WordByWordLine> = emptyList()
    private var currentWordByWordLines: List<WordByWordLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false
    private var lyricsOffsetMs: Long = 0L
    private var currentMessage: (() -> String)? = null
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
        setLyricsAccess(null)
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        backgroundLines = emptyList()
        currentPositionMs = 0L
        lastPositionUpdateUptimeMs = 0L
        currentIsPlaying = false
        lyricsOffsetMs = 0L
        currentMessage = null
        lastRenderedText = null
        resetTextAnimationState()
    }

    fun show(text: String) = show { text }

    fun show(text: () -> String) {
        setLyricsAccess(null)
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        backgroundLines = emptyList()
        currentMessage = text
        setTextImmediately(text())
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
    ): ParsedLyricsAvailability = parseAndShow(plainLrc, translatedLrc, wordByWordLines) { emptyText }

    internal fun parseAndShow(
        plainLrc: String,
        translatedLrc: String? = null,
        wordByWordLines: List<WordByWordLine> = emptyList(),
        emptyText: () -> String
    ): ParsedLyricsAvailability {
        if (expireIfNeeded()) return ParsedLyricsAvailability.EMPTY
        currentPlainLines = LrcParser.parseWithTranslation(plainLrc, translatedLrc)
        currentWordByWordLines = wordByWordLines.filterNot { it.isBackground }
        backgroundLines = wordByWordLines.filter { it.isBackground }
        currentMessage = emptyText
        val availability = if (hasRenderableLyrics()) {
            ParsedLyricsAvailability.AVAILABLE
        } else {
            ParsedLyricsAvailability.EMPTY
        }

        val text = if (availability == ParsedLyricsAvailability.AVAILABLE) {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: emptyText()
        } else {
            emptyText()
        }

        setTextImmediately(text)
        return availability
    }

    fun tick() {
        if (expireIfNeeded()) return
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) return

        val text = renderAtCurrentPosition().takeIf { it.isNotBlankText() } ?: return
        setTextWithOptionalAnimation(text)
    }

    fun isWordByWordActive(): Boolean {
        return wordByWordLyricsEnabledProvider() && currentWordByWordLines.isNotEmpty()
    }

    fun refresh() {
        if (expireIfNeeded()) return
        val text = if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) {
            currentMessage?.invoke() ?: return
        } else {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: currentMessage?.invoke()
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
        val primary = renderPrimaryAtCurrentPosition()
        if (!wordByWordLyricsEnabledProvider() || contentModeProvider() == LyricsContentDisplayMode.TRANSLATION_ONLY) return primary
        val position = getEstimatedPositionMs()
        val activeBackground = backgroundLines.filter { position in it.startMs until it.endMs }
        if (activeBackground.isEmpty()) return primary
        return SpannableStringBuilder(primary).apply {
            activeBackground.forEach { line ->
                if (isNotEmpty()) append("\n")
                append(wordByWordLineSpan(line, line.text, position))
            }
        }
    }

    private fun renderPrimaryAtCurrentPosition(): CharSequence {
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
            scriptPreference = scriptPreferenceProvider(),
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

            val rendered = LyricsDisplayPolicy.render(
                contentMode, original, translation, scriptPreferenceProvider()
            )
            if (rendered.primary.isNotBlank()) {
                // When the preferred script replaces the line with its translation, keep the
                // translation's own timing; otherwise the original tokens stay authoritative.
                val showsTranslationOnly = rendered.secondary == null &&
                    rendered.primary.trim() != original.trim()
                renderedLines += when {
                    wordByWordLine == null -> rendered.primary
                    showsTranslationOnly && wordByWordLine.translatedSegments.isNotEmpty() ->
                        wordByWordLineSpan(
                            wordByWordLine,
                            rendered.primary,
                            positionMs,
                            wordByWordLine.translatedSegments
                        )
                    showsTranslationOnly -> {
                        // No translation shipped: the simplified line is a per-character rewrite
                        // of the original, so the original token timings still apply.
                        val converted = wordByWordLine.segments
                            .map { it.copy(text = ChineseText.toSimplified(it.text)) }
                        if (converted.joinToString("") { it.text } == rendered.primary) {
                            wordByWordLineSpan(wordByWordLine, rendered.primary, positionMs, converted)
                        } else {
                            rendered.primary
                        }
                    }
                    else -> wordByWordLineSpan(wordByWordLine, rendered.primary, positionMs)
                }
            }
            rendered.secondary?.takeIf { it.isNotBlank() }?.let { renderedLines += it }
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

        // Apple/other providers can normalize punctuation or split whitespace differently between
        // the line LRC and the word-timed payload. When both payloads carry the same line start,
        // the timestamp is the stronger identity than a long-text comparison. Keep a narrow
        // timestamp fallback so long lines do not lose karaoke rendering while still avoiding a
        // neighboring line accidentally matching.
        currentWordByWordLines
            .filter { kotlin.math.abs(it.startMs - plainLine.timeMs) <= 120L }
            .minByOrNull { kotlin.math.abs(it.startMs - plainLine.timeMs) }
            ?.takeIf { candidate ->
                isTextCompatible(plainLine.text, wordByWordOriginalText(candidate))
            }
            ?.let { return it }

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
        positionMs: Long,
        displaySegments: List<WordByWordSegment> = wordByWordLine.segments
    ): CharSequence {
        val text = displayText.trim()
        if (text.isBlank()) return ""

        val progress = wordByWordHighlightProgress(wordByWordLine, text, positionMs, displaySegments)
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
        return ChineseText.toSimplified(text).lowercase()
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
        view.text = credited(text)
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
        view.text = credited(text)
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
