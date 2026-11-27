package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.lyrics.providers.SongAliasNative
import android.content.Context
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsProvider
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.LocalPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.LrclibPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteasePlainLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.SongAliasStore
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSettings
import java.util.concurrent.CancellationException

object LyricsRepository {
    private val onlinePlainLyricsProviders = mapOf(
        PlainLyricsSearchSource.NETEASE to NeteasePlainLyricsProvider,
        PlainLyricsSearchSource.MUSIXMATCH to MusixmatchPlainLyricsProvider,
        PlainLyricsSearchSource.LRCLIB to LrclibPlainLyricsProvider,
        PlainLyricsSearchSource.SPICY to SpicyLyricsProvider,
        PlainLyricsSearchSource.APPLE to ApplePlainLyricsProvider
    )

    fun findLyrics(
        context: Context,
        settings: LyricsSettings,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null,
        spotifyTrackId: String? = null,
        onOnlineLookup: (() -> Unit)? = null,
    ): Result<LyricsProviderResult?> = findLyricsWithProviders(
        context, onlinePlainLyricsProviders, settings, title, artist, album, durationMs,
        cancellationToken, spotifyTrackId, onOnlineLookup,
    )

    internal fun findLyricsWithProviders(
        context: Context,
        onlinePlainLyricsProviders: Map<PlainLyricsSearchSource, PlainLyricsProvider>,
        settings: LyricsSettings,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null,
        spotifyTrackId: String? = null,
        onOnlineLookup: (() -> Unit)? = null,
    ): Result<LyricsProviderResult?> {
        val song = SongIdentity(title, artist, album, durationMs)
        registerConfirmedAliases(context.applicationContext, song)
        val token = cancellationToken ?: LyricsLookupCancellationToken(song.storageKey(), 0L)
        return runCatching {
            LyricsSearchCoordinator.begin(song, token, LyricsSearchOrigin.Automatic, LyricsSearchMode.Original).use { lease ->
                val committer = LyricsResultCommitter(context)
                LyricsRepositoryEngine(
                    localPlainLyricsProvider = LocalPlainLyricsProvider,
                    onlinePlainLyricsProviders = onlinePlainLyricsProviders,
                    settingsReader = { settings },
                    wordByWordLyricsReader = AndroidWordByWordLyricsReader,
                    commitOnlineResult = { request, result ->
                        committer.commitLookup(
                            lease = lease,
                            target = song,
                            request = request,
                            result = result,
                            savePlain = settings.autoSaveLocal,
                            wordByWordEnabled = settings.wordByWordLyricsEnabled,
                        )
                    },
                    lookupLogger = AndroidLyricsLookupLogger,
                ).findLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    cancellationToken = token,
                    spotifyTrackId = spotifyTrackId,
                    onOnlineLookup = onOnlineLookup,
                ).getOrThrow().let { result ->
                    token.throwIfCancellationRequested()
                    if (!settings.wordByWordLyricsEnabled) result?.copy(wordByWordLines = emptyList()) else result
                }
            }
        }
    }

    private fun registerConfirmedAliases(context: Context, song: SongIdentity) {
        val alias = runCatching { SongAliasStore.read(context, song) }.getOrNull() ?: return
        runCatching {
            SongAliasNative.registerJson(SongAliasStore.toNativeJson(listOf(alias)))
        }
    }
}

