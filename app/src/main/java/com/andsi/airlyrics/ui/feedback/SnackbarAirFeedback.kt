package com.andsi.airlyrics.ui.feedback

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.Lifecycle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.feedback.AirFeedback
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.google.android.material.behavior.SwipeDismissBehavior
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

/** Activity-scoped feedback anchored above the main screen's persistent bottom bar. */
internal class SnackbarAirFeedback(
    private val activity: AppCompatActivity,
    private val anchorProvider: () -> View?,
    private val fallback: AirFeedback,
    private val canShow: () -> Boolean,
    private val paletteProvider: () -> AirLyricsPalette,
    private val manualMotionProvider: () -> Boolean = {
        needsManualSnackbarMotion(activity)
    }
) : AirFeedback {
    private data class ActiveSnackbar(
        val snackbar: Snackbar,
        val requestedDuration: Int,
        val manualMotion: Boolean,
        var timeoutTask: Runnable? = null,
        var dismissing: Boolean = false,
        var timeoutPaused: Boolean = false
    )

    private class FeedbackBehavior(
        private val onInteractionStarted: () -> Unit,
        private val onInteractionEnded: () -> Unit
    ) : BaseTransientBottomBar.Behavior() {
        override fun onInterceptTouchEvent(
            parent: CoordinatorLayout,
            child: View,
            event: MotionEvent
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (parent.isPointInChildBounds(child, event.x.toInt(), event.y.toInt())) {
                        onInteractionStarted()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onInteractionEnded()
            }
            return super.onInterceptTouchEvent(parent, child, event)
        }
    }

    private var active: ActiveSnackbar? = null

    @get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val activeSnackbar: Snackbar?
        get() = active?.snackbar

    override fun showMessage(@StringRes messageRes: Int) {
        showMessage(activity.getText(messageRes))
    }

    override fun showMessage(message: CharSequence) {
        showTransient(
            message = message,
            duration = Snackbar.LENGTH_SHORT,
            fallbackAction = { fallback.showMessage(message) }
        )
    }

    override fun showError(@StringRes messageRes: Int) {
        showError(activity.getText(messageRes))
    }

    override fun showError(message: CharSequence) {
        showTransient(
            message = message,
            duration = Snackbar.LENGTH_LONG,
            fallbackAction = { fallback.showError(message) }
        )
    }

    override fun dismiss() {
        runOnMainThread {
            dismissCurrentImmediately()
            fallback.dismiss()
        }
    }

    private fun showTransient(
        message: CharSequence,
        duration: Int,
        fallbackAction: () -> Unit
    ) {
        if (!canShow()) return

        runOnMainThread {
            if (!canShow()) return@runOnMainThread
            if (activity.isDestroyed || activity.isFinishing) return@runOnMainThread

            val anchor = availableAnchor()
            if (anchor == null) {
                dismissCurrentImmediately()
                fallbackAction()
            } else {
                fallback.dismiss()
                showSnackbar(anchor, message, duration)
            }
        }
    }

    private fun availableAnchor(): View? {
        if (activity.isDestroyed || activity.isFinishing) return null
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return null
        return anchorProvider()?.takeIf { it.isAttachedToWindow }
    }

    private fun showSnackbar(
        anchor: View,
        message: CharSequence,
        duration: Int
    ) {
        dismissCurrentImmediately()
        val palette = paletteProvider()
        val manualMotion = manualMotionProvider()
        var motionState: ActiveSnackbar? = null
        val behavior = FeedbackBehavior(
            onInteractionStarted = {
                motionState?.takeIf { it.manualMotion }?.let(::pauseManualDismiss)
            },
            onInteractionEnded = {
                motionState?.takeIf { it.manualMotion }?.let(::resumeManualDismiss)
            }
        ).apply {
            setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY)
        }
        val snackbar = Snackbar.make(
            anchor,
            message,
            if (manualMotion) Snackbar.LENGTH_INDEFINITE else duration
        )
            .setAnchorView(anchor)
            .setAnimationMode(BaseTransientBottomBar.ANIMATION_MODE_SLIDE)
            .setBackgroundTint(palette.surfaceLight)
            .setTextColor(palette.textStrong)
            .setActionTextColor(palette.accent)
            .setBehavior(behavior)
        if (manualMotion) prepareManualEnter(snackbar.view)

        lateinit var activeSnackbar: ActiveSnackbar
        activeSnackbar = ActiveSnackbar(
            snackbar = snackbar,
            requestedDuration = duration,
            manualMotion = manualMotion
        )
        motionState = activeSnackbar
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onShown(transientBottomBar: Snackbar) {
                if (active !== activeSnackbar || !activeSnackbar.manualMotion) return
                playManualEnter(activeSnackbar)
            }

            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                if (active === activeSnackbar) {
                    cancelTimeout(activeSnackbar)
                    active = null
                }
            }
        })
        active = activeSnackbar
        snackbar.show()
    }

    private fun prepareManualEnter(view: View) {
        view.alpha = 0f
        view.translationY = manualMotionOffsetPx()
        view.scaleX = MANUAL_MOTION_START_SCALE
        view.scaleY = MANUAL_MOTION_START_SCALE
    }

    private fun playManualEnter(activeSnackbar: ActiveSnackbar) {
        val view = activeSnackbar.snackbar.view
        view.animate().cancel()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(AirUiTokens.Motion.SnackbarEnterMs)
            .setInterpolator(DecelerateInterpolator())
            .withLayer()
            .withEndAction {
                if (active === activeSnackbar &&
                    !activeSnackbar.dismissing &&
                    !activeSnackbar.timeoutPaused) {
                    scheduleManualDismiss(activeSnackbar)
                }
            }
            .start()
    }

    private fun scheduleManualDismiss(activeSnackbar: ActiveSnackbar) {
        val timeoutMs = resolvedTimeoutMs(activeSnackbar.requestedDuration) ?: return
        cancelTimeout(activeSnackbar)
        val task = Runnable {
            if (active === activeSnackbar) playManualExit(activeSnackbar)
        }
        activeSnackbar.timeoutTask = task
        activeSnackbar.snackbar.view.postDelayed(task, timeoutMs)
    }

    private fun pauseManualDismiss(activeSnackbar: ActiveSnackbar) {
        activeSnackbar.timeoutPaused = true
        cancelTimeout(activeSnackbar)
    }

    private fun resumeManualDismiss(activeSnackbar: ActiveSnackbar) {
        if (!activeSnackbar.timeoutPaused) return
        activeSnackbar.timeoutPaused = false
        if (active === activeSnackbar && !activeSnackbar.dismissing) {
            scheduleManualDismiss(activeSnackbar)
        }
    }

    private fun playManualExit(activeSnackbar: ActiveSnackbar) {
        if (activeSnackbar.dismissing) return
        activeSnackbar.dismissing = true
        cancelTimeout(activeSnackbar)

        val view = activeSnackbar.snackbar.view
        if (!view.isAttachedToWindow) {
            activeSnackbar.snackbar.dismiss()
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(manualMotionOffsetPx())
            .scaleX(MANUAL_MOTION_START_SCALE)
            .scaleY(MANUAL_MOTION_START_SCALE)
            .setDuration(AirUiTokens.Motion.SnackbarExitMs)
            .setInterpolator(AccelerateInterpolator())
            .withLayer()
            .withEndAction {
                if (active === activeSnackbar) activeSnackbar.snackbar.dismiss()
            }
            .start()
    }

    private fun dismissCurrentImmediately() {
        val activeSnackbar = active ?: return
        active = null
        cancelTimeout(activeSnackbar)
        activeSnackbar.snackbar.view.animate().cancel()
        activeSnackbar.snackbar.dismiss()
    }

    private fun cancelTimeout(activeSnackbar: ActiveSnackbar) {
        activeSnackbar.timeoutTask?.let(activeSnackbar.snackbar.view::removeCallbacks)
        activeSnackbar.timeoutTask = null
    }

    private fun resolvedTimeoutMs(duration: Int): Long? {
        val baseTimeoutMs = when {
            duration == Snackbar.LENGTH_INDEFINITE -> return null
            duration == Snackbar.LENGTH_SHORT -> SNACKBAR_SHORT_TIMEOUT_MS
            duration == Snackbar.LENGTH_LONG -> SNACKBAR_LONG_TIMEOUT_MS
            duration > 0 -> duration.toLong()
            else -> SNACKBAR_LONG_TIMEOUT_MS
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return baseTimeoutMs
        val accessibilityManager = activity.getSystemService(AccessibilityManager::class.java)
            ?: return baseTimeoutMs
        return accessibilityManager.getRecommendedTimeoutMillis(
            baseTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            AccessibilityManager.FLAG_CONTENT_TEXT
        ).toLong()
    }

    private fun manualMotionOffsetPx(): Float {
        return AirUiTokens.Space.CardH * activity.resources.displayMetrics.density
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            activity.runOnUiThread(action)
        }
    }

    private companion object {
        const val MANUAL_MOTION_START_SCALE = 0.96f
        const val SNACKBAR_SHORT_TIMEOUT_MS = 1_500L
        const val SNACKBAR_LONG_TIMEOUT_MS = 2_750L
    }
}

private fun needsManualSnackbarMotion(activity: AppCompatActivity): Boolean {
    if (!ValueAnimator.areAnimatorsEnabled()) return false
    val accessibilityManager = activity.getSystemService(AccessibilityManager::class.java)
        ?: return false
    if (accessibilityManager.isTouchExplorationEnabled) return false
    // Material disables Snackbar motion for every spoken-feedback service. Some vendor voice
    // assistants advertise that capability without acting as a screen reader, so cover only that
    // false-positive case while still respecting disabled animators and touch exploration.
    return accessibilityManager.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_SPOKEN
    ).isNotEmpty()
}
