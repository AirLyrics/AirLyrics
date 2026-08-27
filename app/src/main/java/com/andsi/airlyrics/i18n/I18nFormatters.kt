package com.andsi.airlyrics.i18n

import android.content.Context
import androidx.annotation.StringRes
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Context.localizedOffsetDescription(offsetMs: Long): String {
    if (offsetMs == 0L) return getString(R.string.ui_no_offset)

    val value = "%.2fs".format(Locale.getDefault(), kotlin.math.abs(offsetMs) / 1000f)
    val messageRes = if (offsetMs > 0L) R.string.lyrics_offset_advance else R.string.lyrics_offset_delay
    return getString(messageRes, value)
}

internal fun Context.localizedPlainLyricsProviderName(plainProviderIdOrName: String): String {
    val providerRes = when (plainProviderIdOrName.trim().lowercase(Locale.ROOT)) {
        "local", "local lyrics" -> R.string.provider_local_plain_lyrics
        "netease", "netease lyrics", "netease cloud music", "\u7f51\u6613\u4e91\u6b4c\u8bcd", "\u7f51\u6613\u4e91\u97f3\u4e50" -> R.string.provider_netease_plain_lyrics
        "musixmatch" -> R.string.provider_musixmatch
        else -> null
    }
    return providerRes?.let { getString(it) }
        ?: plainProviderIdOrName.ifBlank { getString(R.string.provider_local_plain_lyrics) }
}

internal fun Context.localizedLocalPlainLyricsSource(plainSource: String, plainProvider: String): String {
    if (plainSource == LyricsStorage.SOURCE_DOWNLOADED) {
        val providerName = localizedPlainLyricsProviderName(plainProvider)
        return if (plainProvider.isBlank() || plainProvider.trim().equals("local", ignoreCase = true)) {
            getString(R.string.ui_cache)
        } else {
            getString(R.string.local_source_cache_provider, providerName)
        }
    }

    val sourceRes = when (plainSource) {
        LyricsStorage.SOURCE_MANUAL_IMPORT -> R.string.ui_import
        LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK -> R.string.ui_generated_from_word_by_word_lyrics
        else -> R.string.ui_local
    }
    return getString(sourceRes)
}

internal fun Context.localizedLocalPlainLyricsSource(info: LyricsStorage.LocalPlainLyricsInfo): String {
    return localizedLocalPlainLyricsSource(info.plainSource, info.plainProvider)
}

internal fun Context.localizedLocalLyricsSubtitle(item: LyricsStorage.LocalLyricsItem): String {
    val artistPart = item.artist.ifBlank { getString(R.string.ui_unknown_artist) }
    return artistPart + " · " + localizedLocalPlainLyricsSource(item.source, item.provider)
}

internal fun Context.localizedLocalLyricsType(item: LyricsStorage.LocalLyricsItem): String {
    return getString(localLyricsTypeRes(item))
}

@StringRes
internal fun localLyricsTypeRes(item: LyricsStorage.LocalLyricsItem): Int = when {
    item.hasWordByWordLyrics -> R.string.ui_word_by_word
    item.hasPlainLyrics -> R.string.ui_plain
    else -> R.string.ui_unknown_type
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
    val providerName = localizedPlainLyricsProviderName(error.providerId.ifBlank { error.providerName })
    val messageRes = when (error.errorType) {
        LyricsLookupErrorType.NotFound -> R.string.lyrics_lookup_not_found
        LyricsLookupErrorType.NeedCredential -> R.string.lyrics_lookup_need_credentials
        LyricsLookupErrorType.RateLimited -> R.string.lyrics_lookup_rate_limited
        LyricsLookupErrorType.RestrictedLyrics -> R.string.lyrics_lookup_restricted
        LyricsLookupErrorType.NetworkError -> R.string.lyrics_lookup_network_failed
        LyricsLookupErrorType.ParseError -> R.string.lyrics_lookup_parse_failed
        LyricsLookupErrorType.NativeError -> R.string.lyrics_lookup_native_error
        LyricsLookupErrorType.Unknown -> R.string.lyrics_lookup_failed
    }
    return getString(messageRes, providerName)
}
