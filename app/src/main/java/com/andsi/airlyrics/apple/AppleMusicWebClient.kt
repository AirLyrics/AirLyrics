package com.andsi.airlyrics.apple

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Apple's public web endpoints: the developer-token bundle and the account endpoint.
 *
 * The developer token rotates every couple of months and is public (it identifies the web
 * player app, not the listener), so it is cached until shortly before it expires.
 */
object AppleMusicWebClient {

    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val BROWSER_PAGE = "https://music.apple.com/us/browse"
    private const val AMP_API = "https://amp-api.music.apple.com"

    suspend fun developerToken(context: Context, forceRefresh: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis() / 1000
            if (!forceRefresh) {
                AppleMusicSessionStore.cachedDeveloperToken(context, now)?.let { return@withContext Result.success(it) }
            }
            runCatching {
                val html = listOf(BROWSER_PAGE, "https://music.apple.com/cn/browse", "https://music.apple.com/")
                    .firstNotNullOfOrNull { page ->
                        runCatching { httpGet(page) }.getOrNull()
                            ?.takeIf { AppleMusicTokens.pickScriptPath(it) != null }
                    } ?: error("Apple web script not found")
                val scriptPath = AppleMusicTokens.pickScriptPath(html)
                    ?: error("Apple web script not found")
                val bundle = httpGet("https://music.apple.com$scriptPath")
                val token = AppleMusicTokens.pickDeveloperToken(bundle, now)
                    ?: error("No developer token in the Apple web script")
                AppleMusicSessionStore.saveDeveloperToken(context, token)
                token
            }
        }

    /** Returns the account's storefront (e.g. `tr`, `cn`, `us`). */
    suspend fun resolveStorefront(mediaUserToken: String, developerToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = httpGet(
                    "$AMP_API/v1/me/storefront",
                    headers = mapOf(
                        "Authorization" to "Bearer $developerToken",
                        "media-user-token" to mediaUserToken
                    )
                )
                Regex("\"id\"\\s*:\\s*\"([a-z]{2})\"").find(body)?.groupValues?.getOrNull(1)
                    ?: error("Account info has no storefront")
            }
        }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,text/html,*/*")
            setRequestProperty("Origin", "https://music.apple.com")
            setRequestProperty("Referer", "https://music.apple.com/")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${body.take(180)}")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
