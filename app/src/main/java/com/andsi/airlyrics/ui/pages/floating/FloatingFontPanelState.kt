package com.andsi.airlyrics.ui.pages.floating

import kotlin.math.min
import kotlin.random.Random

/** Confirmation belongs to one visit, and ignores taps before the changed icon can be read. */
internal class FontDeleteConfirmation {
    enum class Click { ARMED, WAIT, DELETE }
    private var armedAt: Long? = null
    val isArmed: Boolean get() = armedAt != null

    fun click(now: Long): Click {
        val started = armedAt
        if (started == null) {
            armedAt = now
            return Click.ARMED
        }
        if (now - started < CONFIRM_DELAY_MS) return Click.WAIT
        cancel()
        return Click.DELETE
    }

    fun cancel() { armedAt = null }

    companion object {
        const val CONFIRM_DELAY_MS = 450L
    }
}

/** A changing delivery rhythm can lag real bytes, but can never invent downloaded progress. */
internal class FontDownloadCadence(private val random: Random = Random.Default) {
    var displayed: Int = 0
        private set
    var delayMs: Long = 180L
        private set

    fun advance(actual: Int): Int {
        val upper = actual.coerceIn(0, 99)
        val stalled = random.nextFloat() < .23f
        val burst = random.nextFloat() < .34f
        if (!stalled && upper > displayed) {
            val step = if (burst) random.nextInt(8, 23) else random.nextInt(1, 5)
            displayed += min(upper - displayed, step)
        }
        displayed = min(displayed, upper)
        delayMs = if (stalled) random.nextLong(500L, 1050L) else random.nextLong(120L, 370L)
        return displayed
    }
}
