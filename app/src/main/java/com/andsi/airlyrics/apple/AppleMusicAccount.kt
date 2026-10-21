package com.andsi.airlyrics.apple

import android.content.Context

/**
 * Coordinates the two credentials Apple needs and records the account's storefront.
 */
object AppleMusicAccount {

    /** Validates a freshly captured token and persists it together with the storefront. */
    suspend fun completeLogin(context: Context, mediaUserToken: String): Result<AppleMusicSession> {
        val token = mediaUserToken.trim()
        if (token.isEmpty()) return Result.failure(IllegalArgumentException("Token is empty"))
        val developerToken = AppleMusicWebClient.developerToken(context).getOrElse { return Result.failure(it) }
        val storefront = AppleMusicWebClient.resolveStorefront(token, developerToken)
            .getOrElse { return Result.failure(it) }
        val session = AppleMusicSession(
            mediaUserToken = token,
            storefront = storefront,
            savedAtEpochMs = System.currentTimeMillis()
        )
        AppleMusicSessionStore.save(context, session)
        return Result.success(session)
    }

    /** Re-checks the stored token; also refreshes a stale storefront. */
    suspend fun refresh(context: Context): Result<AppleMusicSession> {
        val existing = AppleMusicSessionStore.load(context)
            ?: return Result.failure(IllegalStateException("Not signed in"))
        val developerToken = AppleMusicWebClient.developerToken(context).getOrElse { return Result.failure(it) }
        val storefront = AppleMusicWebClient.resolveStorefront(existing.mediaUserToken, developerToken)
            .getOrElse { return Result.failure(it) }
        val session = existing.copy(storefront = storefront)
        AppleMusicSessionStore.save(context, session)
        return Result.success(session)
    }

    fun logout(context: Context) = AppleMusicSessionStore.clear(context)
}
