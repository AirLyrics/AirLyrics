package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.lyrics.providers.InternationalTitleResolver
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
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false,
        cancellationToken: LyricsLookupCancellationToken? = null,
        searchMode: LyricsSearchMode = LyricsSearchMode.Original,
        searchOrigin: LyricsSearchOrigin = LyricsSearchOrigin.Automatic,
        spotifyTrackId: String? = null,
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        return findLyricsWithProviders(context, onlinePlainLyricsProviders, settings, title, artist,
            album, durationMs, bypassLocal, forceSaveOnline, ignoreAutoSearchSetting,
            cancellationToken, searchMode, searchOrigin, spotifyTrackId, onInterimResult)
    }

    internal fun findLyricsWithProviders(
        context: Context,
        onlinePlainLyricsProviders: Map<PlainLyricsSearchSource, PlainLyricsProvider>,
        settings: LyricsSettings,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false,
        cancellationToken: LyricsLookupCancellationToken? = null,
        searchMode: LyricsSearchMode = LyricsSearchMode.Original,
        searchOrigin: LyricsSearchOrigin = LyricsSearchOrigin.Automatic,
        spotifyTrackId: String? = null,
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        val song = SongIdentity(title, artist, album, durationMs)
        registerConfirmedAliases(context.applicationContext, song)
        val token = cancellationToken ?: LyricsLookupCancellationToken(song.storageKey(), 0L)
        val existing = LyricsSearchCoordinator.current(token)
        return runCatching {
            val lease = existing ?: LyricsSearchCoordinator.begin(song, token, searchOrigin, searchMode)
            val committer = LyricsResultCommitter(context)
            try {
                LyricsRepositoryEngine(
                    localPlainLyricsProvider = LocalPlainLyricsProvider,
                    onlinePlainLyricsProviders = onlinePlainLyricsProviders,
                    settingsReader = { settings },
                    localPlainLyricsSaver = AndroidLocalPlainLyricsSaver,
                    wordByWordLyricsReader = AndroidWordByWordLyricsReader,
                    lookupLogger = AndroidLyricsLookupLogger,
                    commitOnlineResult = { request, result ->
                        committer.commitLookup(
                            lease = lease,
                            target = song,
                            request = request,
                            result = result,
                            savePlain = settings.autoSaveLocal || forceSaveOnline,
                            wordByWordEnabled = settings.wordByWordLyricsEnabled
                        )
                    }
                ).findLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    bypassLocal = bypassLocal,
                    forceSaveOnline = forceSaveOnline,
                    ignoreAutoSearchSetting = ignoreAutoSearchSetting,
                    cancellationToken = token,
                    onInterimResult = onInterimResult,
                    searchMode = searchMode,
                    searchOrigin = searchOrigin,
                    spotifyTrackId = spotifyTrackId
                ).getOrThrow().let { result ->
                    token.throwIfCancellationRequested()
                    if (!settings.wordByWordLyricsEnabled) result?.copy(wordByWordLines = emptyList()) else result
                }
            } finally {
                if (existing == null) lease.close()
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
    private val localPlainLyricsSaver: LocalPlainLyricsSaver,
    private val wordByWordLyricsReader: WordByWordLyricsReader,
    private val cachedOnlineLyricsReader: (LyricsProvider, PlainLyricsSearchRequest) -> LyricsProviderResult? = { provider, request -> provider.cached(request) },
    private val lookupLogger: LyricsLookupLogger = LyricsLookupLogger { _, _, _, _, _, _ -> },
    private val commitOnlineResult: ((PlainLyricsSearchRequest, LyricsProviderResult) -> LyricsProviderResult)? = null
) {
    fun findLyrics(
        context: Context,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false,
        cancellationToken: LyricsLookupCancellationToken? = null,
        searchMode: LyricsSearchMode = LyricsSearchMode.Original,
        searchOrigin: LyricsSearchOrigin = LyricsSearchOrigin.Automatic,
        spotifyTrackId: String? = null,
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        val appContext = context.applicationContext
        var request = PlainLyricsSearchRequest(
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
                return commitOnlineResult?.invoke(request, result) ?: result.also {
                    if (it.access == null && (settings.autoSaveLocal || forceSaveOnline)) {
                        localPlainLyricsSaver.save(appContext, title, artist, album, durationMs, it)
                    }
                }
            }
            val wordByWordLyricsEnabled = settings.wordByWordLyricsEnabled

            val local = if (!bypassLocal) localPlainLyricsProvider.fetch(request).getOrThrow()?.let {
                attachLocalWordByWordLyricsIfAvailable(appContext, it, title, artist, durationMs, wordByWordLyricsEnabled)
            } else null
            if (local != null && searchOrigin == LyricsSearchOrigin.Automatic) return@runCatching local
            val onlineAllowed = ignoreAutoSearchSetting || settings.autoSearchOnline
            val wordSources = if (wordByWordLyricsEnabled) settings.plainLyricsSearchSources.distinct().filter {
                onlinePlainLyricsProviders[it]?.supportsWordTiming == true
            } else emptyList()
            val seekWords = wordSources.isNotEmpty()
            if (!bypassLocal && seekWords) {
                for (source in wordSources) {
                    val cached = cachedOnlineLyricsReader(onlinePlainLyricsProviders.getValue(source), request)
                    cancellationToken?.throwIfCancellationRequested()
                    if (cached?.wordByWordLines?.isNotEmpty() == true) return@runCatching cached
                }
            }
            if (!onlineAllowed) return@runCatching local
            if (searchMode == LyricsSearchMode.International) {
                request = InternationalTitleResolver.resolve(request)
            }
            cancellationToken?.reportSearchMode(request.searchMode)
            if (local != null) {
                cancellationToken?.throwIfCancellationRequested()
                onInterimResult?.invoke(local)
            }
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
            // A local line-only result must not stop the ordinary source pass: a word-capable
            // provider may have returned a usable line-only result (for example Apple lyrics).
            // Keep local as the final fallback after configured online sources are exhausted.
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
                if (forceSaveOnline) committed else
                    attachLocalWordByWordLyricsIfAvailable(appContext, committed, title, artist, durationMs, wordByWordLyricsEnabled)
            } ?: local
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

internal fun interface LocalPlainLyricsSaver {
    fun save(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        plainLyricsResult: LyricsProviderResult
    )
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

private object AndroidLocalPlainLyricsSaver : LocalPlainLyricsSaver {
    override fun save(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        plainLyricsResult: LyricsProviderResult
    ) {
        if (plainLyricsResult.access != null || LyricsStorage.hasWordByWordLyrics(context, title, artist, durationMs)) {
            return
        }

        LyricsStorage.savePlainLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = durationMs,
            plainLrc = plainLyricsResult.storageText,
            isUntimed = plainLyricsResult.isUntimed,
            untimedTranslation = plainLyricsResult.untimedLyrics?.translatedText,
            album = album,
            plainSource = LyricsStorage.SOURCE_DOWNLOADED,
            plainProvider = plainLyricsResult.plainProviderId,
            overwrite = true,
            matchedTitle = plainLyricsResult.matchedTitle,
            matchedArtist = plainLyricsResult.matchedArtist,
            matchedDurationMs = plainLyricsResult.matchedDurationMs
        )
        // Remember how this song's foreign-language title maps to what the source returned, so the
        // cache is trusted next time instead of being re-verified (or wrongly invalidated).
        val matchedTitle = plainLyricsResult.matchedTitle
        if (matchedTitle.isNotBlank() &&
            !CachedMatchGuard.isTrusted(title, matchedTitle, null)
        ) {
            SongAliasStore.save(
                context,
                SongIdentity(title, artist, album, durationMs),
                SongAliasStore.Alias(
                    originalTitle = title,
                    originalArtist = artist,
                    originalArtistAliases = listOf(artist),
                    aliasTitle = matchedTitle,
                    aliasArtist = plainLyricsResult.matchedArtist,
                    source = SongAliasStore.SOURCE_CATALOGUE,
                    confidence = 0.85,
                    confirmedAt = System.currentTimeMillis()
                )
            )
        }
    }
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
