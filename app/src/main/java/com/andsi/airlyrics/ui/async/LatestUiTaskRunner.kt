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
        val uiGeneration = runtime.currentUiGeneration()
        runtime.runOnAppIo {
            val result = load()
            runtime.runOnStartedUi(uiGeneration) {
                if (taskGeneration == generation.get()) {
                    deliver(result)
                }
            }
        }
    }
}
