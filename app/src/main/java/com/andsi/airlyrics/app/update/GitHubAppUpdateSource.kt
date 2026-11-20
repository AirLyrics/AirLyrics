package com.andsi.airlyrics.app.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

internal object GitHubAppUpdateSource {
    private const val REPOSITORY = "AirLyrics/AirLyrics"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val METADATA_LIMIT = 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val apiHosts = setOf("api.github.com")
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    suspend fun latestRelease(installedVersion: AppUpdateVersion): AppUpdateRelease? =
        get(LATEST_RELEASE_URL, apiHosts, "application/vnd.github+json") { connection ->
            val metadata = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > METADATA_LIMIT) throw IOException("Release metadata is too large")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            parseLatestRelease(metadata, installedVersion)
        }

    suspend fun download(release: AppUpdateRelease, read: suspend (HttpsURLConnection) -> Unit) {
        val tag = "v${release.version}"
        val url = "https://github.com/$REPOSITORY/releases/download/$tag/AirLyrics-$tag.apk"
        get(url, downloadHosts, "application/octet-stream", read)
    }

    internal fun parseLatestRelease(metadata: String, installedVersion: AppUpdateVersion): AppUpdateRelease? =
        try {
            val json = JSONObject(metadata)
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) {
                throw IOException("The latest release is not a stable release")
            }
            val tag = json.getString("tag_name")
            val version = AppUpdateVersion.parse(tag.removePrefix("v"))
                ?.takeIf { "v$it" == tag }
                ?: throw IOException("Unexpected release tag")
            if (version <= installedVersion) {
                null
            } else {
                val assets = json.getJSONArray("assets")
                val asset = (0 until assets.length())
                    .mapNotNull(assets::optJSONObject)
                    .singleOrNull { it.optString("name") == "AirLyrics-$tag.apk" && it.optString("state") == "uploaded" }
                    ?: throw IOException("The release has no standard AirLyrics APK")
                val digest = asset.getString("digest")
                if (!digest.startsWith("sha256:")) throw IOException("The release has no SHA-256 digest")
                AppUpdateRelease.create(
                    version,
                    asset.getLong("size"),
                    digest.removePrefix("sha256:"),
                    runCatching { LocalDate.parse(json.optString("published_at").take(10)) }.getOrNull(),
                    json.optString("body", "")
                ) ?: throw IOException("Invalid APK size or SHA-256 digest")
            }
        } catch (failure: JSONException) {
            throw IOException("Invalid release metadata", failure)
        }

    private suspend fun <T> get(
        url: String,
        allowedHosts: Set<String>,
        accept: String,
        read: suspend (HttpsURLConnection) -> T
    ): T {
        var next = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            currentCoroutineContext().ensureActive()
            if (next.protocol != "https" || next.host !in allowedHosts ||
                next.userInfo != null || (next.port != -1 && next.port != 443)
            ) {
                throw IOException("Unexpected GitHub download address")
            }
            val connection = (next.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AirLyrics")
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val status = connection.responseCode
                if (status in redirectCodes) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Missing GitHub redirect address")
                    next = URL(next, location)
                } else {
                    if (status != 200) throw IOException("GitHub returned HTTP $status")
                    return read(connection)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many GitHub redirects")
    }
}
