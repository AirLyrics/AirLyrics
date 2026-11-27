package com.andsi.airlyrics.lyrics

import android.content.Context
import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.providers.LocalPlainLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.ManualLyricsSelectionStore
import com.andsi.airlyrics.lyrics.storage.PlainLyricsStorageOps

internal class LyricsResultCommitter(context: Context) {
    private val context = context.applicationContext
    private val publisher = BroadcastLyricsChangedPublisher(this.context)

    fun commitLookup(
        lease: LyricsSearchCoordinator.Lease,
        target: SongIdentity,
        request: PlainLyricsSearchRequest,
        result: LyricsProviderResult,
        savePlain: Boolean,
        wordByWordEnabled: Boolean,
    ): LyricsProviderResult {
        val committed = commit(lease, LyricsChange.libraryChanged()) {
            localResultAfterFetch(request, target, wordByWordEnabled)?.let {
                return@commit ManualLyricsSelectionStore.CommitResult(it, saved = false)
            }
            ManualLyricsSelectionStore.commit(
                context, target, request.searchMode, result, manual = false, savePlain = savePlain,
            )
        }
        return checkNotNull(committed).lyrics
    }

    fun applySelection(
        target: SongIdentity,
        result: LyricsProviderResult,
        token: LyricsLookupCancellationToken,
        approvedRevision: String?,
    ): Boolean {
        val mode = LyricsSearchMode.Original
        return LyricsSearchCoordinator.begin(target, token, LyricsSearchOrigin.Manual, mode).use { lease ->
            commit(lease, LyricsChange.updated(target)) {
                require(result.access?.isExpired() != true) { "Preview expired" }
                val revision = protectedRevision(target)
                if (revision != null && revision != approvedRevision) return@commit null
                ManualLyricsSelectionStore.commit(context, target, mode, result, manual = true, savePlain = true)
            } != null
        }
    }

    fun protectedRevision(target: SongIdentity): String? {
        val info = LyricsStorage.withStorageLock {
            PlainLyricsStorageOps.getLocalPlainLyricsInfo(context, target.title, target.artist, target.durationMs)
        } ?: return null
        if (info.plainSource !in protectedSources) return null
        return "${info.indexKey}:${info.updatedAt}:${info.plainSource}"
    }

    private fun localResultAfterFetch(
        request: PlainLyricsSearchRequest,
        target: SongIdentity,
        wordByWordEnabled: Boolean,
    ): LyricsProviderResult? {
        val local = LocalPlainLyricsProvider.fetch(request).getOrNull() ?: return null
        if (!wordByWordEnabled) return local
        val words = LyricsStorage.readWordByWordLyrics(context, target.title, target.artist, target.durationMs)
        return if (words.isEmpty()) local else local.copy(wordByWordLines = words)
    }

    private fun commit(
        lease: LyricsSearchCoordinator.Lease,
        change: LyricsChange,
        write: () -> ManualLyricsSelectionStore.CommitResult?,
    ): ManualLyricsSelectionStore.CommitResult? {
        val committed = lease.commit {
            LyricsStorage.withStorageLock {
                lease.token.throwIfCancellationRequested()
                write()
            }
        }
        if (committed?.saved == true) publisher.publish(change)
        return committed
    }

    private companion object {
        val protectedSources = setOf(
            LyricsStorage.SOURCE_MANUAL_IMPORT,
            LyricsStorage.SOURCE_MANUAL_EDIT,
            LyricsStorage.SOURCE_LEGACY,
            LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK
        )
    }
}
