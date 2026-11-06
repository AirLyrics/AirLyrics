package com.andsi.airlyrics.ui.state

import android.app.Dialog
import android.view.WindowManager

/** Activity-owned windows and snapshot callbacks; never retained in a ViewModel. */
internal class InteractionUiRegistry {
    private val windows = linkedMapOf<Dialog, () -> Unit>()
    private val snapshots = linkedMapOf<Any, () -> Unit>()
    var releasing = false
        private set

    /** Register before show so themed dialogs and sheets inherit the shade on their first frame. */
    fun register(dialog: Dialog, closeImmediately: () -> Unit) {
        dialog.window?.let { window ->
            if (window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                window.setDimAmount(maxOf(window.attributes.dimAmount, maxShowingDimAmount()))
            }
        }
        windows[dialog] = closeImmediately
    }
    fun forget(dialog: Dialog) { windows.remove(dialog) }

    /** A new modal must not replace an existing window's shade with a lighter one. */
    fun maxShowingDimAmount(): Float = windows.keys.asSequence()
        .filter { it.isShowing }
        .mapNotNull { it.window?.attributes }
        .filter { it.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 }
        .maxOfOrNull { it.dimAmount } ?: 0f

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
