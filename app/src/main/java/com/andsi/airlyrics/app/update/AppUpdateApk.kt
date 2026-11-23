package com.andsi.airlyrics.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppUpdateIncompatibleException(message: String) : IOException(message)
internal class AppUpdateDownloadMissingException : IOException("The verified update is no longer cached")

internal object AppUpdateApk {
    private val apkLock = Mutex()

    suspend fun useVerifiedApk(
        context: Context,
        release: AppUpdateRelease,
        onProgress: (Int) -> Unit = {},
        allowDownload: Boolean = true,
        useApk: suspend (File) -> Unit
    ) = withContext(Dispatchers.IO) {
        apkLock.withLock {
            val directory = File(context.cacheDir, "app-updates")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the update cache")

            val apk = File(directory, "update.apk")
            val partial = File(directory, "update-partial.apk")
            try {
                val cached = apk.isFile && apk.length() == release.sizeBytes &&
                    apk.inputStream().use { verifyApkContents(it, release) }
                if (!cached) {
                    if (!allowDownload) throw AppUpdateDownloadMissingException()
                    partial.outputStream().use { output -> downloadVerifiedApkContents(release, output, onProgress) }
                }

                val verified = if (cached) apk else partial
                verifyArchive(context, verified, release)
                currentCoroutineContext().ensureActive()
                if (!cached) {
                    Files.move(
                        verified.toPath(),
                        apk.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                partial.delete()
            }
            onProgress(100)
            useApk(apk)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(context: Context, file: File, release: AppUpdateRelease) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val manager = context.packageManager
        val installed = try {
            manager.getPackageInfo(context.packageName, flags)
        } catch (failure: PackageManager.NameNotFoundException) {
            throw IOException("Installed package cannot be verified", failure)
        }
        val candidate = manager.getPackageArchiveInfo(file.path, flags)
            ?: throw AppUpdateIncompatibleException("The file is not a valid Android package")
        verifyUpdatePackage(installed, candidate, release)
    }
}

private suspend fun downloadVerifiedApkContents(
    release: AppUpdateRelease,
    output: OutputStream? = null,
    onProgress: (Int) -> Unit
) {
    GitHubAppUpdateSource.download(release) { connection ->
        val length = connection.contentLengthLong
        if (length >= 0 && length != release.sizeBytes) throw IOException("Unexpected APK download size")
        val valid = connection.inputStream.use { verifyApkContents(it, release, output, onProgress) }
        if (!valid) throw IOException("APK checksum or size mismatch")
    }
}

// File and network callers run inside useVerifiedApk on Dispatchers.IO.
@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun verifyApkContents(
    input: InputStream,
    release: AppUpdateRelease,
    output: OutputStream? = null,
    onProgress: (Int) -> Unit = {}
): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var downloaded = 0L
    var lastProgress = -1
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        downloaded += count
        if (downloaded > release.sizeBytes) return false

        digest.update(buffer, 0, count)
        output?.write(buffer, 0, count)
        val progress = (downloaded * 100 / release.sizeBytes).toInt().coerceAtMost(99)
        if (progress != lastProgress) {
            lastProgress = progress
            onProgress(progress)
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    return downloaded == release.sizeBytes && sha256 == release.sha256
}

internal fun verifyUpdatePackage(installed: PackageInfo, candidate: PackageInfo, release: AppUpdateRelease) {
    if (candidate.packageName != installed.packageName) throw AppUpdateIncompatibleException("APK package name mismatch")
    if (candidate.versionName != release.version.toString()) throw AppUpdateIncompatibleException("APK version does not match the release")
    if (PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed)) {
        throw AppUpdateIncompatibleException("APK version code is not newer than the installed version")
    }

    val installedSigners = currentSigners(installed)
    val candidateSigners = currentSigners(candidate)
    if (installedSigners.isEmpty() || candidateSigners.isEmpty()) throw AppUpdateIncompatibleException("Missing APK signing certificate")
    if (candidateSigners == installedSigners) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && installedSigners.size == 1 && candidateSigners.size == 1 &&
        candidate.signingInfo?.signingCertificateHistory?.contains(installedSigners.single()) == true
    ) {
        return
    }
    throw AppUpdateIncompatibleException("APK signing certificate does not match the installed app")
}

@Suppress("DEPRECATION")
private fun currentSigners(info: PackageInfo): Set<Signature> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
    } else {
        info.signatures?.toSet().orEmpty()
    }
