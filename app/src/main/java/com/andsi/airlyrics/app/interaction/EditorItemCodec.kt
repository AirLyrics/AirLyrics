package com.andsi.airlyrics.app.interaction

import android.os.Bundle
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem

internal fun LocalLyricsUiItem.toEditorBundle(): Bundle = Bundle().apply {
    putString("name", name)
    putLong("modifiedTimeMillis", modifiedTimeMillis)
    putLong("sizeBytes", sizeBytes)
    putString("title", title)
    putString("artist", artist)
    putString("album", album)
    putLong("durationMs", durationMs)
    putString("indexKey", indexKey)
    putString("source", source)
    putString("provider", provider)
    putBoolean("hasPlainLyrics", hasPlainLyrics)
    putBoolean("hasWordByWordLyrics", hasWordByWordLyrics)
    putBoolean("canDelete", canDelete)
    putString("displayTitle", displayTitle)
    putString("subtitle", subtitle)
    putString("typeText", typeText)
    putString("metaText", metaText)
}

internal fun Bundle.toEditorItem(): LocalLyricsUiItem = LocalLyricsUiItem(
    name = getString("name").orEmpty(),
    modifiedTimeMillis = getLong("modifiedTimeMillis"),
    sizeBytes = getLong("sizeBytes"),
    title = getString("title").orEmpty(),
    artist = getString("artist").orEmpty(),
    album = getString("album").orEmpty(),
    durationMs = getLong("durationMs"),
    indexKey = getString("indexKey").orEmpty(),
    source = getString("source").orEmpty(),
    provider = getString("provider").orEmpty(),
    hasPlainLyrics = getBoolean("hasPlainLyrics"),
    hasWordByWordLyrics = getBoolean("hasWordByWordLyrics"),
    canDelete = getBoolean("canDelete"),
    displayTitle = getString("displayTitle").orEmpty(),
    subtitle = getString("subtitle").orEmpty(),
    typeText = getString("typeText").orEmpty(),
    metaText = getString("metaText").orEmpty(),
)
