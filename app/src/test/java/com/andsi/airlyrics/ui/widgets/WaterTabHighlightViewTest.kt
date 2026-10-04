package com.andsi.airlyrics.ui.widgets

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WaterTabHighlightViewTest {
    @Test fun oversizedHighlightCanMoveInsideNarrowRailWithoutAnEmptyClampRange() {
        val view = WaterTabHighlightView(ApplicationProvider.getApplicationContext())
        view.layout(0, 0, 88, 240)
        view.moveTo(44f, 80f, 104f, 60f, animate = false)
        assertTrue(view.hasPosition)
        view.layout(0, 0, 10, 20)
        view.moveTo(44f, 80f, 104f, 60f, animate = false)
        assertTrue(view.hasPosition)
    }
}
