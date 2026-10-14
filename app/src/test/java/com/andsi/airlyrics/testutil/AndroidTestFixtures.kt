package com.andsi.airlyrics.testutil

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowLooper

internal fun descendants(view: View): List<View> = listOf(view) +
    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    else emptyList()

/** Real I/O completes on worker threads; only UI waits are allowed to drain the main queue. */
internal fun awaitUiCondition(message: String = "Timed out waiting for UI work", condition: () -> Boolean) {
    awaitCondition(message, drainMain = true, condition)
}

internal fun awaitBackgroundCondition(condition: () -> Boolean) {
    awaitCondition("Timed out waiting for background work", drainMain = false, condition)
}

private fun awaitCondition(message: String, drainMain: Boolean, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (drainMain) ShadowLooper.idleMainLooper()
        if (condition()) return
        Thread.sleep(10)
    }
    if (drainMain) ShadowLooper.idleMainLooper()
    assertTrue(message, condition())
}

internal fun resetLyricsStorage(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
}
