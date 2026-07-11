package com.andsi.airlyrics.i18n

import android.content.Context
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Context.localizedOffsetDescription(offsetMs: Long): String {
    val value = LyricsOffsetStore.formatOffset(offsetMs).removePrefix("+").removePrefix("-")
    return when {
        offsetMs > 0L -> getString(R.string.lyrics_offset_advance, value)
        offsetMs < 0L -> getString(R.string.lyrics_offset_delay, value)
        else -> getString(R.string.ui_no_offset)
    }
}

internal fun Context.localizedProviderName(providerIdOrName: String): String {
    return when (providerIdOrName.trim().lowercase(Locale.ROOT)) {
        "local", "local lyrics" -> getString(R.string.provider_local_lyrics)
        "netease", "netease lyrics", "netease cloud music", "\u7f51\u6613\u4e91\u6b4c\u8bcd", "\u7f51\u6613\u4e91\u97f3\u4e50" -> getString(R.string.provider_netease_lyrics)
        "musixmatch" -> getString(R.string.provider_musixmatch)
        else -> providerIdOrName.ifBlank { getString(R.string.provider_local_lyrics) }
    }
}

internal fun Context.localizedLocalLyricsSource(source: String, provider: String): String {
    return when (source) {
        LyricsStorage.SOURCE_MANUAL_IMPORT -> getString(R.string.ui_import)
        LyricsStorage.SOURCE_KARAOKE_FALLBACK -> getString(R.string.ui_generated_from_enhanced_lrc)
        LyricsStorage.SOURCE_DOWNLOADED -> {
            val providerName = localizedProviderName(provider)
            if (provider.isBlank() || provider.trim().equals("local", ignoreCase = true)) {
                getString(R.string.ui_cache)
            } else {
                getString(R.string.local_source_cache_provider, providerName)
            }
        }
        LyricsStorage.SOURCE_LEGACY -> getString(R.string.ui_local)
        else -> getString(R.string.ui_local)
    }
}

internal fun Context.localizedLocalLyricsSource(info: LyricsStorage.LocalLyricsInfo): String {
    return localizedLocalLyricsSource(info.source, info.provider)
}

internal fun Context.localizedLocalLyricsSubtitle(item: LyricsStorage.LocalLyricsItem): String {
    val artistPart = item.artist.ifBlank { getString(R.string.ui_unknown_artist) }
    return artistPart + " · " + localizedLocalLyricsSource(item.source, item.provider)
}

internal fun Context.localizedLocalLyricsType(item: LyricsStorage.LocalLyricsItem): String {
    return when {
        item.hasKaraokeLyrics -> getString(R.string.ui_enhanced)
        item.hasPlainLyrics -> getString(R.string.ui_plain)
        else -> getString(R.string.ui_unknown_type)
    }
}

internal fun Context.localizedLocalLyricsMeta(item: LyricsStorage.LocalLyricsItem): String {
    val dateText = if (item.modifiedTimeMillis > 0L) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.modifiedTimeMillis))
    } else {
        getString(R.string.ui_unknown_time)
    }

    val sizeText = when {
        item.sizeBytes >= 1024 * 1024 -> "%.1f MB".format(item.sizeBytes / 1024f / 1024f)
        item.sizeBytes >= 1024 -> "%.1f KB".format(item.sizeBytes / 1024f)
        item.sizeBytes > 0 -> "${item.sizeBytes} B"
        else -> getString(R.string.ui_unknown_size)
    }

    return "$dateText · $sizeText"
}

internal fun Context.localizedLyricsLookupMessage(error: LyricsLookupException): String {
    val providerName = localizedProviderName(error.providerId.ifBlank { error.providerName })
    return when (error.errorType) {
        LyricsLookupErrorType.NotFound -> getString(R.string.lyrics_lookup_not_found, providerName)
        LyricsLookupErrorType.NeedCredential -> getString(R.string.lyrics_lookup_need_credentials, providerName)
        LyricsLookupErrorType.RateLimited -> getString(R.string.lyrics_lookup_rate_limited, providerName)
        LyricsLookupErrorType.RestrictedLyrics -> getString(R.string.lyrics_lookup_restricted, providerName)
        LyricsLookupErrorType.NetworkError -> getString(R.string.lyrics_lookup_network_failed, providerName)
        LyricsLookupErrorType.ParseError -> getString(R.string.lyrics_lookup_parse_failed, providerName)
        LyricsLookupErrorType.NativeError -> getString(R.string.lyrics_lookup_native_error, providerName)
        LyricsLookupErrorType.Unknown -> getString(R.string.lyrics_lookup_failed, providerName)
    }
}
