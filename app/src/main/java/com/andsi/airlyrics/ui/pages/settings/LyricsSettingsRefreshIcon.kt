package com.andsi.airlyrics.ui.pages.settings

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorSuccessIcon

/** Refresh feedback stays inside the existing 48dp action; data callbacks are never delayed. */
internal class LyricsSettingsRefreshIcon(
    val view: FrameLayout,
    private val refreshIcon: ImageView,
    private val checkIcon: ImageView,
    private val normalColor: Int,
    private val successColor: Int,
    private val refreshingDescription: String,
    private val refreshedDescription: String
) {
    private enum class Phase { IDLE, LOADING, CHECK }

    private var phase = Phase.IDLE
    private var generation = 0L
    private var startedAt = 0L
    private var pendingCompletion: Runnable? = null
    private var pendingRestore: Runnable? = null
    private var tintAnimator: ValueAnimator? = null
    private val spinner = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f).apply {
        duration = AirUiTokens.Motion.RefreshSpinMs
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
    }

    init {
        refreshIcon.imageTintList = ColorStateList.valueOf(normalColor)
        setCheckTint(normalColor)
        checkIcon.alpha = 0f
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = cancel()
        })
    }

    fun start() {
        if (!view.isAttachedToWindow) return
        generation++
        clearPending()
        refreshIcon.animate().cancel()
        checkIcon.animate().cancel()
        phase = Phase.LOADING
        startedAt = SystemClock.uptimeMillis()
        ViewCompat.setStateDescription(view, refreshingDescription)

        if (!ValueAnimator.areAnimatorsEnabled()) {
            tintAnimator?.cancel()
            tintAnimator = null
            setCheckTint(normalColor)
            spinner.cancel()
            refreshIcon.rotation = 0f
            refreshIcon.alpha = 1f
            checkIcon.alpha = 0f
            checkIcon.scaleX = 1f
            checkIcon.scaleY = 1f
            return
        }

        if (!spinner.isRunning) {
            val angle = refreshIcon.rotation
            spinner.setFloatValues(angle, angle + 360f)
            spinner.start()
        }
        if (checkIcon.alpha > 0f || refreshIcon.alpha < 1f) {
            refreshIcon.animate().alpha(1f).setDuration(AirUiTokens.Motion.FeedbackInMs)
                .setInterpolator(DecelerateInterpolator()).start()
            checkIcon.animate().alpha(0f).scaleX(1f).scaleY(1f)
                .setDuration(AirUiTokens.Motion.FeedbackInMs)
                .setInterpolator(DecelerateInterpolator()).start()
            transitionCheckTint(normalColor, AirUiTokens.Motion.FeedbackInMs)
        } else {
            refreshIcon.alpha = 1f
            checkIcon.alpha = 0f
            checkIcon.scaleX = 1f
            checkIcon.scaleY = 1f
            tintAnimator?.cancel()
            tintAnimator = null
            setCheckTint(normalColor)
        }
    }

    fun complete(readSucceeded: Boolean) {
        if (phase != Phase.LOADING) return
        if (!view.isAttachedToWindow || !readSucceeded) {
            cancel()
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            showCheckImmediately()
            return
        }

        val id = generation
        val remaining = (startedAt + AirUiTokens.Motion.RefreshSpinMs - SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        if (remaining == 0L) {
            showCheck(id)
        } else {
            pendingCompletion?.let(view::removeCallbacks)
            val task = Runnable {
                pendingCompletion = null
                if (generation == id && phase == Phase.LOADING && view.isAttachedToWindow) showCheck(id)
            }
            pendingCompletion = task
            view.postDelayed(task, remaining)
        }
    }

    fun cancel() {
        generation++
        clearPending()
        refreshIcon.animate().cancel()
        checkIcon.animate().cancel()
        tintAnimator?.cancel()
        tintAnimator = null
        spinner.cancel()
        refreshIcon.rotation = 0f
        refreshIcon.alpha = 1f
        checkIcon.alpha = 0f
        checkIcon.scaleX = 1f
        checkIcon.scaleY = 1f
        setCheckTint(normalColor)
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        phase = Phase.IDLE
        ViewCompat.setStateDescription(view, null)
    }

    private fun showCheck(id: Long) {
        if (generation != id || phase != Phase.LOADING || !view.isAttachedToWindow) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            showCheckImmediately()
            return
        }
        phase = Phase.CHECK
        ViewCompat.setStateDescription(view, refreshedDescription)
        refreshIcon.animate().cancel()
        checkIcon.animate().cancel()
        checkIcon.alpha = 0f
        checkIcon.scaleX = AirUiTokens.Motion.OptionPressScale
        checkIcon.scaleY = AirUiTokens.Motion.OptionPressScale
        transitionCheckTint(successColor, AirUiTokens.Motion.RefreshCheckInMs)
        refreshIcon.animate().alpha(0f).setDuration(AirUiTokens.Motion.RefreshCheckInMs)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (generation == id && phase == Phase.CHECK) {
                    spinner.cancel()
                    refreshIcon.rotation = 0f
                    refreshIcon.alpha = 0f
                }
            }.start()
        checkIcon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(AirUiTokens.Motion.RefreshCheckInMs)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (generation == id && phase == Phase.CHECK) scheduleRestore(id)
            }.start()
    }

    private fun showCheckImmediately() {
        phase = Phase.CHECK
        refreshIcon.animate().cancel()
        checkIcon.animate().cancel()
        tintAnimator?.cancel()
        tintAnimator = null
        spinner.cancel()
        refreshIcon.rotation = 0f
        refreshIcon.alpha = 0f
        checkIcon.alpha = 1f
        checkIcon.scaleX = 1f
        checkIcon.scaleY = 1f
        setCheckTint(successColor)
        ViewCompat.setStateDescription(view, refreshedDescription)
        scheduleRestore(generation)
    }

    private fun scheduleRestore(id: Long) {
        pendingRestore?.let(view::removeCallbacks)
        val task = Runnable {
            pendingRestore = null
            if (generation == id && phase == Phase.CHECK && view.isAttachedToWindow) restore(id)
        }
        pendingRestore = task
        view.postDelayed(task, AirUiTokens.Motion.RefreshCheckHoldMs)
    }

    private fun restore(id: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            return
        }
        transitionCheckTint(normalColor, AirUiTokens.Motion.RefreshRestoreMs)
        refreshIcon.rotation = AirUiTokens.Motion.RefreshRestoreStartRotation
        refreshIcon.animate().alpha(1f).rotation(0f).setDuration(AirUiTokens.Motion.RefreshRestoreMs)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (generation == id && phase == Phase.CHECK) {
                    tintAnimator?.cancel()
                    tintAnimator = null
                    setCheckTint(normalColor)
                    checkIcon.alpha = 0f
                    checkIcon.scaleX = 1f
                    checkIcon.scaleY = 1f
                    refreshIcon.alpha = 1f
                    refreshIcon.rotation = 0f
                    phase = Phase.IDLE
                    ViewCompat.setStateDescription(view, null)
                }
            }.start()
        checkIcon.animate().alpha(0f).setDuration(AirUiTokens.Motion.RefreshRestoreMs)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun setCheckTint(color: Int) {
        checkIcon.imageTintList = ColorStateList.valueOf(color)
    }

    private fun transitionCheckTint(toColor: Int, durationMs: Long) {
        tintAnimator?.cancel()
        val fromColor = checkIcon.imageTintList?.defaultColor ?: normalColor
        if (fromColor == toColor || !ValueAnimator.areAnimatorsEnabled()) {
            tintAnimator = null
            setCheckTint(toColor)
            return
        }
        tintAnimator = ValueAnimator.ofArgb(fromColor, toColor).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { setCheckTint(it.animatedValue as Int) }
            start()
        }
    }

    private fun clearPending() {
        pendingCompletion?.let(view::removeCallbacks)
        pendingCompletion = null
        pendingRestore?.let(view::removeCallbacks)
        pendingRestore = null
    }
}

internal fun MainUiHost.lyricsSettingsRefreshIcon(): LyricsSettingsRefreshIcon {
    val button = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.IconTouchSize), dp(AirUiTokens.Layout.IconTouchSize))
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.ui_refresh_media_status)
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
    }
    val normalColor = colorAccentText
    val refreshIcon = airIconView(R.drawable.ic_air_refresh, normalColor)
    val checkIcon = airIconView(R.drawable.ic_air_check, normalColor)
    button.addView(refreshIcon, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    ))
    button.addView(checkIcon, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    ))
    return LyricsSettingsRefreshIcon(
        button, refreshIcon, checkIcon, normalColor, colorSuccessIcon,
        getString(R.string.ui_refreshing), getString(R.string.ui_refreshed)
    )
}
