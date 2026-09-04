package com.andsi.airlyrics.displayscope

import android.os.Build

internal object DisplayScopeCapability {
    fun isSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.Q
    }
}
