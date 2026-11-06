package com.andsi.airlyrics.ui.pages.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotFallTest {
    @Test fun gravityHitsFloorAndComesToRestWithoutDocking() {
        val fall = MascotFall(1f)
        fall.setBounds(-50f, -80f, 150f, 240f)
        fall.launch(30f, -20f, 0f, 0f)
        var bounced = false
        var active = true
        repeat(240) {
            active = fall.step(1f / 60f)
            if (fall.vy < 0f) bounced = true
        }
        assertTrue(bounced)
        assertFalse(active)
        assertEquals(240f, fall.y, 0.5f)
        assertEquals(0f, fall.vy, 0.1f)
    }

    @Test fun sideAndCeilingCollisionsReverseVelocity() {
        val fall = MascotFall(1f)
        fall.setBounds(0f, 0f, 100f, 200f)
        fall.launch(95f, 1f, 900f, -500f)
        fall.step(1f / 60f)
        assertEquals(100f, fall.x, 0.1f)
        assertTrue(fall.vx < 0f)
        assertEquals(0f, fall.y, 0.1f)
        assertTrue(fall.vy > 0f)
    }

    @Test fun springRequiresGroundAndCompressionControlsLaunchSpeed() {
        val fall = MascotFall(1f)
        fall.setBounds(0f, 0f, 100f, 200f)
        fall.launch(50f, 50f, 0f, 0f)
        assertFalse(fall.springLaunch(1f, 1f))
        fall.settleOnFloor()
        assertTrue(fall.isGrounded)
        assertTrue(fall.springLaunch(0.25f, 1f))
        val weakVerticalSpeed = -fall.vy
        assertFalse(fall.isGrounded)
        fall.settleOnFloor()
        assertTrue(fall.springLaunch(1f, -1f))
        assertTrue(-fall.vy > weakVerticalSpeed)
        assertTrue(fall.vx < 0f)
        var wallHits = 0
        repeat(180) {
            fall.step(1f / 60f)
            if ((fall.collisionEdge != null) && (fall.collisionSpeed > 300f)) wallHits++
            assertTrue(fall.x in (0f..100f))
            assertTrue(fall.y in (0f..200f))
        }
        assertTrue(wallHits >= 2)
    }

    @Test fun interruptedFallKeepsPositionAndBounds() {
        val fall = MascotFall(1f)
        fall.setBounds(0f, 0f, 100f, 200f)
        fall.launch(40f, 50f, 0f, 300f)
        fall.step(1f / 60f)
        val oldY = fall.y
        fall.shiftOrigin(-20f, 30f)
        fall.setBounds(-20f, 30f, 80f, 230f)
        assertEquals(oldY + 30f, fall.y, 0.1f)
        fall.launch(fall.x, fall.y, -500f, -200f)
        assertTrue(fall.vx < 0f)
        assertTrue(fall.vy < 0f)
    }
}
