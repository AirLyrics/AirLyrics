package com.andsi.airlyrics.displayscope

/** Stateful UsageEvents reader kept separate from Android scheduling so its failure policy is deterministic. */
internal class DisplayScopeSnapshotReader(
    private val hasUsageAccess: () -> Boolean,
    private val currentTimeMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val readEvents: (Long, Long, (DisplayScopeUsageEvent) -> Unit) -> Unit
) {
    private val tracker = VisibleActivityTracker()
    private var lastQueryEndMs = 0L

    fun reset() {
        tracker.clear()
        lastQueryEndMs = 0L
    }

    fun readSnapshot(): DisplayScopeVisibilitySnapshot {
        val now = currentTimeMillis()
        if (!hasUsageAccess()) {
            reset()
            return DisplayScopeVisibilitySnapshot(false, emptySet())
        }

        val queryStart = if (lastQueryEndMs == 0L) {
            val timeSinceBoot = elapsedRealtimeMillis().coerceAtMost(INITIAL_LOOKBACK_MS)
            now - timeSinceBoot
        } else {
            (lastQueryEndMs - EVENT_QUERY_OVERLAP_MS).coerceAtLeast(0L)
        }
        val eventsRead = runCatching {
            readEvents(queryStart, now, tracker::accept)
        }.isSuccess
        lastQueryEndMs = now

        if (!eventsRead) {
            reset()
            return DisplayScopeVisibilitySnapshot(
                usageAccessGranted = hasUsageAccess(),
                visiblePackages = emptySet(),
                visibilitySnapshotAvailable = false
            )
        }

        return DisplayScopeVisibilitySnapshot(true, tracker.visiblePackages())
    }

    fun displayUnavailableSnapshot(): DisplayScopeVisibilitySnapshot {
        tracker.clear()
        lastQueryEndMs = currentTimeMillis()
        return DisplayScopeVisibilitySnapshot(
            usageAccessGranted = hasUsageAccess(),
            visiblePackages = emptySet()
        )
    }

    private companion object {
        const val EVENT_QUERY_OVERLAP_MS = 1_000L
        const val INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1_000L
    }
}
