package com.andsi.airlyrics.ui.async

import com.andsi.airlyrics.ui.model.MainRuntimeHost
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestUiTaskRunnerTest {
    @Test
    fun submit_deliversCompletedTaskWhenItIsLatest() {
        val runtime = RecordingRuntimeHost()
        val runner = LatestUiTaskRunner()
        val delivered = mutableListOf<String>()

        runner.submit(
            runtime = runtime,
            load = { "first" },
            deliver = delivered::add
        )
        runtime.runNextIo()
        runtime.runNextMain()

        assertEquals(listOf("first"), delivered)
    }

    @Test
    fun submit_skipsOlderTaskWhenNewerTaskWasSubmitted() {
        val runtime = RecordingRuntimeHost()
        val runner = LatestUiTaskRunner()
        val delivered = mutableListOf<String>()

        runner.submit(
            runtime = runtime,
            load = { "older" },
            deliver = delivered::add
        )
        runner.submit(
            runtime = runtime,
            load = { "newer" },
            deliver = delivered::add
        )
        runtime.runNextIo()
        runtime.runNextIo()
        runtime.runNextMain()
        runtime.runNextMain()

        assertEquals(listOf("newer"), delivered)
    }

    private class RecordingRuntimeHost : MainRuntimeHost {
        private val ioTasks = ArrayDeque<() -> Unit>()
        private val mainTasks = ArrayDeque<() -> Unit>()

        override fun runOnAppIo(block: () -> Unit) {
            ioTasks.addLast(block)
        }

        override fun runOnMainThread(block: () -> Unit) {
            mainTasks.addLast(block)
        }

        override fun showMessage(messageRes: Int) = Unit

        fun runNextIo() {
            ioTasks.removeFirst().invoke()
        }

        fun runNextMain() {
            mainTasks.removeFirst().invoke()
        }
    }
}
