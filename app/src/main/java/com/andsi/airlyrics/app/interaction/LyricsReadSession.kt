package com.andsi.airlyrics.app.interaction

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class LyricsReadKind { CURRENT, RECENT, SAVED }
internal data class LyricsReadRequest(
    val kind: LyricsReadKind,
    val media: CurrentMediaInfo?,
    val limit: Int,
    val wordEnabled: Boolean,
    val offset: Long
)
internal data class LyricsReadSnapshot(
    val media: CurrentMediaInfo?,
    val info: LyricsStorage.LocalPlainLyricsInfo?,
    val wordByWord: Boolean,
    val wordEnabled: Boolean,
    val offset: Long,
    val current: LyricsStorage.LocalLyricsItem?,
    val items: List<LyricsStorage.LocalLyricsItem>
)

/** A bounded, per-ViewModel cache of raw data. It never retains UI mapping callbacks. */
internal class LyricsReadSession(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val read: suspend (LyricsReadRequest) -> LyricsReadSnapshot = defaultReader(context.applicationContext)
) {
    private val context = context.applicationContext
    private data class Key(val song: SongIdentity?, val revision: Long, val directory: String, val offset: Long, val word: Boolean, val limit: Int)
    private data class Entry(val key: Key, val task: Deferred<Result<LyricsReadSnapshot>>)
    private val entries = mutableMapOf<LyricsReadKind, Entry>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun load(kind: LyricsReadKind, media: CurrentMediaInfo?, limit: Int = 8, force: Boolean = false): Deferred<Result<LyricsReadSnapshot>> {
        val target = media?.takeUnless { it.isEmpty || kind == LyricsReadKind.SAVED }
        val offset = target?.let { LyricsOffsetStore.getOffsetMs(context, it.toSongIdentity()) } ?: 0L
        val word = LyricsSettingsStore.isWordByWordLyricsEnabled(context)
        val key = Key(target?.toSongIdentity(), LyricsStorage.currentRevision(), LyricsStorage.getLyricsDirRawPath(context), offset, word, limit)
        val old = entries[kind]
        if (!force && old?.key == key && !old.task.isCancelled && (!old.task.isCompleted || old.task.getCompleted().isSuccess)) return old.task
        old?.task?.cancel()
        val task = scope.async(dispatcher) {
            try {
                val result = read(LyricsReadRequest(kind, target, limit, word, offset))
                currentCoroutineContext().ensureActive()
                Result.success(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        entries[kind] = Entry(key, task)
        return task
    }
}

private fun defaultReader(context: Context): suspend (LyricsReadRequest) -> LyricsReadSnapshot = { request ->
    val target = request.media
    val info = target?.let { LyricsStorage.getLocalPlainLyricsInfo(context, it.title, it.artist, it.durationMs) }
    val hasWord = target?.let { LyricsStorage.hasWordByWordLyrics(context, it.title, it.artist, it.durationMs) } == true
    val current = info?.let {
        LyricsStorage.LocalLyricsItem(it.plainFileName, it.updatedAt,
            LyricsStorage.localLyricsFileSize(context, it.plainFileName), it.title, it.artist, it.album,
            it.durationMs, it.indexKey, it.plainSource, it.plainProvider, true, hasWord)
    }
    val items = when (request.kind) {
        LyricsReadKind.CURRENT -> emptyList()
        LyricsReadKind.RECENT -> LyricsStorage.listRecentLyrics(context, request.limit)
        LyricsReadKind.SAVED -> LyricsStorage.listAllLyrics(context)
    }
    LyricsReadSnapshot(target, info, hasWord, request.wordEnabled, request.offset, current, items)
}
