package com.andsi.airlyrics.lyrics

import android.content.Context
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.providers.LocalLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteaseLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.settings.model.LyricsSettings
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

/**
 * Central lyrics lookup entry point.
 *
 * Lookup order:
 * 1. Local lyrics, so imported/saved files always win.
 * 2. Online provider, only when the user allows online search.
 * 3. Optional local cache save for successful online results.
 */
object LyricsRepository {
    private val engine = LyricsRepositoryEngine(
        localProvider = LocalLyricsProvider,
        onlineProviders = mapOf(
            LyricsSearchSource.NETEASE to NeteaseLyricsProvider,
            LyricsSearchSource.MUSIXMATCH to MusixmatchLyricsProvider
        ),
        settingsReader = LyricsSettingsStore::getSettings,
        localLyricsSaver = AndroidLocalLyricsSaver,
        karaokeLyricsReader = AndroidKaraokeLyricsReader,
        lookupLogger = AndroidLyricsLookupLogger
    )

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
        return engine.findLyrics(
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
    private val localProvider: LyricsProvider,
    private val onlineProviders: Map<LyricsSearchSource, LyricsProvider>,
    private val settingsReader: (Context) -> LyricsSettings,
    private val localLyricsSaver: LocalLyricsSaver,
    private val karaokeLyricsReader: KaraokeLyricsReader,
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
        val request = LyricsSearchRequest(
            context = appContext,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )

        return runCatching {
            cancellationToken?.throwIfCancellationRequested()
            val settings = settingsReader(context)
            val wordByWordEnabled = settings.karaokeLyricsEnabled

            cancellationToken?.throwIfCancellationRequested()
            if (!bypassLocal) {
                localProvider.fetch(request).getOrThrow()?.let { localResult ->
                    cancellationToken?.throwIfCancellationRequested()
                    return@runCatching attachLocalKaraokeIfAvailable(
                        context = appContext,
                        result = localResult,
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        enabled = wordByWordEnabled
                    )
                }
            }

            cancellationToken?.throwIfCancellationRequested()
            if (settings.source == LyricsSearchSource.LOCAL_ONLY) {
                return@runCatching null
            }
            if (!ignoreAutoSearchSetting && !settings.autoSearchOnline) {
                return@runCatching null
            }

            val provider = onlineProviders[settings.source] ?: return@runCatching null

            cancellationToken?.throwIfCancellationRequested()
            val onlineResult = provider.fetch(request).getOrElse { error ->
                lookupLogger.logProviderFailure(
                    provider = provider,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    error = error,
                    debug = BuildConfig.DEBUG
                )
                throw error
            }

            cancellationToken?.throwIfCancellationRequested()
            if (onlineResult != null && (settings.autoSaveLocal || forceSaveOnline)) {
                localLyricsSaver.save(
                    context = appContext,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    result = onlineResult
                )
            }

            cancellationToken?.throwIfCancellationRequested()
            onlineResult?.let { result ->
                attachLocalKaraokeIfAvailable(
                    context = appContext,
                    result = result,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    enabled = wordByWordEnabled
                )
            }
        }
    }

    private fun attachLocalKaraokeIfAvailable(
        context: Context,
        result: LyricsProviderResult,
        title: String,
        artist: String,
        durationMs: Long,
        enabled: Boolean
    ): LyricsProviderResult {
        if (!enabled) return result

        val cachedKaraokeLines = karaokeLyricsReader.read(
            context = context,
            title = title,
            artist = artist,
            durationMs = durationMs
        )
        if (cachedKaraokeLines.isEmpty()) return result

        return result.copy(karaokeLines = cachedKaraokeLines)
    }
}

internal fun interface LocalLyricsSaver {
    fun save(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        result: LyricsProviderResult
    )
}

internal fun interface KaraokeLyricsReader {
    fun read(context: Context, title: String, artist: String, durationMs: Long): List<KaraokeLine>
}

internal fun interface LyricsLookupLogger {
    fun logProviderFailure(
        provider: LyricsProvider,
        title: String,
        artist: String,
        durationMs: Long,
        error: Throwable,
        debug: Boolean
    )
}

private object AndroidLocalLyricsSaver : LocalLyricsSaver {
    override fun save(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        result: LyricsProviderResult
    ) {
        LyricsStorage.saveLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = durationMs,
            lyrics = LrcParser.mergeOriginalAndTranslationForStorage(
                lyrics = result.lyrics,
                translatedLyrics = result.translatedLyrics
            ),
            album = album,
            source = LyricsStorage.SOURCE_DOWNLOADED,
            provider = result.providerId,
            overwrite = true
        )
    }
}

private object AndroidKaraokeLyricsReader : KaraokeLyricsReader {
    override fun read(context: Context, title: String, artist: String, durationMs: Long): List<KaraokeLine> {
        return LyricsStorage.readKaraokeLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = durationMs
        )
    }
}

private object AndroidLyricsLookupLogger : LyricsLookupLogger {
    override fun logProviderFailure(
        provider: LyricsProvider,
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
}
