package com.andsi.airlyrics.floating

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Normal-mode keyframes and letter proximity from Spicy Lyrics LyricsAnimator.ts,
 * reference ebcb1f0871ace63b81d634b4fcf0d7ebd1428f2d. Keep this distinct from its
 * experimental refreshed-simple renderer. Android keeps the user's own text shadow.
 */
internal object SpicyLyricsCurves {
    private val wordScale = NaturalSpline(floatArrayOf(0f, .7f, 1f), floatArrayOf(.95f, 1.0505f, 1f))
    private val letterScale = NaturalSpline(floatArrayOf(0f, .7f, 1f), floatArrayOf(.95f, 1.175f, 1f))
    private val wordLift = NaturalSpline(floatArrayOf(0f, .9f, 1f), floatArrayOf(.01f, -1f / 60, 0f))
    private val letterLift = NaturalSpline(floatArrayOf(0f, .9f, 1f), floatArrayOf(.01f, -1f / 56, 0f))
    private val glow = NaturalSpline(floatArrayOf(0f, .15f, .6f, 1f), floatArrayOf(0f, 1f, 1f, 0f))

    class Pose(var scale: Float = .95f, var lift: Float = .01f, var glow: Float = 0f)

    fun targets(timing: SpicyTiming, position: Long, out: Pose) {
        val p = spicyProgress(position, timing.startMs, timing.endMs)
        if (!timing.emphasized) {
            out.scale = wordScale.at(p)
            out.lift = wordLift.at(p)
            out.glow = glow.at(p).coerceIn(0f, 1f)
            return
        }
        if (position < timing.startMs) {
            out.scale = .95f; out.lift = .01f; out.glow = 0f
            return
        }
        if (position >= timing.groupEndMs) {
            out.scale = 1f; out.lift = 0f; out.glow = 0f
            return
        }
        val duration = timing.groupEndMs - timing.groupStartMs
        val active = floor((position - timing.groupStartMs).toDouble() * timing.letterCount / duration)
            .toInt().coerceIn(0, timing.letterCount - 1)
        val activeStart = timing.groupStartMs + duration * active / timing.letterCount
        val activeEnd = timing.groupStartMs + duration * (active + 1) / timing.letterCount
        val phase = spicyProgress(position, activeStart, activeEnd)
        val distance = abs(timing.letterIndex - active).toDouble()
        val falloff = (1 / (1 + distance.pow(2.8))).toFloat()
        val glowFalloff = (1 / (1 + distance * .9)).toFloat()
        out.scale = .95f + (letterScale.at(phase) - .95f) * falloff
        out.lift = .01f + (letterLift.at(phase) - .01f) * falloff
        out.glow = (glow.at(phase) * glowFalloff).coerceIn(0f, 1f)
    }

    /** Normal-mode letters use d3 easeSinOut; ordinary words fill linearly. */
    fun fillProgress(timing: SpicyTiming, position: Long): Float {
        val p = spicyProgress(position, timing.startMs, timing.endMs)
        return if (timing.emphasized) kotlin.math.sin(p * Math.PI / 2).toFloat() else p
    }

    fun glowOpacity(emphasized: Boolean, strength: Float): Float =
        (strength * if (emphasized) 1.85f else .35f).coerceIn(0f, 1f)

    /** Match 4+2g / 4+12g CSS-pixel radii at the reference's 3.5rem (56px) size. */
    fun glowRadius(textSize: Float, emphasized: Boolean, strength: Float): Float =
        textSize / 56f * (4f + (if (emphasized) 12f else 2f) * strength)
}

/** Natural cubic spline, matching cubic-spline's zero endpoint second derivatives. */
private class NaturalSpline(private val x: FloatArray, private val y: FloatArray) {
    private val second = FloatArray(x.size)
    init {
        val diagonal = FloatArray(x.size)
        val rhs = FloatArray(x.size)
        diagonal[0] = 1f
        for (i in 1 until x.lastIndex) {
            val left = x[i] - x[i - 1]
            val right = x[i + 1] - x[i]
            val factor = left / diagonal[i - 1]
            diagonal[i] = 2 * (left + right) - factor * (if (i == 1) 0f else left)
            rhs[i] = 6 * ((y[i + 1] - y[i]) / right - (y[i] - y[i - 1]) / left) - factor * rhs[i - 1]
        }
        for (i in x.lastIndex - 1 downTo 1) {
            second[i] = (rhs[i] - (x[i + 1] - x[i]) * second[i + 1]) / diagonal[i]
        }
    }
    fun at(value: Float): Float {
        val t = value.coerceIn(x.first(), x.last())
        var i = 0
        while (i < x.lastIndex - 1 && t > x[i + 1]) i++
        val width = x[i + 1] - x[i]
        val a = (x[i + 1] - t) / width
        val b = (t - x[i]) / width
        return a * y[i] + b * y[i + 1] +
            ((a * a * a - a) * second[i] + (b * b * b - b) * second[i + 1]) * width * width / 6
    }
}
