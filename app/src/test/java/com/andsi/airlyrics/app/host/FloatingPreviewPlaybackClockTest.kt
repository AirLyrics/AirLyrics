package com.andsi.airlyrics.app.host

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingPreviewPlaybackClockTest {
    @Test
    fun hiddenTimeIsDiscardedAndReturningRestartsTheCycle() {
        var now = 100L
        val clock = FloatingPreviewPlaybackClock(5_000L, uptime = { now })
        assertEquals(0L, clock.position())
        clock.setVisible(true)
        now += 700L
        assertEquals(700L, clock.position())
        clock.setVisible(true)
        assertEquals(700L, clock.position())

        clock.setVisible(false)
        now += 30_000L
        assertEquals(0L, clock.position())
        clock.setVisible(true)
        assertEquals(0L, clock.position())
        now += 5_100L
        assertEquals(100L, clock.position())
    }

    @Test
    fun linePreviewHoldsItsFinalPoseUntilTheNextCycle() {
        var now = 0L
        val clock = FloatingPreviewPlaybackClock(4_800L, 3_999L) { now }
        clock.setVisible(true)
        now = 4_500L
        assertEquals(3_999L, clock.position())
        now = 4_800L
        assertEquals(0L, clock.position())
    }
}
