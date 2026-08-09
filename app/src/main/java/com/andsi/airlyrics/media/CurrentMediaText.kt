package com.andsi.airlyrics.media

import com.andsi.airlyrics.media.model.CurrentMediaInfo

internal val CurrentMediaInfo.displayText: String
    get() = if (artist.isNotBlank()) {
        "$title - $artist"
    } else {
        title
    }
