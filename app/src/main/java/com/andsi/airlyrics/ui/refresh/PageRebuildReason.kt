package com.andsi.airlyrics.ui.refresh

/** Documents why the handwritten UI has to discard and rebuild the current page. */
internal enum class PageRebuildReason {
    INITIAL_RENDER,
    PAGE_NAVIGATION,
    SETTINGS_NAVIGATION,
    BACK_NAVIGATION,
    THEME_CHANGED,
    LANGUAGE_CHANGED,
    PERMISSION_CHANGED,
    LYRICS_DIRECTORY_CHANGED,
    LYRICS_CHANGED,
    MEDIA_CONTENT_CHANGED,
    FLOATING_STRUCTURE_CHANGED
}
