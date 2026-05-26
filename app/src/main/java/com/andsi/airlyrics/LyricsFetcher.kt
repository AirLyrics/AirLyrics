package com.andsi.airlyrics

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object LyricsFetcher {
    private val client = OkHttpClient()

    fun fetchSyncedLyrics(
        title: String,
        artist: String,
        durationMs: Long,
        callback: (Result<String?>) -> Unit
    ) {
        Thread {
            try {
                val urlBuilder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                    .addQueryParameter("track_name", title)

                if (artist.isNotBlank()) {
                    urlBuilder.addQueryParameter("artist_name", artist)
                }

                if (durationMs > 0) {
                    // LRCLIB 的 duration 通常用秒，不是毫秒
                    urlBuilder.addQueryParameter("duration", (durationMs / 1000).toString())
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("User-Agent", "FloatLyrics/0.1")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        callback(Result.success(null))
                        return@Thread
                    }

                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)

                    val syncedLyrics = json.optString("syncedLyrics", "")
                    val plainLyrics = json.optString("plainLyrics", "")

                    callback(
                        Result.success(
                            syncedLyrics.ifBlank {
                                plainLyrics.ifBlank { null }
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }.start()
    }
}
