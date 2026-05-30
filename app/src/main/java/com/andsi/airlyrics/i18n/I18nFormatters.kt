package com.andsi.airlyrics.i18n

import android.content.Context
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Context.localizedOffsetDescription(offsetMs: Long): String {
    return when {
        offsetMs > 0L -> tr("歌词提前", "Advanced") + " " + LyricsOffsetStore.formatOffset(offsetMs).removePrefix("+")
        offsetMs < 0L -> tr("歌词延后", "Delayed") + " " + LyricsOffsetStore.formatOffset(offsetMs).removePrefix("-")
        else -> tr("未偏移", "No offset")
    }
}

internal fun Context.localizedLocalLyricsSource(source: String, provider: String): String {
    return when (source) {
        LyricsStorage.SOURCE_MANUAL_IMPORT -> tr("手动导入", "Import")
        LyricsStorage.SOURCE_DOWNLOADED -> {
            if (provider.isBlank() || provider == "local") {
                tr("本地缓存", "Cache")
            } else {
                tr("本地缓存", "Cache") + " · " + provider
            }
        }
        LyricsStorage.SOURCE_LEGACY -> tr("本地歌词", "Local")
        else -> tr("本地歌词", "Local")
    }
}

internal fun Context.localizedLocalLyricsSource(info: LyricsStorage.LocalLyricsInfo): String {
    return localizedLocalLyricsSource(info.source, info.provider)
}

internal fun Context.localizedLocalLyricsSubtitle(item: LyricsStorage.LocalLyricsItem): String {
    val artistPart = item.artist.ifBlank { tr("未知歌手", "Unknown artist") }
    return artistPart + " · " + localizedLocalLyricsSource(item.source, item.provider)
}

internal fun Context.localizedLocalLyricsType(item: LyricsStorage.LocalLyricsItem): String {
    return when {
        item.hasPlainLyrics && item.hasKaraokeLyrics -> tr("普通 + 逐字", "Plain + word")
        item.hasKaraokeLyrics -> tr("逐字", "Word")
        item.hasPlainLyrics -> tr("普通", "Plain")
        else -> tr("未知类型", "Unknown type")
    }
}

internal fun Context.localizedLocalLyricsMeta(item: LyricsStorage.LocalLyricsItem): String {
    val dateText = if (item.modifiedTimeMillis > 0L) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.modifiedTimeMillis))
    } else {
        tr("未知时间", "Unknown time")
    }

    val sizeText = when {
        item.sizeBytes >= 1024 * 1024 -> "%.1f MB".format(item.sizeBytes / 1024f / 1024f)
        item.sizeBytes >= 1024 -> "%.1f KB".format(item.sizeBytes / 1024f)
        item.sizeBytes > 0 -> "${item.sizeBytes} B"
        else -> tr("未知大小", "Unknown size")
    }

    return "$dateText · $sizeText"
}

internal fun Context.localizedLyricsLookupMessage(error: LyricsLookupException): String {
    val providerName = error.providerName
    val suffix = when (error.errorType) {
        LyricsLookupErrorType.NotFound -> tr("未找到歌词", "found no lyrics")
        LyricsLookupErrorType.NeedCredential -> tr("暂时需要访问凭据", "needs credentials")
        LyricsLookupErrorType.RateLimited -> tr("请求过于频繁，请稍后再试", "is rate limited. Try again later")
        LyricsLookupErrorType.RestrictedLyrics -> tr("歌词受限，无法获取", "lyrics are restricted")
        LyricsLookupErrorType.NetworkError -> tr("网络请求失败", "network request failed")
        LyricsLookupErrorType.ParseError -> tr("歌词解析失败", "lyrics parse failed")
        LyricsLookupErrorType.NativeError -> tr("原生歌词模块异常", "native module error")
        LyricsLookupErrorType.Unknown -> tr("查找失败", "lookup failed")
    }
    return "$providerName $suffix"
}
