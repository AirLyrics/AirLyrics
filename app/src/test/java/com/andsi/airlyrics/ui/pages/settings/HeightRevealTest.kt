package com.andsi.airlyrics.ui.pages.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HeightRevealTest {
    @Test
    fun nestedContentAppearsOnlyAfterItsFullShapeFits() {
        assertEquals(0f, heightRevealContentAlpha(0, 88, 100), 0f)
        assertEquals(0f, heightRevealContentAlpha(88, 88, 100), 0f)
        assertEquals(0.5f, heightRevealContentAlpha(94, 88, 100), 0f)
        assertEquals(1f, heightRevealContentAlpha(100, 88, 100), 0f)
        assertEquals(1f, heightRevealContentAlpha(120, 88, 100), 0f)
    }

    @Test
    fun contentWithNoTrailingRoomWaitsUntilTheRevealCompletes() {
        assertEquals(0f, heightRevealContentAlpha(99, 100, 100), 0f)
        assertEquals(1f, heightRevealContentAlpha(100, 100, 100), 0f)
    }
}
