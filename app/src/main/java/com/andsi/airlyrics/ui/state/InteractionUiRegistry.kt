package com.andsi.airlyrics.ui.state

import android.app.Dialog

/** Activity-owned windows and snapshot callbacks; never retained in a ViewModel. */
internal class InteractionUiRegistry {
    private val windows = linkedMapOf<Dialog, () -> Unit>()
    private val snapshots = linkedMapOf<Any, () -> Unit>()
    var releasing = false
        private set

    fun register(dialog: Dialog, closeImmediately: () -> Unit) { windows[dialog] = closeImmediately }
    fun forget(dialog: Dialog) { windows.remove(dialog) }
    fun snapshot(key: Any, capture: () -> Unit) { snapshots[key] = capture }
    fun forgetSnapshot(key: Any) { snapshots.remove(key) }
    fun capture() { snapshots.values.toList().forEach { it() } }

    fun releaseWindows() {
        capture()
        releasing = true
        try { windows.values.toList().forEach { it() } } finally {
            windows.clear()
            releasing = false
        }
    }

    fun destroy() {
        releaseWindows()
        snapshots.clear()
    }
}
