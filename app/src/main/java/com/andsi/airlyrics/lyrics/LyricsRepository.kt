package com.andsi.airlyrics.lyrics

import android.content.Context
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.parser.LrcParser
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
        PlainLyricsSearchSource.LRCLIB to LrclibPlainLyricsProvider
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
        cancellationToken: LyricsLookupCancellationToken? = null
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
            cancellationToken = cancellationToken
        )
    }
}

internal class LyricsRepositoryEngine(
    private val localPlainLyricsProvider: PlainLyricsProvider,
    private val onlinePlainLyricsProviders: Map<PlainLyricsSearchSource, PlainLyricsProvider>,
    private val settingsReader: (Context) -> LyricsSettings,
    private val localPlainLyricsSaver: LocalPlainLyricsSaver,
    private val wordByWordLyricsReader: WordByWordLyricsReader,
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
        cancellationToken: LyricsLookupCancellationToken? = null
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

            cancellationToken?.throwIfCancellationRequested()
            if (!bypassLocal) {
                localPlainLyricsProvider.fetch(request).getOrThrow()?.let { localPlainLyricsResult ->
                    cancellationToken?.throwIfCancellationRequested()
                    return@runCatching attachLocalWordByWordLyricsIfAvailable(
                        context = appContext,
                        result = localPlainLyricsResult,
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        enabled = wordByWordLyricsEnabled
                    )
                }
            }

            cancellationToken?.throwIfCancellationRequested()
            if (!ignoreAutoSearchSetting && !settings.autoSearchOnline) {
                return@runCatching null
            }

            val onlinePlainLyricsResult = findOnlinePlainLyrics(
                sources = settings.plainLyricsSearchSources,
                request = request
            )

            cancellationToken?.throwIfCancellationRequested()
            if (onlinePlainLyricsResult != null && (settings.autoSaveLocal || forceSaveOnline)) {
                localPlainLyricsSaver.save(
                    context = appContext,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    plainLyricsResult = onlinePlainLyricsResult
                )
            }

            cancellationToken?.throwIfCancellationRequested()
            onlinePlainLyricsResult?.let { result ->
                attachLocalWordByWordLyricsIfAvailable(
                    context = appContext,
                    result = result,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    enabled = wordByWordLyricsEnabled
                )
            }
        }
    }

    private fun findOnlinePlainLyrics(
        sources: List<PlainLyricsSearchSource>,
        request: PlainLyricsSearchRequest
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
                provider.fetch(request).getOrThrow()
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
        if (!enabled) return result

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
        if (LyricsStorage.hasWordByWordLyrics(context, title, artist, durationMs)) {
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
