package com.andsi.airlyrics.app.state

/** Mutable floating-window state exposed to floating-window controllers. */
internal interface MainFloatingState {
    var locked: Boolean
    var clickThrough: Boolean
    var quickFloatingVisible: Boolean
    var overlayPermissionGranted: Boolean
}
