package com.andsi.airlyrics.floating

import com.andsi.airlyrics.core.model.FloatingPosition
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FloatingWindowGeometry(
    val availableWidth: Int,
    val availableHeight: Int,
    val width: Int,
    val height: Int
) {
    val travelX = (availableWidth - width).coerceAtLeast(0)
    val travelY = (availableHeight - height).coerceAtLeast(0)

    fun coordinates(position: FloatingPosition): Pair<Int, Int> =
        (position.horizontalTravelFraction * travelX).roundToInt() to
            (position.verticalTravelFraction * travelY).roundToInt()

    fun position(x: Int, y: Int, fallback: FloatingPosition): FloatingPosition =
        FloatingPosition(
            if (travelX > 0) x.coerceIn(0, travelX).toFloat() / travelX else fallback.horizontalTravelFraction,
            if (travelY > 0) y.coerceIn(0, travelY).toFloat() / travelY else fallback.verticalTravelFraction
        )

    fun migrate(x: Int, y: Int, tolerance: Int): FloatingPosition {
        val position = position(x, y, FloatingPosition(0.5f, 0f))
        return if (abs(x - travelX / 2f) <= tolerance) position.copy(horizontalTravelFraction = 0.5f) else position
    }

    companion object {
        fun width(available: Int, percent: Int): Int =
            (available * percent.coerceIn(45, 100) / 100f).roundToInt().coerceAtLeast(1)
    }
}
