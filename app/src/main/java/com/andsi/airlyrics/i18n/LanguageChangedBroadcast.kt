package com.andsi.airlyrics.i18n

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Refreshes running services when AppCompat changes the app language on Android 12L and below. */
internal object LanguageChangedBroadcast {
    private const val ACTION_LANGUAGE_CHANGED = "com.andsi.airlyrics.LANGUAGE_CHANGED"

    fun filter() = IntentFilter(ACTION_LANGUAGE_CHANGED)

    fun intent(context: Context) = Intent(ACTION_LANGUAGE_CHANGED).setPackage(context.packageName)
}
