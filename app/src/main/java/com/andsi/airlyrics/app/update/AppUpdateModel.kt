package com.andsi.airlyrics.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

internal sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data class Progress(val percent: Int) : AppUpdateDownloadState
    data object Failed : AppUpdateDownloadState
    data object Ready : AppUpdateDownloadState
    data object Installing : AppUpdateDownloadState
    data object PermissionRequired : AppUpdateDownloadState
    data object InstallFailed : AppUpdateDownloadState
    data object Incompatible : AppUpdateDownloadState
    data object Installed : AppUpdateDownloadState
}

internal class AppUpdateModel(
    app: Context,
    private val checker: AppUpdateChecker = AppUpdateChecker(app),
    private val prepareApk: suspend (AppUpdateRelease, (Int) -> Unit) -> Unit = { release, progress ->
        AppUpdateApk.useVerifiedApk(app.applicationContext, release, progress) {}
    },
    private val installApk: suspend (ComponentActivity, AppUpdateRelease) -> AppUpdateInstallResult =
        AppUpdateInstaller::install
) : ViewModel() {
    val availableUpdate = checker.availableUpdate
    val checkStatus = checker.checkStatus
    private val mutableState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    val state = mutableState.asStateFlow()
    var activeRelease: AppUpdateRelease? = null
        private set

    private var check: Job? = null
    private var operation: Job? = null
    private var generation = 0L
    private var permissionRelease: AppUpdateRelease? = null

    fun bind(activity: ComponentActivity) {
        checker.startAutomaticChecks(activity)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                val release = permissionRelease ?: return
                permissionRelease = null
                if (activity.packageManager.canRequestPackageInstalls()) install(activity, release)
            }
        })
    }

    @MainThread
    fun checkNow() {
        if (check?.isActive == true || checkStatus.value == AppUpdateCheckStatus.Checking) return
        check = viewModelScope.launch { checker.checkNow() }
    }

    @MainThread
    fun download(release: AppUpdateRelease) {
        if (operation?.isActive == true) return
        activeRelease = release
        mutableState.value = AppUpdateDownloadState.Progress(0)
        operation = viewModelScope.launch {
            try {
                channelFlow<Int> {
                    prepareApk(release) { if (it < 100) trySend(it) }
                }.conflate().collect { mutableState.value = AppUpdateDownloadState.Progress(it) }
                ensureActive()
                mutableState.value = AppUpdateDownloadState.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                mutableState.value = if (failure is AppUpdateIncompatibleException) AppUpdateDownloadState.Incompatible
                    else AppUpdateDownloadState.Failed
            }
        }
    }

    @MainThread
    fun install(activity: ComponentActivity, release: AppUpdateRelease) {
        if (activeRelease != release || operation?.isActive == true) return
        when (state.value) {
            AppUpdateDownloadState.Ready, AppUpdateDownloadState.InstallFailed, AppUpdateDownloadState.PermissionRequired -> Unit
            else -> return
        }

        val currentGeneration = generation
        mutableState.value = AppUpdateDownloadState.Installing
        operation = activity.lifecycleScope.launch {
            try {
                val result = installApk(activity, release)
                ensureActive()
                mutableState.value = when (result) {
                    AppUpdateInstallResult.Installed -> AppUpdateDownloadState.Installed
                    AppUpdateInstallResult.Cancelled -> AppUpdateDownloadState.Ready
                    AppUpdateInstallResult.Disabled, AppUpdateInstallResult.Busy -> AppUpdateDownloadState.InstallFailed
                    is AppUpdateInstallResult.Failed -> when (result.cause) {
                        is AppUpdateDownloadMissingException -> AppUpdateDownloadState.Failed
                        is AppUpdateIncompatibleException -> AppUpdateDownloadState.Incompatible
                        else -> AppUpdateDownloadState.InstallFailed
                    }
                    is AppUpdateInstallResult.PermissionRequired -> {
                        permissionRelease = release
                        try {
                            activity.startActivity(result.settingsIntent)
                            AppUpdateDownloadState.PermissionRequired
                        } catch (_: ActivityNotFoundException) {
                            permissionRelease = null
                            AppUpdateDownloadState.InstallFailed
                        } catch (_: SecurityException) {
                            permissionRelease = null
                            AppUpdateDownloadState.InstallFailed
                        }
                    }
                }
            } finally {
                if (generation == currentGeneration && mutableState.value == AppUpdateDownloadState.Installing) {
                    mutableState.value = AppUpdateDownloadState.Ready
                }
            }
        }
    }

    @MainThread
    fun reset() {
        generation++
        permissionRelease = null
        operation?.cancel()
        operation = null
        activeRelease = null
        mutableState.value = AppUpdateDownloadState.Idle
    }
}
