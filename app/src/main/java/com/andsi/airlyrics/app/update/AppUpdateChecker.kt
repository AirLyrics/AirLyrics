package com.andsi.airlyrics.app.update

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.ui.model.AppUpdateCheckStatus
import java.io.IOException
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppUpdateChecker(
    context: Context,
    versionName: String = BuildConfig.VERSION_NAME,
    private val now: () -> Long = System::currentTimeMillis,
    private val latestRelease: suspend (AppUpdateVersion) -> AppUpdateRelease? = GitHubAppUpdateSource::latestRelease
) {
    private val installedVersion = AppUpdateVersion.parse(versionName)
    private val app = context.applicationContext
    private val preferences by lazy { app.getSharedPreferences("app_update_check", Context.MODE_PRIVATE) }
    private val checkLock = Mutex()
    private val mutableAvailableUpdate = MutableStateFlow<AppUpdateRelease?>(null)
    val availableUpdate = mutableAvailableUpdate.asStateFlow()
    private val mutableCheckStatus = MutableStateFlow(AppUpdateCheckStatus.Unchecked)
    val checkStatus = mutableCheckStatus.asStateFlow()

    fun startAutomaticChecks(owner: LifecycleOwner): Job = owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) delay(checkIfDue().milliseconds)
        }
    }

    suspend fun checkNow(): Result<AppUpdateRelease?> = withContext(Dispatchers.IO) {
        checkLock.withLock {
            publishCachedRelease()
            refresh()
        }
    }

    internal suspend fun checkIfDue(): Long = withContext(Dispatchers.IO) {
        checkLock.withLock {
            publishCachedRelease()
            val remaining = preferences.getLong(NEXT_CHECK, 0) - now()
            if (remaining in 1..CHECK_INTERVAL_MS) return@withLock remaining
            if (refresh().isSuccess) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS
        }
    }

    private fun publishCachedRelease() {
        val current = installedVersion ?: return
        val version = preferences.getString(VERSION, null)?.let(AppUpdateVersion::parse)
        mutableAvailableUpdate.value = version?.takeIf { it > current }?.let {
            AppUpdateRelease.create(
                it,
                preferences.getLong(SIZE, 0),
                preferences.getString(SHA256, null).orEmpty(),
                preferences.getString(PUBLISHED_ON, null)?.let { publishedOn ->
                    runCatching { LocalDate.parse(publishedOn) }.getOrNull()
                },
                preferences.getString(NOTES, null).orEmpty()
            )
        }
        if (mutableCheckStatus.value == AppUpdateCheckStatus.Unchecked && preferences.contains(NEXT_CHECK)) {
            mutableCheckStatus.value = if (preferences.getBoolean(CHECK_FAILED, false)) AppUpdateCheckStatus.Failed
                else AppUpdateCheckStatus.Complete
        }
    }

    private suspend fun refresh(): Result<AppUpdateRelease?> {
        val previous = mutableCheckStatus.value
        mutableCheckStatus.value = AppUpdateCheckStatus.Checking
        try {
            return fetchAndCache()
        } finally {
            if (mutableCheckStatus.value == AppUpdateCheckStatus.Checking) mutableCheckStatus.value = previous
        }
    }

    private suspend fun fetchAndCache(): Result<AppUpdateRelease?> {
        val result = try {
            val current = installedVersion ?: throw IOException("Unsupported installed version name")
            Result.success(latestRelease(current)?.takeIf { it.version > current })
        } catch (failure: IOException) {
            Result.failure(failure)
        }
        currentCoroutineContext().ensureActive()

        preferences.edit {
            val interval = if (result.isSuccess) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS
            putLong(NEXT_CHECK, now() + interval)
            putBoolean(CHECK_FAILED, result.isFailure)
            if (result.isSuccess) {
                val release = result.getOrThrow()
                if (release == null) {
                    remove(VERSION)
                    remove(SIZE)
                    remove(SHA256)
                    remove(PUBLISHED_ON)
                    remove(NOTES)
                } else {
                    putString(VERSION, release.version.toString())
                    putLong(SIZE, release.sizeBytes)
                    putString(SHA256, release.sha256)
                    putString(PUBLISHED_ON, release.publishedOn?.toString())
                    putString(NOTES, release.notes)
                }
            }
        }
        if (result.isSuccess) mutableAvailableUpdate.value = result.getOrThrow()
        mutableCheckStatus.value = if (result.isSuccess) AppUpdateCheckStatus.Complete else AppUpdateCheckStatus.Failed
        return result
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val RETRY_INTERVAL_MS = 60 * 60 * 1000L
        const val NEXT_CHECK = "next_check_at"
        const val VERSION = "version"
        const val SIZE = "size"
        const val SHA256 = "sha256"
        const val PUBLISHED_ON = "published_on"
        const val NOTES = "notes"
        const val CHECK_FAILED = "check_failed"
    }
}
