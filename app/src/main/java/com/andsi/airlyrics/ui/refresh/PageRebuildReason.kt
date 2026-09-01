package com.andsi.airlyrics.ui.refresh

/** Documents why the handwritten UI has to discard and rebuild the current page. */
internal enum class PageRebuildReason {
    PAGE_NAVIGATION,
    SETTINGS_NAVIGATION,
    PERMISSION_CHANGED,
    LYRICS_DIRECTORY_CHANGED,
    MEDIA_CONTENT_CHANGED,
    FLOATING_STRUCTURE_CHANGED
}
