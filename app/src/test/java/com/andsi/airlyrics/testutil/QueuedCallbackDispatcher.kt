package com.andsi.airlyrics.testutil

import com.andsi.airlyrics.lyrics.LyricsLookupCallbackDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class QueuedCallbackDispatcher : LyricsLookupCallbackDispatcher {
    private val deliveries = LinkedBlockingQueue<() -> Unit>()

    override fun dispatch(block: () -> Unit) { deliveries.put(block) }

    fun takeDelivery(): () -> Unit = requireNotNull(deliveries.poll(5, TimeUnit.SECONDS)) {
        "Timed out waiting for a completed lookup to queue its callback"
    }

    fun pendingDeliveryCount(): Int = deliveries.size
}
