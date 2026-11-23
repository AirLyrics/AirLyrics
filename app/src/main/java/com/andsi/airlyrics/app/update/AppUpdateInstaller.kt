package com.andsi.airlyrics.app.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.core.content.IntentSanitizer
import androidx.lifecycle.Lifecycle
import com.andsi.airlyrics.BuildConfig
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface AppUpdateInstallResult {
    data object Disabled : AppUpdateInstallResult
    data object Busy : AppUpdateInstallResult
    data class PermissionRequired(val settingsIntent: Intent) : AppUpdateInstallResult
    data object Cancelled : AppUpdateInstallResult
    data object Installed : AppUpdateInstallResult
    data class Failed(val cause: Exception) : AppUpdateInstallResult
}

internal object AppUpdateInstaller {
    private val installLock = Mutex()

    suspend fun install(
        activity: ComponentActivity,
        release: AppUpdateRelease
    ): AppUpdateInstallResult = withContext(Dispatchers.Main.immediate) {
        if (!BuildConfig.APP_UPDATE_INSTALLER_ENABLED) return@withContext AppUpdateInstallResult.Disabled
        if (!installLock.tryLock()) return@withContext AppUpdateInstallResult.Busy
        try {
            requireForeground(activity)
            val manager = activity.packageManager
            if (!manager.canRequestPackageInstalls()) {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", activity.packageName, null)
                )
                AppUpdateInstallResult.PermissionRequired(settings.setComponent(systemHandler(manager, settings)))
            } else {
                stageAndConfirm(activity, release)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppUpdateInstallResult.Failed(failure)
        } finally {
            installLock.unlock()
        }
    }

    private suspend fun stageAndConfirm(
        activity: ComponentActivity,
        release: AppUpdateRelease
    ): AppUpdateInstallResult {
        val app = activity.applicationContext
        val installer = app.packageManager.packageInstaller
        val responses = Channel<InstallResponse>(Channel.BUFFERED)
        var sessionId = -1
        var statusIntent: PendingIntent? = null
        var handedOff = false
        try {
            AppUpdateApk.useVerifiedApk(app, release, allowDownload = false) { apk ->
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(release.sizeBytes)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                    }
                }
                val id = installer.createSession(params)
                sessionId = id
                pendingResponses[id] = responses

                // PackageInstaller uses this explicit callback for both confirmation and the final result.
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(
                    app,
                    id,
                    Intent(app, AppUpdateInstallReceiver::class.java).setAction(INSTALL_STATUS),
                    flags
                )
                statusIntent = pending
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, release.sizeBytes).use { output ->
                        apk.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        session.fsync(output)
                    }
                    currentCoroutineContext().ensureActive()
                    session.commit(pending.intentSender)
                }
            }
            var result = withTimeoutOrNull(30.seconds) { responses.receive() }
                ?: throw IOException("The package installer did not respond")
            if (result.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                requireForeground(activity)
                activity.startActivity(systemConfirmation(activity.packageManager, result.confirmation, sessionId))
                handedOff = true
                result = withTimeoutOrNull(5.minutes) {
                    var next = responses.receive()
                    while (next.status == PackageInstaller.STATUS_PENDING_USER_ACTION) next = responses.receive()
                    next
                } ?: throw IOException("The package installer did not report a result")
            }
            return when (result.status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    handedOff = true
                    AppUpdateInstallResult.Installed
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdateInstallResult.Cancelled
                else -> throw IOException("Package installer returned status ${result.status}")
            }
        } finally {
            pendingResponses.remove(sessionId)
            statusIntent?.cancel()
            responses.close()
            if (!handedOff && sessionId >= 0) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { installer.abandonSession(sessionId) }
                }
            }
        }
    }

    private fun requireForeground(activity: ComponentActivity) {
        if (activity.isFinishing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            throw IOException("Installation requires a foreground activity")
        }
    }

    private fun systemConfirmation(manager: PackageManager, intent: Intent?, sessionId: Int): Intent {
        if (intent?.action != CONFIRM_INSTALL || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) {
            throw IOException("Invalid package installer confirmation")
        }
        val component = systemHandler(manager, intent)
        return IntentSanitizer.Builder()
            .allowComponent(component)
            .allowPackage(component.packageName)
            .allowAction(CONFIRM_INSTALL)
            .allowExtra(PackageInstaller.EXTRA_SESSION_ID, Int::class.javaObjectType) { it == sessionId }
            .allowExtra("android.content.pm.extra.RESOLVED_BASE_PATH", String::class.java)
            .build()
            .sanitizeByFiltering(Intent(intent).setComponent(component))
    }

    private fun systemHandler(manager: PackageManager, intent: Intent): ComponentName {
        val handler = manager.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
            ?: throw ActivityNotFoundException("No system handler for ${intent.action}")
        return ComponentName(handler.packageName, handler.name)
    }
}

internal class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != INSTALL_STATUS) return
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val response = pendingResponses[id] ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        response.trySend(InstallResponse(status, confirmation))
    }
}

private data class InstallResponse(val status: Int, val confirmation: Intent?)

private const val INSTALL_STATUS = "com.andsi.airlyrics.app.update.INSTALL_STATUS"
private const val CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
private val pendingResponses = ConcurrentHashMap<Int, Channel<InstallResponse>>()
