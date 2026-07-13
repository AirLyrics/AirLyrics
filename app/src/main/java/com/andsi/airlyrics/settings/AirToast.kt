package com.andsi.airlyrics.settings

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.andsi.airlyrics.settings.store.AppSettingsStore

/** App-level Toast wrapper that respects the user's hide-toast preference. */
object AirToast {
    fun showShort(context: Context, message: CharSequence) {
        show(context, message, Toast.LENGTH_SHORT)
    }

    fun showShort(context: Context, @StringRes messageRes: Int) {
        showShort(context, context.getText(messageRes))
    }

    fun showLong(context: Context, message: CharSequence) {
        show(context, message, Toast.LENGTH_LONG)
    }

    fun showLong(context: Context, @StringRes messageRes: Int) {
        showLong(context, context.getText(messageRes))
    }

    private fun show(context: Context, message: CharSequence, duration: Int) {
        if (AppSettingsStore.isToasterMuted(context)) return
        Toast.makeText(context, message, duration).show()
    }
}
