package com.andsi.airlyrics.lyrics.providers

import android.content.Context
import com.andsi.airlyrics.apple.AppleMusicSession
import com.andsi.airlyrics.apple.AppleMusicSessionStore
import com.andsi.airlyrics.apple.AppleMusicWebClient
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Credentials are resolved per operation; catalogue identity belongs to the selected candidate. */
internal object AppleManualLyricsRequest {
    fun execute(
        context: Context,
        request: String,
        token: LyricsLookupCancellationToken,
        loadSession: () -> AppleMusicSession? = { AppleMusicSessionStore.load(context) },
        developerToken: () -> Result<String> = { runBlocking { AppleMusicWebClient.developerToken(context) } },
        nativeCall: (String, String, String, String, LyricsLookupCancellationToken) -> String = { raw, user, developer, storefront, cancellation ->
            withNativeLyricsCancellation(cancellation) { lookup ->
                AppleLyricsNative.requestManualJson(raw, user, developer, storefront, lookup)
            }
        }
    ): String {
        token.throwIfCancellationRequested()
        val session = loadSession() ?: throw LyricsLookupException(
            "apple", "Apple Music", LyricsLookupErrorType.NeedCredential, "Apple Music is not signed in"
        )
        val developer = developerToken().getOrElse {
            token.throwIfCancellationRequested()
            throw LyricsLookupException("apple", "Apple Music", LyricsLookupErrorType.NetworkError,
                "Apple Music credentials unavailable", it)
        }
        token.throwIfCancellationRequested()
        // Logout while obtaining the developer token invalidates this operation too.
        if (loadSession()?.mediaUserToken != session.mediaUserToken) {
            throw LyricsLookupException("apple", "Apple Music", LyricsLookupErrorType.NeedCredential,
                "Apple Music session changed")
        }
        val json = JSONObject(request)
        val storefront = if (json.getString("operation") == "fetch") {
            json.getString("storefront").also { require(it.matches(Regex("[a-z]{2}"))) }
        } else session.storefront.ifBlank { "us" }
        return try {
            nativeCall(request, session.mediaUserToken, developer, storefront, token).also {
                token.throwIfCancellationRequested()
            }
        } catch (error: LinkageError) {
            throw LyricsLookupException("apple", "Apple Music", LyricsLookupErrorType.NativeError,
                "Apple Music native library unavailable", error)
        }
    }
}
