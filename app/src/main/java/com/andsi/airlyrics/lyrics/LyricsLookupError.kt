package com.andsi.airlyrics.lyrics

/** Structured provider failure that can be shown to users and logged during tests. */
class LyricsLookupException(
    val providerId: String,
    val providerName: String,
    val errorType: LyricsLookupErrorType,
    val detailMessage: String,
    cause: Throwable? = null
) : Exception(detailMessage, cause) {
    fun userMessage(): String {
        return when (errorType) {
            LyricsLookupErrorType.NotFound -> "$providerName 未找到歌词"
            LyricsLookupErrorType.NeedCredential -> "$providerName 暂时需要访问凭据"
            LyricsLookupErrorType.RateLimited -> "$providerName 请求过于频繁，请稍后再试"
            LyricsLookupErrorType.RestrictedLyrics -> "$providerName 歌词受限，无法获取"
            LyricsLookupErrorType.NetworkError -> "$providerName 网络请求失败"
            LyricsLookupErrorType.ParseError -> "$providerName 歌词解析失败"
            LyricsLookupErrorType.NativeError -> "$providerName 原生歌词模块异常"
            LyricsLookupErrorType.Unknown -> "$providerName 查找失败"
        }
    }
}

enum class LyricsLookupErrorType {
    NotFound,
    NeedCredential,
    RateLimited,
    RestrictedLyrics,
    NetworkError,
    ParseError,
    NativeError,
    Unknown;

    companion object {
        fun fromNativeName(name: String?): LyricsLookupErrorType {
            return when (name.orEmpty()) {
                "NotFound" -> NotFound
                "NeedCredential" -> NeedCredential
                "RateLimited" -> RateLimited
                "RestrictedLyrics" -> RestrictedLyrics
                "NetworkError" -> NetworkError
                "ParseError" -> ParseError
                "NativeError" -> NativeError
                "SerializeError" -> NativeError
                else -> Unknown
            }
        }
    }
}
