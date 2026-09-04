package com.andsi.airlyrics.displayscope

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.andsi.airlyrics.app.platform.PermissionHelper

internal data class DisplayScopeVisibilitySnapshot(
    val usageAccessGranted: Boolean,
    val visiblePackages: Set<String>
)

internal data class DisplayScopeUsageEvent(
    val type: Int,
    val packageName: String?,
    val activityName: String?
)

/** Reconstructs the set of visible activities from UsageEvents on Android 10+. */
internal class VisibleActivityTracker {
    private data class ActivityKey(val packageName: String, val activityName: String)

    private val visibleActivities = linkedSetOf<ActivityKey>()
    private var screenAvailable = true

    fun accept(event: DisplayScopeUsageEvent) {
        when (event.type) {
            UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.KEYGUARD_SHOWN -> {
                screenAvailable = false
                visibleActivities.clear()
            }

            UsageEvents.Event.SCREEN_INTERACTIVE,
            UsageEvents.Event.KEYGUARD_HIDDEN -> screenAvailable = true

            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                if (!screenAvailable) return
                event.activityKey()?.let(visibleActivities::add)
            }

            UsageEvents.Event.ACTIVITY_STOPPED -> {
                event.activityKey()?.let(visibleActivities::remove)
            }
        }
    }

    fun visiblePackages(): Set<String> {
        if (!screenAvailable) return emptySet()
        return visibleActivities.mapTo(linkedSetOf(), ActivityKey::packageName)
    }

    fun clear() {
        visibleActivities.clear()
        screenAvailable = true
    }

    private fun DisplayScopeUsageEvent.activityKey(): ActivityKey? {
        val packageName = packageName?.takeIf(String::isNotBlank) ?: return null
        return ActivityKey(packageName, activityName?.takeIf(String::isNotBlank) ?: packageName)
    }
}

/** Polls UsageEvents off the service main thread and publishes best-effort visibility snapshots. */
internal class DisplayScopeMonitor(
    context: Context,
    private val onSnapshot: (DisplayScopeVisibilitySnapshot) -> Unit
) {
    private val appContext = context.applicationContext ?: context
    private val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("AirLyrics-DisplayScope").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val tracker = VisibleActivityTracker()

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    private var lastQueryEndMs = 0L

    fun start() {
        if (running) return
        running = true
        val currentGeneration = ++generation
        workerHandler.post {
            tracker.clear()
            lastQueryEndMs = 0L
            poll(currentGeneration)
        }
    }

    fun stop() {
        running = false
        generation += 1
    }

    fun close() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }

    private fun poll(expectedGeneration: Int) {
        if (!running || generation != expectedGeneration) return

        val now = System.currentTimeMillis()
        val hasAccess = PermissionHelper.hasUsageStatsAccess(appContext)
        val snapshot = if (!hasAccess) {
            tracker.clear()
            lastQueryEndMs = 0L
            DisplayScopeVisibilitySnapshot(false, emptySet())
        } else {
            val queryStart = if (lastQueryEndMs == 0L) {
                val timeSinceBoot = SystemClock.elapsedRealtime().coerceAtMost(INITIAL_LOOKBACK_MS)
                now - timeSinceBoot
            } else {
                (lastQueryEndMs - EVENT_QUERY_OVERLAP_MS).coerceAtLeast(0L)
            }
            val eventsRead = runCatching {
                readEvents(queryStart, now)
            }.isSuccess
            lastQueryEndMs = now

            if (!eventsRead) {
                tracker.clear()
                lastQueryEndMs = 0L
                DisplayScopeVisibilitySnapshot(false, emptySet())
            } else if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                tracker.clear()
                DisplayScopeVisibilitySnapshot(true, emptySet())
            } else {
                DisplayScopeVisibilitySnapshot(true, tracker.visiblePackages())
            }
        }

        mainHandler.post {
            if (running && generation == expectedGeneration) onSnapshot(snapshot)
        }
        workerHandler.postDelayed(
            { poll(expectedGeneration) },
            POLL_INTERVAL_MS
        )
    }

    private fun readEvents(beginTimeMs: Long, endTimeMs: Long) {
        val events = usageStatsManager.queryEvents(beginTimeMs, endTimeMs)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            tracker.accept(
                DisplayScopeUsageEvent(
                    type = event.eventType,
                    packageName = event.packageName,
                    activityName = event.className
                )
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 750L
        const val EVENT_QUERY_OVERLAP_MS = 1_000L
        const val INITIAL_LOOKBACK_MS = 24L * 60L * 60L * 1_000L
    }
}
