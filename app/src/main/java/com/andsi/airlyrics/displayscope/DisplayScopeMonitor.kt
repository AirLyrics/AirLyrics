package com.andsi.airlyrics.displayscope

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
    private inner class PollTask(val expectedGeneration: Int) : Runnable {
        override fun run() {
            poll(this)
        }
    }

    private val appContext = context.applicationContext ?: context
    private val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("AirLyrics-DisplayScope").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private val tracker = VisibleActivityTracker()
    private val displayStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    val task = activePollTask ?: return
                    workerHandler.post { handleDisplayAvailabilityChanged(task) }
                }
            }
        }
    }

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    @Volatile
    private var activePollTask: PollTask? = null

    private var displayStateReceiverRegistered = false
    private var pausedForUnavailableDisplay = false
    private var lastQueryEndMs = 0L

    fun start() {
        if (running) return
        running = true
        val currentGeneration = ++generation
        val task = PollTask(currentGeneration)
        activePollTask = task
        registerDisplayStateReceiver()
        workerHandler.post {
            if (!isActive(task)) return@post
            tracker.clear()
            lastQueryEndMs = 0L
            pausedForUnavailableDisplay = false
            poll(task)
        }
    }

    fun stop() {
        running = false
        generation += 1
        val task = activePollTask
        activePollTask = null
        unregisterDisplayStateReceiver()
        if (task != null) {
            workerHandler.removeCallbacks(task)
            workerHandler.post { workerHandler.removeCallbacks(task) }
        }
    }

    fun close() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }

    private fun poll(task: PollTask) {
        if (!isActive(task)) return
        if (!isDisplayAvailable()) {
            pauseForUnavailableDisplay(task)
            return
        }
        pausedForUnavailableDisplay = false

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
            } else {
                DisplayScopeVisibilitySnapshot(true, tracker.visiblePackages())
            }
        }

        if (!isDisplayAvailable()) {
            pauseForUnavailableDisplay(task)
            return
        }

        publishSnapshot(task, snapshot)
        if (!isActive(task)) return
        workerHandler.postDelayed(task, POLL_INTERVAL_MS)
    }

    private fun handleDisplayAvailabilityChanged(task: PollTask) {
        if (!isActive(task)) return
        if (!isDisplayAvailable()) {
            pauseForUnavailableDisplay(task)
        } else if (pausedForUnavailableDisplay) {
            pausedForUnavailableDisplay = false
            workerHandler.removeCallbacks(task)
            poll(task)
        }
    }

    private fun pauseForUnavailableDisplay(task: PollTask) {
        workerHandler.removeCallbacks(task)
        if (!isActive(task) || pausedForUnavailableDisplay) return

        pausedForUnavailableDisplay = true
        tracker.clear()
        lastQueryEndMs = System.currentTimeMillis()
        val snapshot = DisplayScopeVisibilitySnapshot(
            usageAccessGranted = PermissionHelper.hasUsageStatsAccess(appContext),
            visiblePackages = emptySet()
        )
        publishSnapshot(task, snapshot)
    }

    private fun publishSnapshot(task: PollTask, snapshot: DisplayScopeVisibilitySnapshot) {
        mainHandler.post {
            if (isActive(task)) onSnapshot(snapshot)
        }
    }

    private fun isActive(task: PollTask): Boolean {
        return running &&
            generation == task.expectedGeneration &&
            activePollTask === task
    }

    private fun isDisplayAvailable(): Boolean {
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    private fun registerDisplayStateReceiver() {
        if (displayStateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            appContext,
            displayStateReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        displayStateReceiverRegistered = true
    }

    private fun unregisterDisplayStateReceiver() {
        if (!displayStateReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(displayStateReceiver) }
        displayStateReceiverRegistered = false
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
