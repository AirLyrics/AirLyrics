package com.andsi.airlyrics.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingAutoHideStateTest {
    private var now = 0L
    private val state = FloatingAutoHideState { now }
    private val conditions = AutoHideConditions(
        desiredVisible = true,
        isPlaying = false,
        hideWhenPaused = true,
        hideWhenLyricsUnavailable = true,
    )
    private val firstTrack = PlaybackLyricsKey("first")
    private val nextTrack = PlaybackLyricsKey("next")

    @Test
    fun independentDeadlinesKeepWindowHiddenUntilBothReasonsClear() {
        state.setLyricsAvailability(LyricsAvailability.UNAVAILABLE, firstTrack)
        state.evaluate(conditions)
        assertEquals(AUTO_HIDE_WHEN_PAUSED_DELAY_MS, state.nextDeadlineUptimeMs)

        now = AUTO_HIDE_WHEN_PAUSED_DELAY_MS - 1L
        state.evaluate(conditions)
        assertFalse(state.shouldHide)

        now++
        state.evaluate(conditions)
        assertEquals(setOf(AutoHideReason.PAUSED), state.activeReasons)

        now = AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS
        state.evaluate(conditions)
        assertEquals(setOf(AutoHideReason.PAUSED, AutoHideReason.LYRICS_UNAVAILABLE), state.activeReasons)

        val playing = conditions.copy(isPlaying = true)
        state.evaluate(playing)
        assertEquals(setOf(AutoHideReason.LYRICS_UNAVAILABLE), state.activeReasons)
        assertTrue(state.shouldHide)

        state.setLyricsAvailability(LyricsAvailability.AVAILABLE, firstTrack)
        state.evaluate(playing)
        assertFalse(state.shouldHide)
        assertNull(state.nextDeadlineUptimeMs)
    }

    @Test
    fun awaitingReplacementKeepsLyricsHiddenAndManualShowAppliesToThatTrack() {
        val playing = conditions.copy(isPlaying = true)
        state.setLyricsAvailability(LyricsAvailability.UNAVAILABLE, firstTrack)
        state.evaluate(playing)
        now += AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS
        state.evaluate(playing)
        assertTrue(state.shouldHide)

        state.setLyricsAvailability(LyricsAvailability.LOADING, nextTrack)
        state.evaluate(playing)
        assertTrue(state.shouldHide)
        assertNull(state.nextDeadlineUptimeMs)

        state.suppressForManualShow(playing, nextTrack)
        state.setLyricsAvailability(LyricsAvailability.UNAVAILABLE, nextTrack)
        state.evaluate(playing)
        assertFalse(state.shouldHide)
        assertNull(state.nextDeadlineUptimeMs)

        state.setLyricsAvailability(LyricsAvailability.UNAVAILABLE, firstTrack)
        state.evaluate(playing)
        now += AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS
        state.evaluate(playing)
        assertTrue(state.shouldHide)
    }
}
