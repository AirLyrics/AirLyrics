package com.andsi.airlyrics.ui.model

import android.media.session.MediaController
import com.andsi.airlyrics.core.model.LyricsSearchSource

internal data class MediaPageState(
    val controllers: List<MediaController>,
    val selectedPackage: String?,
    val selectedController: MediaController?
)

internal data class CurrentMediaUiInfo(
    val displayText: String,
    val isEmpty: Boolean = false
)

internal enum class LyricsDeleteMode { PLAIN, KARAOKE, ALL }

internal data class CurrentLyricsUiState(
    val media: CurrentMediaUiInfo?,
    val localSourceText: String?,
    val plainLyricsTitle: String?,
    val plainLyricsDownloaded: Boolean,
    val hasPlainLyrics: Boolean,
    val canRemoveAllLyrics: Boolean,
    val localWordByWord: Boolean,
    val karaokeEnabled: Boolean,
    val offsetMs: Long
)

internal data class LocalLyricsUiItem(
    val name: String,
    val modifiedTimeMillis: Long,
    val sizeBytes: Long,
    val title: String = "",
    val artist: String = "",
    val source: String = "",
    val provider: String = "local",
    val hasPlainLyrics: Boolean = true,
    val hasKaraokeLyrics: Boolean = false,
    val displayTitle: String = title.ifBlank { friendlyNameFromFileName(name) },
    val subtitle: String = "",
    val typeText: String = "",
    val metaText: String = ""
)

internal data class RecentLyricsUiState(
    val currentItem: LocalLyricsUiItem?,
    val recentLyrics: List<LocalLyricsUiItem>,
    val media: CurrentMediaUiInfo?
)

internal data class LyricsSettingsUiState(
    val selectedSource: LyricsSearchSource,
    val sourceOptions: List<LyricsSearchSource>,
    val autoSearchOnline: Boolean,
    val autoSaveLocal: Boolean,
    val lyricsDirectoryPath: String
)

internal data class LanguageOptionUiItem(
    val mode: String,
    val title: String,
    val subtitle: String
)

internal data class LanguageSettingsUiState(
    val displayName: String,
    val currentMode: String,
    val options: List<LanguageOptionUiItem>
)

private fun friendlyNameFromFileName(fileName: String): String {
    return fileName
        .substringAfterLast('/')
        .removeSuffix(".karaoke.json")
        .removeSuffix(".lrc")
        .replace(Regex("\\s*\\[[0-9a-fA-F]{8}]$"), "")
        .replace('_', ' ')
        .trim()
        .ifBlank { fileName }
}
