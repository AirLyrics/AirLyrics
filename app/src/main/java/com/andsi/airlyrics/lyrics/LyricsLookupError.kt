package com.andsi.airlyrics.lyrics

class LyricsLookupException(
    val providerId: String,
    val providerName: String,
    val errorType: LyricsLookupErrorType,
    val detailMessage: String,
    cause: Throwable? = null
) : Exception(detailMessage, cause)

enum class LyricsLookupErrorType {
    NotFound,
    NeedCredential,
    RateLimited,
    RestrictedLyrics,
    HttpError,
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
                "HttpError" -> HttpError
                "NetworkError" -> NetworkError
                "ParseError" -> ParseError
                "NativeError" -> NativeError
                "SerializeError" -> NativeError
                else -> Unknown
            }
        }
    }
}