internal class LyricsRepositoryEngine(
    private val localPlainLyricsProvider: PlainLyricsProvider,
    private val onlinePlainLyricsProviders: Map<PlainLyricsSearchSource, PlainLyricsProvider>,
    private val settingsReader: (Context) -> LyricsSettings,
    private val wordByWordLyricsReader: WordByWordLyricsReader,
    private val commitOnlineResult: (PlainLyricsSearchRequest, LyricsProviderResult) -> LyricsProviderResult,
    private val cachedOnlineLyricsReader: (LyricsProvider, PlainLyricsSearchRequest) -> LyricsProviderResult? = { provider, request -> provider.cached(request) },
    private val lookupLogger: LyricsLookupLogger = LyricsLookupLogger { _, _, _, _, _, _ -> },
) {
    fun findLyrics(
        context: Context,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null,
        spotifyTrackId: String? = null,
        onOnlineLookup: (() -> Unit)? = null,
    ): Result<LyricsProviderResult?> {
        val appContext = context.applicationContext
        val request = PlainLyricsSearchRequest(
            context = appContext,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            cancellationToken = cancellationToken,
            spotifyTrackId = spotifyTrackId
        )

        return runCatching {
            cancellationToken?.throwIfCancellationRequested()
            val settings = settingsReader(context)
            fun commit(result: LyricsProviderResult): LyricsProviderResult {
                cancellationToken?.throwIfCancellationRequested()
                return commitOnlineResult(request, result)
            }

            val wordByWordLyricsEnabled = settings.wordByWordLyricsEnabled
            localPlainLyricsProvider.fetch(request).getOrThrow()?.let { local ->
                return@runCatching attachLocalWordByWordLyricsIfAvailable(
                    appContext, local, title, artist, durationMs, wordByWordLyricsEnabled
                )
            }

            val wordSources = if (wordByWordLyricsEnabled) settings.plainLyricsSearchSources.distinct().filter {
                onlinePlainLyricsProviders[it]?.supportsWordTiming == true
            } else emptyList()
            if (wordSources.isNotEmpty()) {
                for (source in wordSources) {
                    val cached = cachedOnlineLyricsReader(onlinePlainLyricsProviders.getValue(source), request)
                    cancellationToken?.throwIfCancellationRequested()
                    if (cached?.wordByWordLines?.isNotEmpty() == true) return@runCatching cached
                }
            }
            if (!settings.autoSearchOnline) return@runCatching null
            cancellationToken?.throwIfCancellationRequested()
            onOnlineLookup?.invoke()

            val prefetched = mutableMapOf<PlainLyricsSearchSource, Result<LyricsProviderResult?>>()
            for (source in wordSources) {
                val result = try {
                    onlinePlainLyricsProviders.getValue(source).fetch(request).getOrThrow()
                } catch (e: Exception) {
                    cancellationToken?.throwIfCancellationRequested()
                    if (e is CancellationException) throw e
                    prefetched[source] = Result.failure(e)
                    continue
                }
                cancellationToken?.throwIfCancellationRequested()
                prefetched[source] = Result.success(result)
                if (result?.wordByWordLines?.isNotEmpty() == true) return@runCatching commit(result)
            }
            val sources = settings.plainLyricsSearchSources
            val prefetchedMatches = prefetched.values.mapNotNull { it.getOrNull() }
            val lineFallback = prefetchedMatches.firstOrNull { !it.isUntimed } ?: prefetchedMatches.firstOrNull()
            val onlinePlainLyricsResult = try {
                // Word timing is an optional capability. Once the word-capable prefetch has
                // failed to produce timed words, resolve the ordinary result strictly in the
                // user's configured source order; an Apple line-only hit is still a valid Apple
                // hit and must not be silently demoted.
                findOnlinePlainLyrics(sources, request, prefetched)
            } catch (error: Exception) {
                cancellationToken?.throwIfCancellationRequested()
                if (error is CancellationException) throw error
                if (lineFallback == null) throw error
                lineFallback
            }
            cancellationToken?.throwIfCancellationRequested()
            onlinePlainLyricsResult?.let { result ->
                val committed = commit(result)
                attachLocalWordByWordLyricsIfAvailable(appContext, committed, title, artist, durationMs, wordByWordLyricsEnabled)
            }
        }
    }

    private fun findOnlinePlainLyrics(
        sources: List<PlainLyricsSearchSource>,
        request: PlainLyricsSearchRequest,
        preloaded: Map<PlainLyricsSearchSource, Result<LyricsProviderResult?>> = emptyMap()
    ): LyricsProviderResult? {
        val orderedSources = sources.distinct()
        var lookupError: Throwable? = null
        var textFallback: LyricsProviderResult? = null
        for ((index, source) in orderedSources.withIndex()) {
            request.cancellationToken?.throwIfCancellationRequested()
            val provider = checkNotNull(onlinePlainLyricsProviders[source]) {
                "No online lyrics provider registered for source: ${source.key}"
            }
            lookupLogger.logProviderProgress(
                provider = provider,
                priority = index + 1,
                total = orderedSources.size,
                stage = LyricsLookupProviderStage.ATTEMPT,
                debug = BuildConfig.DEBUG
            )

            val result = try {
                (preloaded[source] ?: provider.fetch(request)).getOrThrow()
            } catch (error: Throwable) {
                request.cancellationToken?.throwIfCancellationRequested()
                if (error is CancellationException) {
                    throw error
                }
                lookupLogger.logProviderFailure(
                    provider = provider,
                    title = request.title,
                    artist = request.artist,
                    durationMs = request.durationMs,
                    error = error,
                    debug = BuildConfig.DEBUG
                )
                if (provider.continueOnFailure) {
                    lookupError = error
                    continue
                }
                if (textFallback != null) { lookupError = error; continue }
                throw error
            }

            request.cancellationToken?.throwIfCancellationRequested()
            if (result == null || !result.hasUsableLyrics()) {
                lookupLogger.logProviderProgress(
                    provider = provider,
                    priority = index + 1,
                    total = orderedSources.size,
                    stage = LyricsLookupProviderStage.MISS,
                    debug = BuildConfig.DEBUG
                )
                continue
            }

            lookupLogger.logProviderProgress(
                provider = provider,
                priority = index + 1,
                total = orderedSources.size,
                stage = LyricsLookupProviderStage.MATCH,
                debug = BuildConfig.DEBUG
            )
            if (result.isUntimed) {
                if (textFallback == null) textFallback = result
                continue
            }
            return result
        }
        textFallback?.let { return it }
        lookupError?.let { throw it }
        return null
    }

    private fun attachLocalWordByWordLyricsIfAvailable(
        context: Context,
        result: LyricsProviderResult,
        title: String,
        artist: String,
        durationMs: Long,
        enabled: Boolean
    ): LyricsProviderResult {
        if (result.isUntimed) return result
        if (!enabled) return if (result.wordByWordLines.isEmpty()) result else result.copy(wordByWordLines = emptyList())
        if (result.access != null || result.selectedManually) return result

        val cachedWordByWordLines = wordByWordLyricsReader.read(
            context = context,
            title = title,
            artist = artist,
            durationMs = durationMs
        )
        if (cachedWordByWordLines.isEmpty()) return result

        return result.copy(wordByWordLines = cachedWordByWordLines)
    }
}

