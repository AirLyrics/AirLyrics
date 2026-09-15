package com.andsi.airlyrics.displayscope

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleActivityTrackerTest {
    @Test
    fun pausedActivityRemainsVisibleUntilStopped() {
        val tracker = VisibleActivityTracker()
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        tracker.accept(event(UsageEvents.Event.ACTIVITY_PAUSED, "player.app", "PlayerActivity"))

        assertEquals(setOf("player.app"), tracker.visiblePackages())

        tracker.accept(event(UsageEvents.Event.ACTIVITY_STOPPED, "player.app", "PlayerActivity"))
        assertTrue(tracker.visiblePackages().isEmpty())
    }

    @Test
    fun screenOffClearsVisibleActivities() {
        val tracker = VisibleActivityTracker()
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        tracker.accept(event(UsageEvents.Event.SCREEN_NON_INTERACTIVE))

        assertTrue(tracker.visiblePackages().isEmpty())

        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "other.app", "OtherActivity"))
        assertTrue(tracker.visiblePackages().isEmpty())

        tracker.accept(event(UsageEvents.Event.SCREEN_INTERACTIVE))
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        assertEquals(setOf("player.app"), tracker.visiblePackages())
    }

    @Test
    fun stoppingOneOfTwoActivities_keepsThePackageVisibleUntilBothStop() {
        val tracker = VisibleActivityTracker()
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "PlayerActivity"))
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", "QueueActivity"))

        tracker.accept(event(UsageEvents.Event.ACTIVITY_STOPPED, "player.app", "PlayerActivity"))
        assertEquals(setOf("player.app"), tracker.visiblePackages())

        tracker.accept(event(UsageEvents.Event.ACTIVITY_STOPPED, "player.app", "QueueActivity"))
        assertTrue(tracker.visiblePackages().isEmpty())
    }

    @Test
    fun malformedActivityEvents_areIgnoredAndClearRestoresScreenAvailability() {
        val tracker = VisibleActivityTracker()
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "", "MissingPackage"))
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, null, "MissingPackage"))
        assertTrue(tracker.visiblePackages().isEmpty())

        tracker.accept(event(UsageEvents.Event.KEYGUARD_SHOWN))
        tracker.clear()
        tracker.accept(event(UsageEvents.Event.ACTIVITY_RESUMED, "player.app", null))

        assertEquals(setOf("player.app"), tracker.visiblePackages())
    }

    private fun event(type: Int, packageName: String? = null, activityName: String? = null) =
        DisplayScopeUsageEvent(type, packageName, activityName)
}
