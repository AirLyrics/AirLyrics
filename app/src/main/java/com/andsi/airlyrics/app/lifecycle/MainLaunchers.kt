package com.andsi.airlyrics.app.lifecycle

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Owns ActivityResult launchers for MainActivity.
 *
 * This keeps Android callback registration out of MainActivity while preserving
 * the existing handwritten UI and import/permission behavior.
 */
internal class MainLaunchers(
    activity: AppCompatActivity,
    private val onLyricsFileSelected: (Uri) -> Unit,
    private val onLyricsDirectorySelected: (Uri) -> Unit,
    private val onNotificationPermissionResult: (Boolean) -> Unit
) {
    private val lyricsDocumentMimeTypes = arrayOf(
        "*/*",
        "application/x-lrc",
        "application/lrc",
        "text/lrc",
        "text/plain",
        "text/*",
        "application/octet-stream"
    )

    private val lyricsFileLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onLyricsFileSelected(uri)
        }

    private val lyricsDirectoryLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onLyricsDirectorySelected(uri)
        }

    private val notificationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onNotificationPermissionResult(granted)
        }

    fun selectLyricsFile() {
        lyricsFileLauncher.launch(lyricsDocumentMimeTypes)
    }

    fun selectLyricsDirectory() {
        lyricsDirectoryLauncher.launch(null)
    }

    fun requestNotificationPermission(permission: String) {
        notificationPermissionLauncher.launch(permission)
    }
}
