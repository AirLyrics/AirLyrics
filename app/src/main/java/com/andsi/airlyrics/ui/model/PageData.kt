package com.andsi.airlyrics.ui.model

import android.media.session.MediaController
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource

internal data class MediaPageState(
    val controllers: List<MediaController>,
    val selectedPackage: String?,
    val selectedController: MediaController?
)

internal data class CurrentMediaUiInfo(
    val displayText: String,
    val isEmpty: Boolean = false
)

internal enum class LyricsDeleteMode { PLAIN, WORD_BY_WORD, ALL }

internal data class CurrentLyricsUiState(
    val media: CurrentMediaUiInfo?,
    val localSourceText: String?,
    val plainLyricsTitle: String?,
    val plainLyricsDownloaded: Boolean,
    val hasPlainLyrics: Boolean,
    val canRemoveAllLyrics: Boolean,
    val hasLocalWordByWordLyrics: Boolean,
    val wordByWordLyricsEnabled: Boolean,
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
    val hasWordByWordLyrics: Boolean = false,
    val displayTitle: String,
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
    val selectedPlainLyricsSource: PlainLyricsSearchSource,
    val plainLyricsSourceOptions: List<PlainLyricsSearchSource>,
    val autoSearchOnline: Boolean,
    val autoSaveLocal: Boolean,
    val lyricsDirectoryPath: String
)

internal data class LanguageOptionUiItem(
    val mode: String,
    val title: String
)

internal data class LanguageSettingsUiState(
    val displayName: String,
    val currentMode: String,
    val options: List<LanguageOptionUiItem>
)
