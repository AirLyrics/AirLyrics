package com.andsi.airlyrics.ui.model

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

internal interface AppUpdateUi {
    val availableUpdate: StateFlow<AppUpdateReleaseInfo?>
    val activeRelease: AppUpdateReleaseInfo?
    val checkStatus: StateFlow<AppUpdateCheckStatus>
    val state: StateFlow<AppUpdateDownloadState>

    fun checkNow()
    fun download(release: AppUpdateReleaseInfo)
    fun install(release: AppUpdateReleaseInfo)
    fun reset()
}

internal interface AppUpdateReleaseInfo {
    val versionName: String
    val publishedOn: LocalDate?
    val notes: String
}

internal enum class AppUpdateCheckStatus { Unchecked, Checking, Complete, Failed }

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
