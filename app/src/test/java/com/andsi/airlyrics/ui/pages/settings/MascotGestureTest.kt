package com.andsi.airlyrics.ui.pages.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotGestureTest {
    @Test fun catTapIsDistinctFromCloudTap() {
        val gesture = MascotGesture(8f)
        assertEquals(MascotGesture.Target.CAT, mascotTargetAt(0.5f, 0.35f))
        assertEquals(MascotGesture.Target.CLOUD, mascotTargetAt(0.5f, 0.7f))
        assertTrue(gesture.down(1, 20f, 20f, MascotGesture.Target.CAT))
        assertEquals(MascotGesture.End.Tap(MascotGesture.Target.CAT), gesture.up(1, 23f, 22f))
        assertTrue(gesture.down(2, 20f, 20f, MascotGesture.Target.CLOUD))
        assertEquals(MascotGesture.End.Tap(MascotGesture.Target.CLOUD), gesture.up(2, 20f, 20f))
    }

    @Test fun dragNeverBecomesTapAndKeepsItsOriginalTarget() {
        val gesture = MascotGesture(8f)
        gesture.down(4, 10f, 10f, MascotGesture.Target.CLOUD)
        assertEquals(MascotGesture.Phase.DRAGGING, gesture.move(4, 25f, 10f).phase)
        assertEquals(MascotGesture.Phase.DRAGGING, gesture.move(4, 11f, 10f).phase)
        assertEquals(MascotGesture.End.Drag(MascotGesture.Target.CLOUD), gesture.up(4, 11f, 10f))
        assertEquals(MascotGesture.Phase.IDLE, gesture.snapshot.phase)
    }

    @Test fun cancelAndSecondPointerCannotStealGesture() {
        val gesture = MascotGesture(8f)
        gesture.down(1, 0f, 0f, MascotGesture.Target.CAT)
        assertFalse(gesture.down(2, 0f, 0f, MascotGesture.Target.CLOUD))
        gesture.move(2, 100f, 100f)
        assertEquals(MascotGesture.Phase.PRESSED, gesture.snapshot.phase)
        assertEquals(MascotGesture.End.None, gesture.up(2, 100f, 100f))
        gesture.cancel()
        assertEquals(MascotGesture.End.None, gesture.up(1, 0f, 0f))
        assertTrue(gesture.down(3, 0f, 0f, MascotGesture.Target.CLOUD))
    }
}
