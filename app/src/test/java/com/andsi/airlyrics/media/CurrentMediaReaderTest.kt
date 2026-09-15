package com.andsi.airlyrics.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CurrentMediaReaderTest {
    @Test
    fun estimatedPositionMs_handlesPlaybackStatesAndSpeed() {
        val cases = listOf(
            PositionCase(
                name = "missing state",
                state = null,
                elapsedRealtimeMs = 10_000L,
                expectedPositionMs = 0L
            ),
            PositionCase(
                name = "paused state",
                state = playbackState(
                    state = PlaybackState.STATE_PAUSED,
                    positionMs = 62_000L,
                    speed = 0f,
                    updateTimeMs = 5_000L
                ),
                elapsedRealtimeMs = 12_000L,
                expectedPositionMs = 62_000L
            ),
            PositionCase(
                name = "playing at normal speed",
                state = playbackState(
                    state = PlaybackState.STATE_PLAYING,
                    positionMs = 62_000L,
                    speed = 1f,
                    updateTimeMs = 5_000L
                ),
                elapsedRealtimeMs = 12_000L,
                expectedPositionMs = 69_000L
            ),
            PositionCase(
                name = "playing at 1.5x speed",
                state = playbackState(
                    state = PlaybackState.STATE_PLAYING,
                    positionMs = 10_000L,
                    speed = 1.5f,
                    updateTimeMs = 2_000L
                ),
                elapsedRealtimeMs = 6_000L,
                expectedPositionMs = 16_000L
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedPositionMs,
                CurrentMediaReader.estimatedPositionMs(case.state, case.elapsedRealtimeMs)
            )
        }
    }

    @Test
    fun selectedCandidate_prefersPlayingControllerWithTitleWithinSelectedPackage() {
        val paused = candidate(
            value = "paused",
            packageName = "player.selected",
            hasMediaTitle = true,
            isPlaying = false
        )
        val playing = candidate(
            value = "playing",
            packageName = "player.selected",
            hasMediaTitle = true,
            isPlaying = true
        )

        val selected = CurrentMediaReader.selectedCandidate(
            candidates = listOf(paused, playing),
            selectedPackage = "player.selected"
        )

        assertEquals("playing", selected?.value)
    }

    @Test
    fun bestCandidate_usesSelectedPackageBeforeGlobalFallbacks() {
        val selectedPackageCandidate = candidate(
            value = "selected",
            packageName = "player.selected",
            hasMediaTitle = true,
            isPlaying = false
        )
        val globallyBetterCandidate = candidate(
            value = "global-playing",
            packageName = "player.other",
            hasMediaTitle = true,
            isPlaying = true
        )

        val selected = CurrentMediaReader.bestCandidate(
            candidates = listOf(globallyBetterCandidate, selectedPackageCandidate),
            selectedPackage = "player.selected"
        )

        assertEquals("selected", selected?.value)
    }

    @Test
    fun bestCandidate_fallsBackToPlayingControllerWithTitleWhenSelectedPackageIsMissing() {
        val paused = candidate(
            value = "paused",
            packageName = "player.paused",
            hasMediaTitle = true,
            isPlaying = false
        )
        val playing = candidate(
            value = "playing",
            packageName = "player.playing",
            hasMediaTitle = true,
            isPlaying = true
        )

        val selected = CurrentMediaReader.bestCandidate(
            candidates = listOf(paused, playing),
            selectedPackage = "missing.player"
        )

        assertEquals("playing", selected?.value)
    }

    @Test
    fun bestCandidate_ignoresControllersWithoutMetadataOrPlaybackState() {
        val unusable = candidate(
            value = "unusable",
            packageName = "player.unusable",
            hasMetadata = false,
            hasPlaybackState = false,
            hasMediaTitle = true,
            isPlaying = true
        )
        val fallback = candidate(
            value = "fallback",
            packageName = "player.fallback",
            hasMediaTitle = false,
            isPlaying = true
        )

        val selected = CurrentMediaReader.bestCandidate(
            candidates = listOf(unusable, fallback),
            selectedPackage = null
        )

        assertEquals("fallback", selected?.value)
    }

    @Test
    fun selectedCandidate_returnsNullForBlankSelectedPackage() {
        val selected = CurrentMediaReader.selectedCandidate(
            candidates = listOf(candidate(value = "candidate", packageName = "player")),
            selectedPackage = ""
        )

        assertNull(selected)
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

    private data class PositionCase(
        val name: String,
        val state: PlaybackState?,
        val elapsedRealtimeMs: Long,
        val expectedPositionMs: Long
    )

    private fun candidate(
        value: String,
        packageName: String,
        hasMetadata: Boolean = true,
        hasPlaybackState: Boolean = true,
        hasMediaTitle: Boolean = true,
        isPlaying: Boolean = false
    ): CurrentMediaReader.ControllerCandidate<String> {
        return CurrentMediaReader.ControllerCandidate(
            value = value,
            packageName = packageName,
            hasMetadata = hasMetadata,
            hasPlaybackState = hasPlaybackState,
            hasMediaTitle = hasMediaTitle,
            isPlaying = isPlaying
        )
    }
}
