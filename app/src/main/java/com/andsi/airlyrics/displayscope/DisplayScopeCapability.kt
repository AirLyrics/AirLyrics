package com.andsi.airlyrics.displayscope

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

internal object DisplayScopeCapability {
    fun isSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= Build.VERSION_CODES.Q
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }
}
