package com.andsi.airlyrics.app.lifecycle

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.lyrics.importer.LyricsFormatCatalog

/**
 * Owns ActivityResult launchers for MainActivity.
 *
 * This keeps Android callback registration out of MainActivity while preserving
 * the existing handwritten UI and import/permission behavior.
 */
internal class MainLaunchers(
    activity: AppCompatActivity,
    private val onLyricsFileResult: (Uri?) -> Unit,
    private val onFloatingFontFileResult: (Uri?) -> Unit,
    private val onLyricsDirectorySelected: (Uri) -> Unit,
    private val onNotificationPermissionResult: (Boolean) -> Unit
) {
    private val lyricsDocumentMimeTypes = LyricsFormatCatalog.pickerMimeTypes()

    private val lyricsFileLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onLyricsFileResult(uri)
        }

    private val floatingFontMimeTypes = arrayOf(
        "font/ttf",
        "font/otf",
        "application/font-sfnt",
        "application/x-font-ttf",
        "application/x-font-truetype",
        "application/x-font-otf",
        "application/x-font-opentype",
        "application/vnd.ms-opentype",
        "application/octet-stream"
    )

    private val floatingFontFileLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onFloatingFontFileResult(uri)
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

    fun selectFloatingFontFile() {
        floatingFontFileLauncher.launch(floatingFontMimeTypes)
    }

    fun selectLyricsDirectory() {
        lyricsDirectoryLauncher.launch(null)
    }

    fun requestNotificationPermission(permission: String) {
        notificationPermissionLauncher.launch(permission)
    }
}
