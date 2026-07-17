package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException

private const val NATIVE_LOAD_FAILURE_MESSAGE = "Native lyrics core is missing or failed to load"

internal fun NativeLyricsJsonResult.toNativeLyricsLookupException(
    providerId: String,
    providerName: String,
    defaultMessage: String
): LyricsLookupException {
    return LyricsLookupException(
        providerId = providerId,
        providerName = providerName,
        errorType = errorType,
        detailMessage = errorMessage ?: defaultMessage
    )
}

internal fun <T> Result<T>.recoverNativeLoadFailure(
    providerId: String,
    providerName: String
): Result<T> {
    return recoverCatching { error ->
        if (error.isNativeLoadFailure()) {
            throw LyricsLookupException(
                providerId = providerId,
                providerName = providerName,
                errorType = LyricsLookupErrorType.NativeError,
                detailMessage = NATIVE_LOAD_FAILURE_MESSAGE,
                cause = error
            )
        }
        throw error
    }
}

internal fun Throwable.isNativeLoadFailure(): Boolean {
    return when (this) {
        is UnsatisfiedLinkError -> true
        is NoClassDefFoundError -> true
        is ExceptionInInitializerError -> cause?.isNativeLoadFailure() == true
        else -> false
    }
}