internal fun interface WordByWordLyricsReader {
    fun read(context: Context, title: String, artist: String, durationMs: Long): List<WordByWordLine>
}

internal fun interface LyricsLookupLogger {
    fun logProviderFailure(
        provider: PlainLyricsProvider,
        title: String,
        artist: String,
        durationMs: Long,
        error: Throwable,
        debug: Boolean
    )

    fun logProviderProgress(
        provider: PlainLyricsProvider,
        priority: Int,
        total: Int,
        stage: LyricsLookupProviderStage,
        debug: Boolean
    ) = Unit
}

internal enum class LyricsLookupProviderStage(val logValue: String) {
    ATTEMPT("provider_attempt"),
    MISS("provider_miss"),
    MATCH("provider_match")
}

private object AndroidWordByWordLyricsReader : WordByWordLyricsReader {
    override fun read(context: Context, title: String, artist: String, durationMs: Long): List<WordByWordLine> {
        return LyricsStorage.readWordByWordLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = durationMs
        )
    }
}

private object AndroidLyricsLookupLogger : LyricsLookupLogger {
    override fun logProviderFailure(
        provider: PlainLyricsProvider,
        title: String,
        artist: String,
        durationMs: Long,
        error: Throwable,
        debug: Boolean
    ) {
        if (debug) {
            Log.w(
                "AirLyricsLyrics",
                "${provider.name} lookup failed: title=$title artist=$artist durationMs=$durationMs",
                error
            )
        } else {
            Log.w("AirLyricsLyrics", "${provider.name} lookup failed", error)
        }
    }

    override fun logProviderProgress(
        provider: PlainLyricsProvider,
        priority: Int,
        total: Int,
        stage: LyricsLookupProviderStage,
        debug: Boolean
    ) {
        if (!debug) return
        Log.d(
            "AirLyricsLyrics",
            "stage=${stage.logValue} source=${provider.id} priority=$priority/$total"
        )
    }
}
