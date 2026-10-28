package com.andsi.airlyrics.app.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.search.manualUnavailableReason
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SearchQueryField {
    TITLE, ARTIST, ALBUM;

    fun value(song: SongIdentity): String = when (this) {
        TITLE -> song.title
        ARTIST -> song.artist
        ALBUM -> song.album
    }
}

internal interface LyricsSearchAccess {
    fun search(source: PlainLyricsSearchSource, query: SongIdentity, token: LyricsLookupCancellationToken): List<LyricsCandidate>
    fun fetch(candidate: LyricsCandidate, target: SongIdentity, token: LyricsLookupCancellationToken): ManualLyricsFetchResult
    fun protectedRevision(target: SongIdentity): String?
    fun apply(target: SongIdentity, result: LyricsProviderResult, token: LyricsLookupCancellationToken, approvedRevision: String?): Boolean
}

internal data class SourceResults(val loading: Boolean = false, val candidates: List<LyricsCandidate> = emptyList(), val error: Throwable? = null)
internal data class SearchPreview(val candidate: LyricsCandidate, val lyrics: LyricsProviderResult)
internal data class LyricsSearchState(
    val target: SongIdentity, val query: SongIdentity = target,
    val sources: Set<PlainLyricsSearchSource>,
    val results: Map<PlainLyricsSearchSource, SourceResults> = emptyMap(),
    val submittedQuery: SongIdentity? = null,
    val preview: SearchPreview? = null,
    val previewCandidate: LyricsCandidate? = null,
    val previewUnavailableReason: LyricsUnavailableReason? = null,
    val previewLoading: Boolean = false, val applying: Boolean = false,
    val confirmationRevision: String? = null,
    val error: Throwable? = null, val saved: Boolean = false,
    val moreConditions: Boolean = false
) {
    val canApply: Boolean
        get() = preview != null && preview.candidate == previewCandidate &&
            !previewLoading && !applying && previewUnavailableReason == null &&
            preview.lyrics.manualUnavailableReason() == null
}

