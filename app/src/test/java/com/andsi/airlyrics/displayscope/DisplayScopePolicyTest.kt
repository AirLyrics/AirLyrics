package com.andsi.airlyrics.displayscope

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScopePolicyTest {
    @Test
    fun disabledFilterAlwaysAllowsDisplay() {
        val decision = DisplayScopePolicy.decide(
            enabled = false,
            usageAccessGranted = false,
            visibilitySnapshotAvailable = false,
            selectedPackages = emptySet(),
            visiblePackages = emptySet()
        )

        assertTrue(decision.allowsDisplay)
        assertNull(decision.blockReason)
    }

    @Test
    fun enabledFilterFailsClosedWithoutUsageAccess() {
        val decision = DisplayScopePolicy.decide(
            enabled = true,
            usageAccessGranted = false,
            visibilitySnapshotAvailable = false,
            selectedPackages = setOf("player.app"),
            visiblePackages = setOf("player.app")
        )

        assertFalse(decision.allowsDisplay)
        assertEquals(DisplayScopeBlockReason.USAGE_ACCESS_REQUIRED, decision.blockReason)
    }

    @Test
    fun enabledFilterWaitsForFirstVisibilitySnapshot() {
        val decision = DisplayScopePolicy.decide(
            enabled = true,
            usageAccessGranted = true,
            visibilitySnapshotAvailable = false,
            selectedPackages = setOf("player.app"),
            visiblePackages = emptySet()
        )

        assertFalse(decision.allowsDisplay)
        assertEquals(DisplayScopeBlockReason.CHECKING_SELECTED_APPS, decision.blockReason)
    }

    @Test
    fun anyVisibleSelectedAppAllowsDisplay() {
        val decision = DisplayScopePolicy.decide(
            enabled = true,
            usageAccessGranted = true,
            visibilitySnapshotAvailable = true,
            selectedPackages = setOf("lyrics.app", "player.app"),
            visiblePackages = setOf("launcher.app", "player.app")
        )

        assertTrue(decision.allowsDisplay)
    }

    @Test
    fun knownVisibilityWithoutSelectedAppWaitsForSelectionMatch() {
        val decision = DisplayScopePolicy.decide(
            enabled = true,
            usageAccessGranted = true,
            visibilitySnapshotAvailable = true,
            selectedPackages = setOf("player.app"),
            visiblePackages = setOf("launcher.app")
        )

        assertFalse(decision.allowsDisplay)
        assertEquals(DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP, decision.blockReason)
    }

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

    private fun event(type: Int, packageName: String? = null, activityName: String? = null) =
        DisplayScopeUsageEvent(type, packageName, activityName)
}
