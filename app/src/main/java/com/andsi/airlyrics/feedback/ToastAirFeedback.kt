package com.andsi.airlyrics.feedback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/** Toast feedback whose visibility policy is supplied by the owning surface. */
internal class ToastAirFeedback(
    context: Context,
    private val canShow: () -> Boolean
) : AirFeedback {
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

    override fun dismiss() {
        runOnMainThread {
            currentToast?.cancel()
            currentToast = null
        }
    }

    private fun show(message: CharSequence, duration: Int) {
        if (!canShow()) return

        runOnMainThread {
            if (!canShow()) return@runOnMainThread
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
