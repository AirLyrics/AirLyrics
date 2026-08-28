package com.andsi.airlyrics.ui.feedback

import android.os.Looper
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.behavior.SwipeDismissBehavior
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

/** Activity-scoped feedback anchored above the main screen's persistent bottom bar. */
internal class SnackbarAirFeedback(
    private val activity: AppCompatActivity,
    private val anchorProvider: () -> View?,
    private val fallback: AirFeedback
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

    override fun showAction(
        message: CharSequence,
        actionLabel: CharSequence,
        onAction: () -> Unit
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (AppSettingsStore.areStatusPopupsMuted(activity)) return false
        val anchor = availableAnchor() ?: return false

        fallback.dismiss()
        showSnackbar(anchor, message, Snackbar.LENGTH_LONG) {
            setAction(actionLabel) { onAction() }
        }
        return true
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
        if (AppSettingsStore.areStatusPopupsMuted(activity)) return

        runOnMainThread {
            if (AppSettingsStore.areStatusPopupsMuted(activity)) return@runOnMainThread
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
        return anchorProvider()?.takeIf(ViewCompat::isAttachedToWindow)
    }

    private fun showSnackbar(
        anchor: View,
        message: CharSequence,
        duration: Int,
        configure: (Snackbar.() -> Unit)? = null
    ) {
        currentSnackbar?.dismiss()
        val palette = AirLyricsTheme.palette(
            isDark = ThemeSettingsStore.isDark(activity),
            accent = ThemeSettingsStore.getAccent(activity)
        )
        val snackbar = Snackbar.make(anchor, message, duration)
            .setAnchorView(anchor)
            .setBackgroundTint(palette.surfaceLight)
            .setTextColor(palette.textStrong)
            .setActionTextColor(palette.accent)
            .setBehavior(BaseTransientBottomBar.Behavior().apply {
                setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY)
            })
        configure?.invoke(snackbar)
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
