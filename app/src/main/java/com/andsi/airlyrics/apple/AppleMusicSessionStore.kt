package com.andsi.airlyrics.apple

import android.content.Context

data class AppleMusicSession(
    val mediaUserToken: String,
    val storefront: String,
    val savedAtEpochMs: Long
)

/**
 * Local storage for the Apple Music credentials.
 *
 * Kept in the app-private preferences file: no backup, no export, cleared on logout.
 * The media user token is a bearer credential for the listener's Apple Music account.
 */
object AppleMusicSessionStore {

    private const val PREFS = "apple_music_session"
    private const val KEY_TOKEN = "media_user_token"
    private const val KEY_STOREFRONT = "storefront"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_DEVELOPER_TOKEN = "developer_token"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): AppleMusicSession? {
        val token = prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return AppleMusicSession(
            mediaUserToken = token,
            storefront = prefs(context).getString(KEY_STOREFRONT, null).orEmpty(),
            savedAtEpochMs = prefs(context).getLong(KEY_SAVED_AT, 0L)
        )
    }

    fun save(context: Context, session: AppleMusicSession) {
        prefs(context).edit()
            .putString(KEY_TOKEN, session.mediaUserToken)
            .putString(KEY_STOREFRONT, session.storefront)
            .putLong(KEY_SAVED_AT, session.savedAtEpochMs)
            .apply()
    }

    fun updateStorefront(context: Context, storefront: String) {
        prefs(context).edit().putString(KEY_STOREFRONT, storefront).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun hasSession(context: Context): Boolean = load(context) != null

    fun cachedDeveloperToken(context: Context, nowEpochSeconds: Long): String? {
        val token = prefs(context).getString(KEY_DEVELOPER_TOKEN, null) ?: return null
        return token.takeIf { AppleMusicTokens.isJwtFresh(it, nowEpochSeconds, 86_400) }
    }

    fun saveDeveloperToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_DEVELOPER_TOKEN, token).apply()
    }
}
