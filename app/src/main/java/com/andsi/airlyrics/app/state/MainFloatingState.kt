package com.andsi.airlyrics.app.state

/** Narrow state boundary used by floating-window coordination. */
internal interface MainFloatingState {
    val locked: Boolean
    val clickThrough: Boolean
    val quickFloatingVisible: Boolean
    val quickFloatingDesiredVisible: Boolean
    val overlayPermissionGranted: Boolean

    fun updateFloatingState(
        visible: Boolean? = null,
        desiredVisible: Boolean? = null,
        overlayGranted: Boolean? = null,
        locked: Boolean? = null,
        clickThrough: Boolean? = null
    )
}
