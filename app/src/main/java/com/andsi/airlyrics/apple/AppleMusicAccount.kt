package com.andsi.airlyrics.apple

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates the two credentials Apple needs and records the account's storefront.
 */
object AppleMusicAccount {

    /**
     * Bumped by every new login / refresh / logout. A network answer may outlive the action that
     * started it (most commonly: the user signs out while a validation round-trip is pending),
     * so a stale response is only persisted while its generation is still the newest.
     */
    private val generation = AtomicLong(0L)

    /** Validates a freshly captured token and persists it together with the storefront. */
    suspend fun completeLogin(context: Context, mediaUserToken: String): Result<AppleMusicSession> {
        val token = mediaUserToken.trim()
        if (token.isEmpty()) return Result.failure(IllegalArgumentException("Token is empty"))
        val myGeneration = generation.incrementAndGet()
        val developerToken = AppleMusicWebClient.developerToken(context).getOrElse { return Result.failure(it) }
        val storefront = AppleMusicWebClient.resolveStorefront(token, developerToken)
            .getOrElse { return Result.failure(it) }
        if (generation.get() != myGeneration) {
            return Result.failure(IllegalStateException("Login superseded"))
        }
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
        val myGeneration = generation.incrementAndGet()
        val developerToken = AppleMusicWebClient.developerToken(context).getOrElse { return Result.failure(it) }
        val storefront = AppleMusicWebClient.resolveStorefront(existing.mediaUserToken, developerToken)
            .getOrElse { return Result.failure(it) }
        if (generation.get() != myGeneration) {
            // Logout (or a newer login) happened while the storefront round-trip was pending.
            return Result.failure(IllegalStateException("Refresh superseded"))
        }
        val session = existing.copy(storefront = storefront)
        AppleMusicSessionStore.save(context, session)
        return Result.success(session)
    }

    fun logout(context: Context) {
        generation.incrementAndGet()
        AppleMusicSessionStore.clear(context)
    }
}
