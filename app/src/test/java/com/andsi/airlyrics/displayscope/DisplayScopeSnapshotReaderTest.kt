package com.andsi.airlyrics.displayscope

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScopeSnapshotReaderTest {
    @Test
    fun firstRead_reconstructsVisiblePackagesFromBootBoundedWindow() {
        val source = RecordingEventSource(
            events = listOf(
                event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"),
                event(UsageEvents.Event.ACTIVITY_RESUMED, "lyrics.app", "LyricsActivity"),
                event(UsageEvents.Event.ACTIVITY_STOPPED, "lyrics.app", "LyricsActivity")
            )
        )
        val reader = reader(now = { 50_000L }, elapsed = { 8_000L }, source = source)

        val snapshot = reader.readSnapshot()

        assertEquals(DisplayScopeVisibilitySnapshot(true, setOf("player.app")), snapshot)
        assertEquals(listOf(42_000L to 50_000L), source.queries)
    }

    @Test
    fun subsequentRead_overlapsPreviousWindowAndRetainsActivityState() {
        var now = 50_000L
        val source = RecordingEventSource(
            events = listOf(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        )
        val reader = reader(now = { now }, elapsed = { 8_000L }, source = source)
        assertEquals(setOf("player.app"), reader.readSnapshot().visiblePackages)

        now = 52_500L
        source.events = emptyList()
        val second = reader.readSnapshot()

        assertEquals(setOf("player.app"), second.visiblePackages)
        assertEquals(listOf(42_000L to 50_000L, 49_000L to 52_500L), source.queries)
    }

    @Test
    fun missingUsageAccess_clearsStateAndSkipsUsageQuery() {
        var granted = true
        val source = RecordingEventSource(
            events = listOf(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        )
        val reader = reader(hasAccess = { granted }, source = source)
        reader.readSnapshot()

        granted = false
        val snapshot = reader.readSnapshot()

        assertEquals(DisplayScopeVisibilitySnapshot(false, emptySet()), snapshot)
        assertEquals(1, source.queries.size)
    }

    @Test
    fun queryFailure_marksSnapshotUnavailableAndRestartsWithInitialWindow() {
        var now = 50_000L
        val source = RecordingEventSource(failure = IllegalStateException("usage service failed"))
        val reader = reader(now = { now }, elapsed = { 8_000L }, source = source)

        val failed = reader.readSnapshot()

        assertTrue(failed.usageAccessGranted)
        assertTrue(failed.visiblePackages.isEmpty())
        assertFalse(failed.visibilitySnapshotAvailable)

        now = 60_000L
        source.failure = null
        source.events = listOf(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        val recovered = reader.readSnapshot()

        assertEquals(DisplayScopeVisibilitySnapshot(true, setOf("player.app")), recovered)
        assertEquals(listOf(42_000L to 50_000L, 52_000L to 60_000L), source.queries)
    }

    @Test
    fun unavailableDisplay_clearsVisibilityAndResumesFromAnOverlappingBoundary() {
        var now = 50_000L
        val source = RecordingEventSource(
            events = listOf(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        )
        val reader = reader(now = { now }, source = source)
        reader.readSnapshot()

        now = 55_000L
        assertEquals(
            DisplayScopeVisibilitySnapshot(true, emptySet()),
            reader.displayUnavailableSnapshot()
        )

        now = 58_000L
        source.events = listOf(event(UsageEvents.Event.ACTIVITY_RESUMED, "other.app", "OtherActivity"))
        val resumed = reader.readSnapshot()

        assertEquals(setOf("other.app"), resumed.visiblePackages)
        assertEquals(54_000L to 58_000L, source.queries.last())
    }

    private fun reader(
        hasAccess: () -> Boolean = { true },
        now: () -> Long = { 50_000L },
        elapsed: () -> Long = { 8_000L },
        source: RecordingEventSource
    ): DisplayScopeSnapshotReader {
        return DisplayScopeSnapshotReader(
            hasUsageAccess = hasAccess,
            currentTimeMillis = now,
            elapsedRealtimeMillis = elapsed,
            readEvents = source::read
        )
    }

    private class RecordingEventSource(
        var events: List<DisplayScopeUsageEvent> = emptyList(),
        var failure: Throwable? = null
    ) {
        val queries = mutableListOf<Pair<Long, Long>>()

        fun read(beginMs: Long, endMs: Long, accept: (DisplayScopeUsageEvent) -> Unit) {
            queries += beginMs to endMs
            failure?.let { throw it }
            events.forEach(accept)
        }
    }

    private fun event(type: Int, packageName: String, activityName: String) =
        DisplayScopeUsageEvent(type, packageName, activityName)
}
