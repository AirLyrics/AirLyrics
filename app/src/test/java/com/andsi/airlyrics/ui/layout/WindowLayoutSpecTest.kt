package com.andsi.airlyrics.ui.layout

import org.junit.Assert.*
import org.junit.Test

class WindowLayoutSpecTest {
    @Test fun navigationUsesWindowWidthRatherThanOrientation() {
        assertFalse(WindowLayoutSpec(599f, 320f, 1f).useRail)
        assertTrue(WindowLayoutSpec(600f, 1000f, 1f).useRail)
        assertTrue(WindowLayoutSpec(600f, 240f, 2f).useRail)
    }
    @Test fun columnsRespectBothGapAndScaledMinimumCardWidth() {
        val normal = WindowLayoutSpec(1200f, 800f, 1f)
        assertEquals(1, normal.columns(743f))
        assertEquals(2, normal.columns(744f))
        assertEquals(1, normal.copy(fontScale = 2f).columns(1200f))
        assertEquals(2, normal.copy(fontScale = 0.85f).columns(744f))
    }
    @Test fun compactHeightDoesNotChangeNavigation() {
        val tall = WindowLayoutSpec(800f, 600f, 1f)
        assertFalse(tall.compact)
        assertTrue(tall.copy(height = 300f).compact)
        assertEquals(tall.useRail, tall.copy(height = 300f).useRail)
    }
}
