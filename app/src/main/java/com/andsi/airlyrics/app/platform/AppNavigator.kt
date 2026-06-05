package com.andsi.airlyrics.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object AppNavigator {
    fun openUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
