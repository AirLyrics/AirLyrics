package com.andsi.airlyrics.floating

internal object FloatingWindowRuntimeState {
    @Volatile
    private var latestState: FloatingWindowStateBroadcast.State? = null

    fun update(state: FloatingWindowStateBroadcast.State) {
        latestState = state
    }

    fun snapshot(): FloatingWindowStateBroadcast.State? = latestState
}
