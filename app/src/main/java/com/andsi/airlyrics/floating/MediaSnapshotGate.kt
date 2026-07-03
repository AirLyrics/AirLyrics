package com.andsi.airlyrics.floating

import com.andsi.airlyrics.media.model.CurrentMediaInfo

internal class MediaSnapshotGate {
    private var lastAcceptedSnapshotSequence = CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE

    fun markAcceptedIfFresh(media: CurrentMediaInfo): Boolean {
        val sequence = media.snapshotSequence
        if (sequence == CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE) {
            return true
        }

        val lastAccepted = lastAcceptedSnapshotSequence
        if (lastAccepted != CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE &&
            sequence <= lastAccepted
        ) {
            return false
        }

        lastAcceptedSnapshotSequence = sequence
        return true
    }
}
