package com.andsi.airlyrics.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackClockTest {
    private var now = 0L
    private val clock = PlaybackClock { now }

    @Test
    fun ignoresSmallStaleBacktrackWhilePlaying() {
        clock.update(positionMs = 1_000L, isPlaying = true)
        now += 700L
        assertEquals(1_700L, clock.estimatedPositionMs())

        clock.update(positionMs = 1_200L, isPlaying = true)
        assertEquals(1_700L, clock.estimatedPositionMs())

        now += 100L
        assertEquals(1_800L, clock.estimatedPositionMs())
    }

    @Test
    fun acceptsLargeSeekAndExplicitPausedPosition() {
        clock.update(positionMs = 10_000L, isPlaying = true)
        now += 3_000L
        assertEquals(13_000L, clock.estimatedPositionMs())

        clock.update(positionMs = 8_000L, isPlaying = true)
        assertEquals(8_000L, clock.estimatedPositionMs())

        now += 700L
        clock.update(positionMs = 8_200L, isPlaying = false)
        now += 400L
        assertEquals(8_200L, clock.estimatedPositionMs())
        assertFalse(clock.isPlaying)
    }

    @Test
    fun pausesAtEstimateUntilNewPlaybackAndResetsForNewTrack() {
        clock.update(positionMs = 500L, isPlaying = true)
        now += 200L
        assertEquals(700L, clock.pause())
        now += 300L
        assertEquals(700L, clock.estimatedPositionMs())
        assertFalse(clock.isPlaying)

        clock.update(positionMs = 700L, isPlaying = true)
        clock.reset()
        assertEquals(0L, clock.estimatedPositionMs())
        assertFalse(clock.isPlaying)
        clock.update(positionMs = 50L, isPlaying = true)
        assertEquals(50L, clock.estimatedPositionMs())
    }
}
