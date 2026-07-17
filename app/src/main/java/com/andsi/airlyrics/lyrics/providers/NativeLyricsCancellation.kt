package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken

internal object LyricsNativeCancellation {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    external fun cancelLookup(lookupId: Long)

    external fun clearLookup(lookupId: Long)
}

internal fun <T> withNativeLyricsCancellation(
    token: LyricsLookupCancellationToken?,
    block: (Long) -> T
): T {
    val lookupId = token?.nativeLookupId ?: 0L
    val cancellationRegistration = token?.invokeOnCancellation {
        if (lookupId > 0L) {
            LyricsNativeCancellation.cancelLookup(lookupId)
        }
    }

    return try {
        block(lookupId)
    } finally {
        cancellationRegistration?.dispose()
        if (lookupId > 0L) {
            LyricsNativeCancellation.clearLookup(lookupId)
        }
    }
}
