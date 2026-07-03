package com.andsi.airlyrics.floating

import com.andsi.airlyrics.media.model.CurrentMediaInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSnapshotGateTest {
    private val gate = MediaSnapshotGate()

    @Test
    fun markAcceptedIfFresh_rejectsOlderOrDuplicateSnapshots() {
        assertTrue(gate.markAcceptedIfFresh(media(sequence = 10L)))

        assertFalse(gate.markAcceptedIfFresh(media(sequence = 9L)))
        assertFalse(gate.markAcceptedIfFresh(media(sequence = 10L)))
        assertTrue(gate.markAcceptedIfFresh(media(sequence = 11L)))
    }

    @Test
    fun markAcceptedIfFresh_acceptsUnspecifiedSequenceWithoutMovingWatermark() {
        assertTrue(gate.markAcceptedIfFresh(media(sequence = 5L)))

        assertTrue(gate.markAcceptedIfFresh(media(sequence = CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE)))
        assertFalse(gate.markAcceptedIfFresh(media(sequence = 4L)))
    }

    private fun media(sequence: Long): CurrentMediaInfo {
        return CurrentMediaInfo(
            sourcePackage = "player",
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            isPlaying = true,
            positionMs = 1_000L,
            snapshotSequence = sequence
        )
    }
}
