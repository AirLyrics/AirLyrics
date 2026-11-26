package com.andsi.airlyrics.floating

internal enum class AutoHideReason {
    PAUSED,
    LYRICS_UNAVAILABLE
}

internal enum class LyricsAvailability {
    UNKNOWN,
    LOADING,
    AVAILABLE,
    UNAVAILABLE
}

internal data class AutoHideConditions(
    val desiredVisible: Boolean,
    val isPlaying: Boolean?,
    val hideWhenPaused: Boolean,
    val hideWhenLyricsUnavailable: Boolean,
)

internal class FloatingAutoHideState(private val uptimeMillis: () -> Long) {
    private val pause = DelayedHide(AUTO_HIDE_WHEN_PAUSED_DELAY_MS)
    private val unavailable = DelayedHide(AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS)
    private var pauseSuppressed = false
    private var suppressedLyricsKey: PlaybackLyricsKey? = null
    private var lyricsKey: PlaybackLyricsKey? = null

    var lyricsAvailability = LyricsAvailability.UNKNOWN
        private set

    val shouldHide: Boolean get() = pause.active || unavailable.active

    val activeReasons: Set<AutoHideReason>
        get() = buildSet {
            if (pause.active) add(AutoHideReason.PAUSED)
            if (unavailable.active) add(AutoHideReason.LYRICS_UNAVAILABLE)
        }

    val nextDeadlineUptimeMs: Long?
        get() = listOfNotNull(pause.deadlineUptimeMs, unavailable.deadlineUptimeMs).minOrNull()

    fun setLyricsAvailability(availability: LyricsAvailability, key: PlaybackLyricsKey? = null) {
        if (lyricsKey != key) suppressedLyricsKey = null
        lyricsKey = key
        lyricsAvailability = availability
    }

    fun evaluate(conditions: AutoHideConditions) {
        if (!conditions.desiredVisible || (conditions.isPlaying == null)) {
            resetVisibility()
            return
        }

        val now = uptimeMillis()
        if (conditions.hideWhenPaused && !conditions.isPlaying) {
            if (!pauseSuppressed) pause.request(now)
        } else {
            pause.reset()
            pauseSuppressed = false
        }

        when {
            !conditions.hideWhenLyricsUnavailable ||
                lyricsAvailability == LyricsAvailability.UNKNOWN ||
                lyricsAvailability == LyricsAvailability.AVAILABLE -> {
                unavailable.reset()
                suppressedLyricsKey = null
            }
            // Keep a hidden window hidden while looking up the replacement song.
            lyricsAvailability == LyricsAvailability.LOADING -> unavailable.cancelDelay()
            lyricsKey == suppressedLyricsKey -> unavailable.reset()
            else -> unavailable.request(now)
        }
    }

    fun suppressForManualShow(conditions: AutoHideConditions, playbackKey: PlaybackLyricsKey?) {
        if (conditions.hideWhenPaused && (pause.active || conditions.isPlaying == false)) {
            pauseSuppressed = true
            pause.reset()
        }
        if (conditions.hideWhenLyricsUnavailable && playbackKey != null &&
            (unavailable.active || lyricsAvailability == LyricsAvailability.UNAVAILABLE)
        ) {
            suppressedLyricsKey = playbackKey
            unavailable.reset()
        }
    }

    fun resetVisibility() {
        pause.reset()
        unavailable.reset()
        pauseSuppressed = false
        suppressedLyricsKey = null
    }

    private class DelayedHide(private val delayMs: Long) {
        var deadlineUptimeMs: Long? = null
            private set
        var active = false
            private set

        fun request(now: Long) {
            if (active) return
            val deadline = deadlineUptimeMs ?: (now + delayMs)
            active = now >= deadline
            deadlineUptimeMs = deadline.takeUnless { active }
        }

        fun cancelDelay() {
            deadlineUptimeMs = null
        }

        fun reset() {
            active = false
            cancelDelay()
        }
    }
}

internal const val AUTO_HIDE_WHEN_PAUSED_DELAY_MS = 400L
internal const val AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS = 3_000L
