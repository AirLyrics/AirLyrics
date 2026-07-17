package com.andsi.airlyrics.ui.async

import com.andsi.airlyrics.ui.model.MainRuntimeHost
import java.util.concurrent.atomic.AtomicInteger

internal class LatestUiTaskRunner {
    private val generation = AtomicInteger(0)

    fun <T> submit(
        runtime: MainRuntimeHost,
        load: () -> T,
        deliver: (T) -> Unit
    ) {
        val taskGeneration = generation.incrementAndGet()
        runtime.runOnAppIo {
            val result = load()
            runtime.runOnMainThread {
                if (taskGeneration == generation.get()) {
                    deliver(result)
                }
            }
        }
    }
}
