package com.andsi.airlyrics.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLyricsRendererTest {
    @Test
    fun updatePlayback_ignoresSmallStaleBacktrackWhilePlaying() {
        var now = 10_000L
        val renderer = renderer { now }

        renderer.updatePlayback(positionMs = 1_000L, isPlaying = true)
        now += 700L
        assertEquals(1_700L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 1_200L, isPlaying = true)
        assertEquals(1_700L, renderer.getEstimatedPositionMs())

        now += 100L
        assertEquals(1_800L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun updatePlayback_acceptsLargeBackwardJumpAsSeek() {
        var now = 10_000L
        val renderer = renderer { now }

        renderer.updatePlayback(positionMs = 10_000L, isPlaying = true)
        now += 3_000L
        assertEquals(13_000L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 8_000L, isPlaying = true)
        assertEquals(8_000L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun updatePlayback_acceptsPausedPositionEvenWhenBehind() {
        var now = 10_000L
        val renderer = renderer { now }

        renderer.updatePlayback(positionMs = 5_000L, isPlaying = true)
        now += 700L
        assertEquals(5_700L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 5_200L, isPlaying = false)
        assertEquals(5_200L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun setLyricsOffset_reportsChangesAndShiftsEstimatedPosition() {
        val now = 10_000L
        val renderer = renderer { now }

        renderer.updatePlayback(positionMs = 1_000L, isPlaying = false)

        assertTrue(renderer.setLyricsOffset(500L))
        assertEquals(1_500L, renderer.getEstimatedPositionMs())

        assertFalse(renderer.setLyricsOffset(500L))
        assertEquals(1_500L, renderer.getEstimatedPositionMs())
    }

    private fun renderer(uptimeMillisProvider: () -> Long): FloatingLyricsRenderer {
        return FloatingLyricsRenderer(
            textViewProvider = { null },
            wordByWordHighlightColorProvider = { 0 },
            uptimeMillisProvider = uptimeMillisProvider
        )
    }
}
