package com.andsi.airlyrics.ui.feedback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.andsi.airlyrics.settings.store.AppSettingsStore

/** Toast feedback for interactions owned by a Service or another non-Activity surface. */
internal class ToastAirFeedback(context: Context) : AirFeedback {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentToast: Toast? = null

    override fun showMessage(@StringRes messageRes: Int) {
        showMessage(appContext.getText(messageRes))
    }

    override fun showMessage(message: CharSequence) {
        show(message, Toast.LENGTH_SHORT)
    }

    override fun showError(@StringRes messageRes: Int) {
        showError(appContext.getText(messageRes))
    }

    override fun showError(message: CharSequence) {
        show(message, Toast.LENGTH_LONG)
    }

    override fun showAction(
        message: CharSequence,
        actionLabel: CharSequence,
        onAction: () -> Unit
    ): Boolean = false

    override fun dismiss() {
        runOnMainThread {
            currentToast?.cancel()
            currentToast = null
        }
    }

    private fun show(message: CharSequence, duration: Int) {
        if (AppSettingsStore.areStatusPopupsMuted(appContext)) return

        runOnMainThread {
            if (AppSettingsStore.areStatusPopupsMuted(appContext)) return@runOnMainThread
            currentToast?.cancel()
            currentToast = Toast.makeText(appContext, message, duration).also(Toast::show)
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
