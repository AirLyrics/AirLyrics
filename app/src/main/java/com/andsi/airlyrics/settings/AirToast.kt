package com.andsi.airlyrics.settings

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.andsi.airlyrics.settings.store.AppSettingsStore

/** App-level Toast wrapper that respects the user's hide-toast preference. */
object AirToast {
    fun showShort(context: Context, @StringRes messageRes: Int) {
        show(context, messageRes, Toast.LENGTH_SHORT)
    }

    fun showLong(context: Context, message: CharSequence) {
        if (AppSettingsStore.isToasterMuted(context)) return
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun showLong(context: Context, @StringRes messageRes: Int) {
        show(context, messageRes, Toast.LENGTH_LONG)
    }

    private fun show(context: Context, @StringRes messageRes: Int, duration: Int) {
        if (AppSettingsStore.isToasterMuted(context)) return
        Toast.makeText(context, messageRes, duration).show()
    }
}
