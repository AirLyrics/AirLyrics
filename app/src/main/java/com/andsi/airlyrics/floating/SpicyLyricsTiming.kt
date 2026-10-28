package com.andsi.airlyrics.floating

import android.icu.text.BreakIterator
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import java.util.Locale

/** UTF-16 ranges; character splitting always follows ICU extended grapheme boundaries. */
internal data class SpicyTiming(
    val start: Int, val end: Int, val startMs: Long, val endMs: Long,
    val emphasized: Boolean, val motion: Boolean,
    val groupStartMs: Long = startMs, val groupEndMs: Long = endMs,
    val letterIndex: Int = 0, val letterCount: Int = 1
)

internal fun spicyTimings(
    line: WordByWordLine,
    text: String,
    displaySegments: List<WordByWordSegment> = line.segments
): List<SpicyTiming> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val result = mutableListOf<SpicyTiming>()
    var cursor = 0
    for (segment in displaySegments) {
        val word = segment.text.trim()
        if (word.isEmpty()) continue
        val start = text.indexOf(word, cursor)
        if (start < 0 || segment.endMs <= segment.startMs) return emptyList()
        val end = start + word.length
        if (!iterator.isBoundary(start) || !iterator.isBoundary(end)) return emptyList()
        cursor = end
        // Keep joining scripts and bidirectional runs intact, including their shaping context.
        val motion = word.codePoints().allMatch {
            val script = Character.UnicodeScript.of(it)
            script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.HAN ||
                script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA ||
                script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
        }
        val boundaries = mutableListOf(start)
        var next = iterator.following(start)
        while (next != BreakIterator.DONE && next <= end) {
            boundaries += next
            next = iterator.next()
        }
        val duration = segment.endMs - segment.startMs
        val emphasis = motion && duration >= 1000 && boundaries.size > 2
        if (emphasis) {
            val count = boundaries.lastIndex
            // Spicy normal mode completes emphasized text 250ms before the sung word ends.
            val emphasisDuration = duration - 250L
            for (i in 0 until count) {
                result += SpicyTiming(boundaries[i], boundaries[i + 1],
                    segment.startMs + emphasisDuration * i / count,
                    segment.startMs + emphasisDuration * (i + 1) / count, emphasized = true, motion = true,
                    groupStartMs = segment.startMs, groupEndMs = segment.endMs - 250L,
                    letterIndex = i, letterCount = count)
            }
        } else {
            result += SpicyTiming(start, end, segment.startMs, segment.endMs, emphasized = false, motion = motion)
        }
    }
    return result
}

internal fun spicyProgress(positionMs: Long, startMs: Long, endMs: Long): Float =
    ((positionMs - startMs).toDouble() / (endMs - startMs).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)

/** Small, frame-rate independent damped spring. Seeking snaps instead of replaying old motion. */
internal class SpicySpring(private val frequency: Double, private val damping: Double, var value: Float) {
    private var velocity = 0.0
    fun reset(target: Float) { value = target; velocity = 0.0 }
    fun step(target: Float, seconds: Double): Float {
        val omega = frequency * 2 * Math.PI
        val damped = omega * kotlin.math.sqrt(1 - damping * damping)
        val displacement = value - target
        val decay = kotlin.math.exp(-damping * omega * seconds)
        val sin = kotlin.math.sin(damped * seconds)
        val cos = kotlin.math.cos(damped * seconds)
        val b = (velocity + damping * omega * displacement) / damped
        value = (target + decay * (displacement * cos + b * sin)).toFloat()
        velocity = decay * (velocity * cos - (damping * omega * b + damped * displacement) * sin)
        return value
    }
}
