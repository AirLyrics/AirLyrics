package com.andsi.airlyrics.floating

import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import com.andsi.airlyrics.core.model.FloatingPosition
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FloatingWindowArea(
    val width: Int,
    val height: Int,
    val topOffset: Int = 0,
    val legacyTopOffset: Int = 0,
    val fitInsetsTypes: Int = 0,
) {
    companion object {
        @RequiresApi(30)
        fun fromInsets(
            displayWidth: Int,
            displayHeight: Int,
            insets: WindowInsets,
        ): FloatingWindowArea {
            val statusBars = WindowInsets.Type.statusBars()
            val fitTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val stable = insets.getInsetsIgnoringVisibility(fitTypes)
            val fitted = FloatingWindowArea(
                width = (displayWidth - stable.left - stable.right).coerceAtLeast(1),
                height = (displayHeight - stable.top - stable.bottom).coerceAtLeast(1),
                fitInsetsTypes = fitTypes,
            )
            if (Build.VERSION.SDK_INT < 35) return fitted
            val frame = insets.frame
            if (frame.width != displayWidth || frame.height != displayHeight) return fitted

            val otherTypes = fitTypes and statusBars.inv()
            val withoutStatus = insets.getInsetsIgnoringVisibility(otherTypes)
            if (withoutStatus.top >= stable.top || withoutStatus.left != stable.left ||
                withoutStatus.right != stable.right || withoutStatus.bottom != stable.bottom
            ) return fitted

            val barBottom = statusBarBottom(insets, statusBars) ?: return fitted
            val top = maxOf(
                withoutStatus.top,
                barBottom,
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures()).top,
            )
            if (top >= stable.top || top >= displayHeight - stable.bottom) return fitted

            // WindowManager clamps to its fitted frame. Without status-bar fitting, Y starts at withoutStatus.top.
            return fitted.copy(
                height = displayHeight - top - stable.bottom,
                topOffset = top - withoutStatus.top,
                legacyTopOffset = stable.top - withoutStatus.top,
                fitInsetsTypes = otherTypes,
            )
        }

        @RequiresApi(35)
        private fun statusBarBottom(insets: WindowInsets, statusBars: Int): Int? {
            val reservedBottom = occupiedBottom(insets.getBoundingRectsIgnoringVisibility(statusBars)) ?: return null
            val visibleBottom = occupiedBottom(insets.getBoundingRects(statusBars))
                ?: insets.getInsets(statusBars).top
            return maxOf(reservedBottom, visibleBottom)
        }

        private fun occupiedBottom(rects: List<Rect>): Int? =
            rects.maxOfOrNull { if (it.isEmpty) 0 else it.bottom }?.takeIf { it > 0 }
    }
}

internal data class FloatingWindowGeometry(
    val area: FloatingWindowArea,
    val width: Int,
    val height: Int,
) {
    private val travelX = (area.width - width).coerceAtLeast(0)
    private val travelY = (area.height - height).coerceAtLeast(0)

    val hasVerticalTravel: Boolean
        get() = travelY > 0

    fun clampX(x: Int): Int = x.coerceIn(0, travelX)

    fun clampY(y: Int): Int = y.coerceIn(area.topOffset, area.topOffset + travelY)

    fun coordinates(position: FloatingPosition): Pair<Int, Int> {
        val x = (position.horizontalTravelFraction * travelX).roundToInt()
        val y = area.topOffset + (position.verticalTravelFraction * travelY).roundToInt()
        return x to y
    }

    fun position(x: Int, y: Int, fallback: FloatingPosition): FloatingPosition =
        FloatingPosition(
            if (travelX > 0) clampX(x).toFloat() / travelX else fallback.horizontalTravelFraction,
            if (hasVerticalTravel) verticalFraction(y) else fallback.verticalTravelFraction,
        )

    fun rebaseLegacyPosition(position: FloatingPosition): FloatingPosition? {
        val topDifference = area.legacyTopOffset - area.topOffset
        val legacyTravelY = travelY - topDifference
        if (legacyTravelY <= 0 || !hasVerticalTravel) return null
        if (topDifference == 0) return position
        val legacyY = area.legacyTopOffset + (position.verticalTravelFraction * legacyTravelY).roundToInt()
        return position.copy(verticalTravelFraction = verticalFraction(legacyY))
    }

    private fun verticalFraction(y: Int): Float = (clampY(y) - area.topOffset).toFloat() / travelY

    fun migrate(x: Int, y: Int, tolerance: Int): FloatingPosition {
        val position = position(x, y, FloatingPosition(0.5f, 0f))
        return if (abs(x - travelX / 2f) <= tolerance) position.copy(horizontalTravelFraction = 0.5f) else position
    }

    companion object {
        fun width(available: Int, percent: Int): Int =
            (available * percent.coerceIn(45, 100) / 100f).roundToInt().coerceAtLeast(1)
    }
}
