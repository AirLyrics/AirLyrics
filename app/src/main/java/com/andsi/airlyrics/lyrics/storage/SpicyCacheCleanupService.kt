package com.andsi.airlyrics.lyrics.storage

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Periodic physical cleanup; reads and active displays independently enforce the deadline. */
class SpicyCacheCleanupService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = executor.submit {
            runCatching { LyricsStorage.pruneOnlineCache(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(true); return true }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