/** Owns requests across recreation. Only application-scoped dependencies may be captured. */
internal class LyricsSearchSession(
    target: SongIdentity,
    sources: Set<PlainLyricsSearchSource>,
    private val access: LyricsSearchAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    val sourceOrder = (sources.toList() + PlainLyricsSearchSource.onlineSources).distinct()
    private val mutable = MutableStateFlow(LyricsSearchState(target, sources = sources))
    val state = mutable.asStateFlow()
    private val requests = mutableMapOf<String, Pair<LyricsLookupCancellationToken, Job>>()
    private var generation = 0L
    var resultsGeneration = 0L
        private set
    var resultsScroll = 0
    var activeSource: PlainLyricsSearchSource? = null
    val pagePositions = mutableMapOf<PlainLyricsSearchSource, android.os.Parcelable>()
    var previewScroll = 0

    fun editQuery(title: String, artist: String, album: String) {
        if (state.value.applying) return
        mutable.value = state.value.copy(query = state.value.query.copy(title=title,artist=artist,album=album),error=null)
    }
    fun restoreQuery(field: SearchQueryField) {
        val before = state.value
        if (before.applying) return
        val original = before.target
        val restored = when (field) {
            SearchQueryField.TITLE -> before.query.copy(title=original.title)
            SearchQueryField.ARTIST -> before.query.copy(artist=original.artist)
            SearchQueryField.ALBUM -> before.query.copy(album=original.album)
        }
        mutable.value = before.copy(query=restored,error=null)
    }
    fun setSources(sources: Set<PlainLyricsSearchSource>) {
        if (!state.value.applying) mutable.value = state.value.copy(sources=sourceOrder.filter { it in sources }.toSet())
    }
    fun toggleMore() { mutable.value = state.value.copy(moreConditions=!state.value.moreConditions) }
    fun switchTarget(target: SongIdentity) {
        if (state.value.applying) return
        cancelAll()
        activeSource=null
        resultsScroll=0
        resultsGeneration++
        pagePositions.clear()
        previewScroll=0
        mutable.value=LyricsSearchState(target,sources=state.value.sources)
    }
    fun search() {
        val before=state.value
        if (before.applying || before.sources.isEmpty() || listOf(before.query.title,before.query.artist,before.query.album).all { it.isBlank() }) return
        cancelAll()
        resultsScroll=0
        resultsGeneration++
        pagePositions.clear()
        mutable.value=before.copy(results=before.sources.associateWith { SourceResults(loading=true) },submittedQuery=before.query,
            preview=null,previewUnavailableReason=null,previewCandidate=null,previewLoading=false,error=null,confirmationRevision=null)
        before.sources.forEach { searchSource(it,before.query) }
    }
    fun retry(source: PlainLyricsSearchSource) {
        if (state.value.applying || source !in state.value.results) return
        val query=state.value.submittedQuery ?: return
        mutable.value=state.value.copy(results=state.value.results+(source to SourceResults(loading=true)))
        searchSource(source,query)
    }
    private fun searchSource(source: PlainLyricsSearchSource, query: SongIdentity) {
        launch(source.key,{ access.search(source,query,it) }, { candidates ->
            mutable.value=state.value.copy(results=state.value.results+(source to SourceResults(candidates=candidates.filter { it.source == source }.distinctBy { it.id }.sortedByDescending { it.score })))
        }, { error -> mutable.value=state.value.copy(results=state.value.results+(source to SourceResults(error=error))) })
    }
    fun preview(candidate: LyricsCandidate) {
        if (state.value.applying) return
        val target=state.value.target
        previewScroll=0
        mutable.value=state.value.copy(preview=null,previewUnavailableReason=null,previewCandidate=candidate,previewLoading=true,error=null)
        launch("preview",{ access.fetch(candidate,target,it) }, { result ->
            val fetched = (result as? ManualLyricsFetchResult.Available)?.lyrics
            val contentReason = fetched?.manualUnavailableReason()
            val lyrics = fetched?.takeIf { contentReason == null }
            val reason = (result as? ManualLyricsFetchResult.Unavailable)?.reason
                ?: contentReason
                ?: LyricsUnavailableReason.UNKNOWN.takeIf { lyrics == null }
            mutable.value=state.value.copy(preview=lyrics?.let { SearchPreview(candidate,it) },previewUnavailableReason=reason,previewLoading=false)
        }, { mutable.value=state.value.copy(previewLoading=false,error=it) })
    }
    fun backToResults() {
        if (state.value.applying) return
        cancel("preview")
        mutable.value=state.value.copy(preview=null,previewUnavailableReason=null,previewCandidate=null,previewLoading=false,confirmationRevision=null,error=null)
    }
    fun dismissConfirmation() { mutable.value=state.value.copy(confirmationRevision=null) }
    fun apply(approvedRevision: String? = null) {
        val before=state.value
        val preview=before.preview ?: return
        if (!before.canApply) return
        mutable.value=before.copy(applying=true,error=null,confirmationRevision=null)
        launch("apply",{ token ->
            val revision=access.protectedRevision(before.target)
            if (revision != null && revision != approvedRevision) revision
            else if (access.apply(before.target,preview.lyrics,token,approvedRevision)) null
            else access.protectedRevision(before.target) ?: error("Lyrics changed during apply")
        }, { revision ->
            mutable.value=state.value.copy(applying=false,saved=revision==null,confirmationRevision=revision)
        }, { mutable.value=state.value.copy(applying=false,error=it) })
    }
    private fun <T> launch(key: String, work: (LyricsLookupCancellationToken)->T, done:(T)->Unit, failed:(Throwable)->Unit) {
        cancel(key)
        val token=LyricsLookupCancellationToken(state.value.target.storageKey(),++generation)
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=withContext(io) { work(token) }
                if (requests[key]?.first === token && !token.isCancellationRequested) done(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (requests[key]?.first === token && !token.isCancellationRequested) failed(e) }
            catch (e: LinkageError) { if (requests[key]?.first === token && !token.isCancellationRequested) failed(e) }
            finally { if (requests[key]?.first === token) requests.remove(key) }
        }
        requests[key]=token to job
        job.start()
    }
    private fun cancel(key:String) { requests.remove(key)?.let { (token,job)-> token.cancel();job.cancel() } }
    private fun cancelAll() { requests.keys.toList().forEach(::cancel) }
    override fun onCleared() { cancelAll() }
}
