package com.andsi.airlyrics.app.update

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class AppUpdateBackendTest {
    private val installedVersion = AppUpdateVersion(1, 2, 9)
    private val release = requireNotNull(AppUpdateRelease.create(
        AppUpdateVersion(1, 2, 10), 3, SHA256, LocalDate.of(2026, 11, 11), "### Fixed"
    ))

    @Test
    fun latestReleaseComparesNumericVersionsAndSelectsStandardApk() {
        assertEquals(release, GitHubAppUpdateSource.parseLatestRelease(metadata(), installedVersion))
        assertNull(GitHubAppUpdateSource.parseLatestRelease(metadata(), release.version))
        assertNull(GitHubAppUpdateSource.parseLatestRelease(metadata(), AppUpdateVersion(1, 3, 0)))
    }

    @Test
    fun prereleaseAndMissingDigestCannotProduceAnUpdate() {
        assertThrows(IOException::class.java) {
            GitHubAppUpdateSource.parseLatestRelease(metadata(prerelease = true), installedVersion)
        }
        assertThrows(IOException::class.java) {
            GitHubAppUpdateSource.parseLatestRelease(metadata(digest = ""), installedVersion)
        }
    }

    @Test
    fun automaticCheckSharesCachedAvailabilityAndRetriesAfterFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var time = 1_000L
        var calls = 0
        var offline = false
        var latest: AppUpdateRelease? = release
        fun checker(version: String = installedVersion.toString()) = AppUpdateChecker(context, version, { time }) {
            calls++
            if (offline) throw IOException("Offline")
            latest
        }

        val first = checker()
        assertEquals(DAY_MS, first.checkIfDue())
        assertEquals(release, first.availableUpdate.value)

        time += 1_000L
        val reopened = checker()
        assertEquals(DAY_MS - 1_000L, reopened.checkIfDue())
        assertEquals(release, reopened.availableUpdate.value)
        assertEquals(1, calls)

        val updated = checker(release.version.toString())
        updated.checkIfDue()
        assertNull(updated.availableUpdate.value)

        offline = true
        assertTrue(reopened.checkNow().isFailure)
        assertEquals(AppUpdateCheckStatus.Failed, reopened.checkStatus.value)
        assertEquals(release, reopened.availableUpdate.value)
        assertEquals(HOUR_MS, reopened.checkIfDue())
        assertEquals(2, calls)

        time += HOUR_MS
        offline = false
        latest = null
        assertEquals(DAY_MS, reopened.checkIfDue())
        assertNull(reopened.availableUpdate.value)
        assertEquals(3, calls)
    }

    @Test
    fun apkDownloadRequiresExactSizeAndChecksum() = runBlocking {
        val bytes = "abc".toByteArray()
        val output = ByteArrayOutputStream()

        assertTrue(verifyApkContents(bytes.inputStream(), release, output))
        assertArrayEquals(bytes, output.toByteArray())
        assertFalse(verifyApkContents("abd".byteInputStream(), release))
        assertFalse(verifyApkContents("ab".byteInputStream(), release))
        assertFalse(verifyApkContents("abcd".byteInputStream(), release))
    }

    @Test
    fun archiveMustBeANewerVersionOfTheSameSignedPackage() {
        val installed = packageInfo("com.andsi.airlyrics", 20, "1.2.9", "01")
        verifyUpdatePackage(installed, packageInfo("com.andsi.airlyrics", 21, "1.2.10", "01"), release)

        assertThrows(IOException::class.java) {
            verifyUpdatePackage(installed, packageInfo("other.app", 21, "1.2.10", "01"), release)
        }
        assertThrows(IOException::class.java) {
            verifyUpdatePackage(installed, packageInfo("com.andsi.airlyrics", 20, "1.2.10", "01"), release)
        }
        assertThrows(IOException::class.java) {
            verifyUpdatePackage(installed, packageInfo("com.andsi.airlyrics", 21, "1.2.10", "02"), release)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun installationPermissionAndPrivateReceiverAreOptIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS
        )
        val hasPermission = Manifest.permission.REQUEST_INSTALL_PACKAGES in installed.requestedPermissions.orEmpty()
        val receiver = installed.receivers?.singleOrNull { it.name == AppUpdateInstallReceiver::class.java.name }

        assertEquals(BuildConfig.APP_UPDATE_INSTALLER_ENABLED, hasPermission)
        assertEquals(BuildConfig.APP_UPDATE_INSTALLER_ENABLED, receiver != null)
        assertFalse(receiver?.exported == true)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageId: String, code: Int, version: String, signingKey: String) =
        PackageInfo().apply {
            packageName = packageId
            versionCode = code
            versionName = version
            signatures = arrayOf(Signature(signingKey))
        }

    private fun metadata(prerelease: Boolean = false, digest: String = "sha256:$SHA256"): String =
        """
        {
          "tag_name": "v1.2.10",
          "published_at": "2026-11-11T04:54:00Z",
          "body": "### Fixed",
          "draft": false,
          "prerelease": $prerelease,
          "assets": [
            {"name": "AirLyrics-v1.2.10-debug.apk", "state": "uploaded", "size": 1, "digest": "sha256:$SHA256"},
            {"name": "AirLyrics-v1.2.10.apk", "state": "uploaded", "size": 3, "digest": "$digest"}
          ]
        }
        """.trimIndent()

    private companion object {
        const val SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
