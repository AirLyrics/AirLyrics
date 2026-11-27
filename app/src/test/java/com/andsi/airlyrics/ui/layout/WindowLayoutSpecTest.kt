package com.andsi.airlyrics.ui.layout

import org.junit.Assert.*
import org.junit.Test

class WindowLayoutSpecTest {
    @Test fun navigationUsesBothWindowDimensions() {
        assertEquals(0, WindowLayoutSpec(619f, 390f, 1f).railWidthDp)
        assertEquals(84, WindowLayoutSpec(620f, 479f, 1f).railWidthDp)
        assertEquals(0, WindowLayoutSpec(620f, 480f, 1f).railWidthDp)
        assertEquals(0, WindowLayoutSpec(899f, 480f, 1f).railWidthDp)
        assertEquals(104, WindowLayoutSpec(900f, 480f, 1f).railWidthDp)
        assertEquals(0, WindowLayoutSpec(800f, 1024f, 1f).railWidthDp)
    }

    @Test fun twoColumnsRequireAnExpandedWindowAndRoomForBothScaledCards() {
        val wide = WindowLayoutSpec(1240f, 775f, 1f)
        assertFalse(wide.usesTwoColumns(743f))
        assertTrue(wide.usesTwoColumns(744f))
        assertFalse(wide.copy(fontScale = 2f).usesTwoColumns(1112f))
        assertTrue(wide.copy(fontScale = 0.85f).usesTwoColumns(744f))
        assertFalse(WindowLayoutSpec(800f, 1024f, 1f).usesTwoColumns(760f))
    }
}
