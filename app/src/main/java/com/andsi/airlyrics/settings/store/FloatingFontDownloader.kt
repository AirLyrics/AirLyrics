package com.andsi.airlyrics.settings.store

import com.andsi.airlyrics.core.model.FloatingFontNetworkException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class FloatingFontDownloadSpec(
    val id: String,
    val url: String,
    val bytes: Long,
    val sha256: String
)

internal interface FloatingFontDownloadResponse : Closeable {
    val input: InputStream
    val contentLength: Long
}

internal fun interface FloatingFontDownloadTransport {
    fun open(url: String, registerCancellation: (Closeable) -> Unit): FloatingFontDownloadResponse
}

/** Streams a pinned font without retaining the full file in memory. */
internal class FloatingFontDownloader(
    private val transport: FloatingFontDownloadTransport = FloatingFontDownloadTransport(::openHttps)
) {
    suspend fun fetch(
        spec: FloatingFontDownloadSpec,
        temporary: File,
        onProgress: (Int) -> Unit
    ): Unit = suspendCancellableCoroutine { continuation ->
        if (spec.bytes <= 0L) {
            continuation.resumeWithException(IOException("Invalid expected font size"))
            return@suspendCancellableCoroutine
        }
        val response = AtomicReference<FloatingFontDownloadResponse?>()
        val pendingConnection = AtomicReference<Closeable?>()
        continuation.invokeOnCancellation {
            // Closing a network stream may block; cancellation itself must not block the UI.
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                runCatching { pendingConnection.getAndSet(null)?.close() }
                runCatching { response.getAndSet(null)?.close() }
            })
        }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            try {
                if (!continuation.isActive) return@Runnable
                val opened = networkIo {
                    transport.open(spec.url) { connection ->
                        pendingConnection.set(connection)
                        if (!continuation.isActive) {
                            connection.close()
                            throw kotlinx.coroutines.CancellationException("Font download cancelled")
                        }
                    }
                }
                response.set(opened)
                if (!continuation.isActive) return@Runnable
                opened.use { source ->
                    if (source.contentLength > 0 && source.contentLength != spec.bytes) {
                        throw IOException("Unexpected font download size")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var previous = -1
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (continuation.isActive) {
                            val count = networkIo { source.input.read(buffer) }
                            if (count == -1) break
                            total += count
                            if (total > spec.bytes) throw IOException("Font download exceeds expected size")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val progress = (total * 100L / spec.bytes).toInt().coerceIn(0, 99)
                            if (progress != previous) {
                                previous = progress
                                onProgress(progress)
                            }
                        }
                    }
                    if (!continuation.isActive) return@Runnable
                    val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                    if (total != spec.bytes || !checksum.equals(spec.sha256, ignoreCase = true)) {
                        throw IOException("Font download verification failed")
                    }
                }
                if (continuation.isActive) continuation.resume(Unit)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally {
                runCatching { response.getAndSet(null)?.close() }
                if (!continuation.isActive && continuation.isCancelled) temporary.delete()
            }
        })
    }

    private inline fun <T> networkIo(operation: () -> T): T = try {
        operation()
    } catch (failure: IOException) {
        if (failure is DownloadProtocolException || failure is MalformedURLException) throw failure
        throw FloatingFontNetworkException(failure)
    }

    private class DownloadProtocolException(message: String) : IOException(message)

    private companion object {
        fun openHttps(url: String, registerCancellation: (Closeable) -> Unit): FloatingFontDownloadResponse {
            var next = URL(url)
            repeat(8) {
                if (next.protocol != "https") throw DownloadProtocolException("Font downloads require HTTPS")
                val connection = (next.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept-Encoding", "identity")
                }
                registerCancellation(Closeable { connection.disconnect() })
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                            ?: throw DownloadProtocolException("Missing font download redirect")
                        next = URL(next, location)
                        connection.disconnect()
                    } else {
                        if (status !in 200..299) {
                            throw DownloadProtocolException("Font download failed: $status")
                        }
                        val stream = connection.inputStream
                        return object : FloatingFontDownloadResponse {
                            override val input = stream
                            override val contentLength = connection.contentLengthLong
                            override fun close() {
                                try { input.close() } finally { connection.disconnect() }
                            }
                        }
                    }
                } catch (failure: Exception) {
                    connection.disconnect()
                    throw failure
                }
            }
            throw DownloadProtocolException("Too many font download redirects")
        }
    }
}
