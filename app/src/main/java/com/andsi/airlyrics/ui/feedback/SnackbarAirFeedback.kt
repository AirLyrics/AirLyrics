package com.andsi.airlyrics.ui.feedback

import android.os.Looper
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
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
    private val paletteProvider: () -> AirLyricsPalette
) : AirFeedback {
    private var currentSnackbar: Snackbar? = null

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
            currentSnackbar?.dismiss()
            currentSnackbar = null
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
                currentSnackbar?.dismiss()
                currentSnackbar = null
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
        currentSnackbar?.dismiss()
        val palette = paletteProvider()
        val snackbar = Snackbar.make(anchor, message, duration)
            .setAnchorView(anchor)
            .setBackgroundTint(palette.surfaceLight)
            .setTextColor(palette.textStrong)
            .setActionTextColor(palette.accent)
            .setBehavior(BaseTransientBottomBar.Behavior().apply {
                setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY)
            })
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                if (currentSnackbar === transientBottomBar) {
                    currentSnackbar = null
                }
            }
        })
        currentSnackbar = snackbar
        snackbar.show()
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            activity.runOnUiThread(action)
        }
    }
}
