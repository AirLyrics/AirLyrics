package com.andsi.airlyrics.floating

/** Process-local snapshot of the latest actual state reported by the floating service. */
internal object FloatingWindowRuntimeState {
    @Volatile
    private var latestState: FloatingWindowStateBroadcast.State? = null

    fun update(state: FloatingWindowStateBroadcast.State) {
        latestState = state
    }

    fun snapshot(): FloatingWindowStateBroadcast.State? = latestState
}
