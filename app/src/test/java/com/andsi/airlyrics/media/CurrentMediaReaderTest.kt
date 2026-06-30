package com.andsi.airlyrics.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrentMediaReaderTest {
    @Test
    fun estimatedPositionMs_returnsZeroWhenStateIsMissing() {
        assertEquals(0L, CurrentMediaReader.estimatedPositionMs(null, elapsedRealtimeMs = 10_000L))
    }

    @Test
    fun estimatedPositionMs_doesNotAdvancePausedState() {
        val state = playbackState(
            state = PlaybackState.STATE_PAUSED,
            positionMs = 62_000L,
            speed = 0f,
            updateTimeMs = 5_000L
        )

        assertEquals(62_000L, CurrentMediaReader.estimatedPositionMs(state, elapsedRealtimeMs = 12_000L))
    }

    @Test
    fun estimatedPositionMs_advancesPlayingStateFromLastUpdateTime() {
        val state = playbackState(
            state = PlaybackState.STATE_PLAYING,
            positionMs = 62_000L,
            speed = 1f,
            updateTimeMs = 5_000L
        )

        assertEquals(69_000L, CurrentMediaReader.estimatedPositionMs(state, elapsedRealtimeMs = 12_000L))
    }

    @Test
    fun estimatedPositionMs_appliesPlaybackSpeed() {
        val state = playbackState(
            state = PlaybackState.STATE_PLAYING,
            positionMs = 10_000L,
            speed = 1.5f,
            updateTimeMs = 2_000L
        )

        assertEquals(16_000L, CurrentMediaReader.estimatedPositionMs(state, elapsedRealtimeMs = 6_000L))
    }

    private fun playbackState(
        state: Int,
        positionMs: Long,
        speed: Float,
        updateTimeMs: Long
    ): PlaybackState {
        return PlaybackState.Builder()
            .setState(state, positionMs, speed, updateTimeMs)
            .build()
    }
}
