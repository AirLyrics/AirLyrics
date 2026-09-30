package com.andsi.airlyrics.floating

import com.andsi.airlyrics.core.model.FloatingPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowGeometryTest {
    @Test fun centeredPositionSurvivesRepeatedRotations() {
        val portrait = FloatingWindowGeometry(1080, 2200, 918, 100)
        val landscape = FloatingWindowGeometry(2200, 1080, 1870, 180)
        val position = FloatingPosition(0.5f, 0.8f)
        repeat(100) {
            assertEquals(165 to 720, landscape.coordinates(position))
            assertEquals(81 to 1680, portrait.coordinates(position))
        }
    }

    @Test fun fullWidthAndOversizedContentRetainPreferredPosition() {
        val geometry = FloatingWindowGeometry(100, 100, 100, 150)
        val preferred = FloatingPosition(0.8f, 0.9f)
        assertEquals(0 to 0, geometry.coordinates(preferred))
        assertEquals(preferred, geometry.position(40, -10, preferred))
    }

    @Test fun migrationSnapsOnlyNearCenterAndClampsOutsidePositions() {
        val geometry = FloatingWindowGeometry(1000, 800, 800, 100)
        assertEquals(FloatingPosition(0.5f, 1f), geometry.migrate(105, 999, 8))
        assertEquals(FloatingPosition(0.25f, 0f), geometry.migrate(50, -100, 8))
        assertEquals(FloatingPosition(1f, 1f), geometry.migrate(999, 999, 8))
    }

    @Test fun widthsRespectStyleLimits() {
        assertEquals(850, FloatingWindowGeometry.width(1000, 85))
        assertEquals(1000, FloatingWindowGeometry.width(1000, 100))
        assertEquals(450, FloatingWindowGeometry.width(1000, -10))
    }
}
