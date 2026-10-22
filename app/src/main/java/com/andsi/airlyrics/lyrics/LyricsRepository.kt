package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.lyrics.providers.InternationalTitleResolver
import com.andsi.airlyrics.lyrics.providers.SongAliasNative
import android.content.Context
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsProvider
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.LocalPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.LrclibPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteasePlainLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.SongAliasStore
import com.andsi.airlyrics.lyrics.storage.ManualLyricsSelectionStore
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSettings
import java.util.concurrent.CancellationException

/**
 * Central lyrics lookup entry point.
 *
 * Lookup order:
 * 1. Local plain lyrics, so imported/saved files always win.
 * 2. Selected online providers in priority order, only when the user allows online search.
 * 3. Optional local plain cache save for successful online results.
 */
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
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        return findLyricsWithProviders(context, onlinePlainLyricsProviders, settings, title, artist,
            album, durationMs, bypassLocal, forceSaveOnline, ignoreAutoSearchSetting,
            cancellationToken, searchMode, searchOrigin, onInterimResult)
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
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        val song = SongIdentity(title, artist, album, durationMs)
        registerConfirmedAliases(context.applicationContext, song)
        val token = cancellationToken ?: LyricsLookupCancellationToken(song.storageKey(), 0L)
        val existing = LyricsSearchCoordinator.current(token)
        return runCatching {
            val lease = existing ?: LyricsSearchCoordinator.begin(song, token, searchOrigin, searchMode)
            try {
                LyricsRepositoryEngine(
                    localPlainLyricsProvider = LocalPlainLyricsProvider,
                    onlinePlainLyricsProviders = onlinePlainLyricsProviders,
                    settingsReader = { settings },
                    localPlainLyricsSaver = AndroidLocalPlainLyricsSaver,
                    wordByWordLyricsReader = AndroidWordByWordLyricsReader,
                    lookupLogger = AndroidLyricsLookupLogger,
                    commitOnlineResult = { request, result ->
                        lease.commit {
                            if (searchOrigin == LyricsSearchOrigin.Automatic) {
                                // The network request may have outlived the lookup that started it:
                                // re-check inside the storage lock so lyrics imported (or otherwise
                                // written) while the request was in flight are never overwritten.
                                val appeared = LocalPlainLyricsProvider.fetch(request).getOrNull()
                                if (appeared != null) {
                                    val cachedWords = if (settings.wordByWordLyricsEnabled) {
                                        LyricsStorage.readWordByWordLyrics(
                                            context = context.applicationContext,
                                            title = title,
                                            artist = artist,
                                            duration = durationMs
                                        )
                                    } else emptyList()
                                    return@commit if (cachedWords.isEmpty()) appeared
                                    else appeared.copy(wordByWordLines = cachedWords)
                                }
                            }
                            val committed = ManualLyricsSelectionStore.commit(
                                context.applicationContext, song, request.searchMode, result,
                                manual = searchOrigin == LyricsSearchOrigin.Manual,
                                savePlain = settings.autoSaveLocal || forceSaveOnline
                            )
                            if (result.wordByWordLines.isNotEmpty()) {
                                LyricsStorage.saveWordByWordLyrics(
                                    context = context.applicationContext,
                                    title = title,
                                    artist = artist,
                                    duration = durationMs,
                                    wordByWordLines = result.wordByWordLines,
                                    album = album,
                                    wordByWordProvider = result.plainProviderId,
                                    overwrite = true
                                )
                            }
                            committed
                        }
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
                    searchOrigin = searchOrigin
                ).getOrThrow().also { token.throwIfCancellationRequested() }
            } finally {
                if (existing == null) lease.close()
            }
        }
    }

    /**
     * Reuse a mapping the user already confirmed for this recording. The native engine only sees
     * aliases that are registered explicitly; nothing is guessed from the English title.
     */
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
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        val appContext = context.applicationContext
        var request = PlainLyricsSearchRequest(
            context = appContext,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            cancellationToken = cancellationToken
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
            // Existing lyrics — imported, edited or downloaded earlier — are never touched by an
            // automatic lookup: no online search, no word-timing upgrade and no cache correction.
            // A wrong match is the user's call, so only an explicit search replaces them.
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
            val prefetched = mutableMapOf<PlainLyricsSearchSource, LyricsProviderResult?>()
            var wordLookupError: Throwable? = null
            for (source in wordSources) {
                val result = try {
                    onlinePlainLyricsProviders.getValue(source).fetch(request).getOrThrow()
                } catch (e: Exception) {
                    cancellationToken?.throwIfCancellationRequested()
                    if (e is CancellationException) throw e
                    wordLookupError = e
                    prefetched[source] = null
                    continue
                }
                cancellationToken?.throwIfCancellationRequested()
                prefetched[source] = result
                if (result?.wordByWordLines?.isNotEmpty() == true) return@runCatching commit(result)
            }
            // A local line-only result must not stop the ordinary source pass: a word-capable
            // provider may have returned a usable line-only result (for example Apple lyrics).
            // Keep local as the final fallback after configured online sources are exhausted.
            val sources = settings.plainLyricsSearchSources
            val lineFallback = prefetched.values.firstOrNull { it != null }
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
            if (onlinePlainLyricsResult == null && sources.all { it in wordSources } && wordLookupError != null && local == null) throw wordLookupError
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
        preloaded: Map<PlainLyricsSearchSource, LyricsProviderResult?> = emptyMap()
    ): LyricsProviderResult? {
        val orderedSources = sources.distinct()
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
                if (source in preloaded) preloaded[source] else provider.fetch(request).getOrThrow()
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
                if (provider.continueOnFailure && index < orderedSources.lastIndex) continue
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
            return result
        }
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
        if (!enabled || result.access != null || result.selectedManually) return result

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
            plainLrc = LrcParser.mergeOriginalAndTranslationForStorage(
                plainLrc = plainLyricsResult.plainLrc,
                translatedLrc = plainLyricsResult.translatedLrc
            ),
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
