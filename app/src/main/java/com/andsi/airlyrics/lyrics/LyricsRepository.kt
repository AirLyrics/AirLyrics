package com.andsi.airlyrics.lyrics

import android.content.Context
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsProvider
import com.andsi.airlyrics.lyrics.providers.LocalPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.LrclibPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchPlainLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteasePlainLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
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
        PlainLyricsSearchSource.SPICY to SpicyLyricsProvider
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
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        return LyricsRepositoryEngine(
            localPlainLyricsProvider = LocalPlainLyricsProvider,
            onlinePlainLyricsProviders = onlinePlainLyricsProviders,
            settingsReader = { settings },
            localPlainLyricsSaver = AndroidLocalPlainLyricsSaver,
            wordByWordLyricsReader = AndroidWordByWordLyricsReader,
            lookupLogger = AndroidLyricsLookupLogger
        ).findLyrics(
            context = context,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            bypassLocal = bypassLocal,
            forceSaveOnline = forceSaveOnline,
            ignoreAutoSearchSetting = ignoreAutoSearchSetting,
            cancellationToken = cancellationToken,
            onInterimResult = onInterimResult
        )
    }
}

internal class LyricsRepositoryEngine(
    private val localPlainLyricsProvider: PlainLyricsProvider,
    private val onlinePlainLyricsProviders: Map<PlainLyricsSearchSource, PlainLyricsProvider>,
    private val settingsReader: (Context) -> LyricsSettings,
    private val localPlainLyricsSaver: LocalPlainLyricsSaver,
    private val wordByWordLyricsReader: WordByWordLyricsReader,
    private val cachedOnlineLyricsReader: (LyricsProvider, PlainLyricsSearchRequest) -> LyricsProviderResult? = { provider, request -> provider.cached(request) },
    private val lookupLogger: LyricsLookupLogger = LyricsLookupLogger { _, _, _, _, _, _ -> }
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
        onInterimResult: ((LyricsProviderResult) -> Unit)? = null
    ): Result<LyricsProviderResult?> {
        val appContext = context.applicationContext
        val request = PlainLyricsSearchRequest(
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
            val wordByWordLyricsEnabled = settings.wordByWordLyricsEnabled

            val local = if (!bypassLocal) localPlainLyricsProvider.fetch(request).getOrThrow()?.let {
                attachLocalWordByWordLyricsIfAvailable(appContext, it, title, artist, durationMs, wordByWordLyricsEnabled)
            } else null
            val onlineAllowed = ignoreAutoSearchSetting || settings.autoSearchOnline
            val wordSources = if (wordByWordLyricsEnabled) settings.plainLyricsSearchSources.distinct().filter {
                onlinePlainLyricsProviders[it]?.supportsWordTiming == true
            } else emptyList()
            val seekWords = wordSources.isNotEmpty()
            if (local != null && (local.manualLocal || !seekWords || local.wordByWordLines.isNotEmpty())) return@runCatching local
            if (!bypassLocal && seekWords) {
                for (source in wordSources) {
                    val cached = cachedOnlineLyricsReader(onlinePlainLyricsProviders.getValue(source), request)
                    cancellationToken?.throwIfCancellationRequested()
                    if (cached?.wordByWordLines?.isNotEmpty() == true) return@runCatching cached
                }
            }
            if (!onlineAllowed) return@runCatching local
            if (local != null) {
                cancellationToken?.throwIfCancellationRequested()
                onInterimResult?.invoke(local)
            }
            val prefetched = mutableMapOf<PlainLyricsSearchSource, LyricsProviderResult?>()
            var wordLookupError: Throwable? = null
            for (source in wordSources) {
                try {
                    val result = onlinePlainLyricsProviders.getValue(source).fetch(request).getOrThrow()
                    cancellationToken?.throwIfCancellationRequested()
                    prefetched[source] = result
                    if (result?.wordByWordLines?.isNotEmpty() == true) return@runCatching result
                } catch (e: Exception) {
                    cancellationToken?.throwIfCancellationRequested()
                    if (e is CancellationException) throw e
                    wordLookupError = e
                    prefetched[source] = null
                }
            }
            if (seekWords && local != null) return@runCatching local
            val lineFallback = prefetched.values.firstOrNull { it != null }
            val sources = settings.plainLyricsSearchSources
            val onlinePlainLyricsResult = try {
                findOnlinePlainLyrics(sources, request, prefetched) ?: lineFallback
            } catch (error: Exception) {
                cancellationToken?.throwIfCancellationRequested()
                if (error is CancellationException || lineFallback == null) throw error
                lineFallback
            }
            if (onlinePlainLyricsResult == null && sources.all { it in wordSources } && wordLookupError != null) throw wordLookupError
            cancellationToken?.throwIfCancellationRequested()
            if (onlinePlainLyricsResult != null && onlinePlainLyricsResult.access == null && (settings.autoSaveLocal || forceSaveOnline)) {
                localPlainLyricsSaver.save(appContext, title, artist, album, durationMs, onlinePlainLyricsResult)
            }
            onlinePlainLyricsResult?.let {
                attachLocalWordByWordLyricsIfAvailable(appContext, it, title, artist, durationMs, wordByWordLyricsEnabled)
            }
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
        if (!enabled || result.access != null) return result

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
            overwrite = true
        )
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
