package com.andsi.airlyrics.app.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.model.SpotifyTrackId
import com.andsi.airlyrics.lyrics.search.SpotifyIdLyricsResult
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.search.manualUnavailableReason
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
import com.andsi.airlyrics.lyrics.search.ManualWordSource
import com.andsi.airlyrics.lyrics.search.ManualWordVersion
import com.andsi.airlyrics.lyrics.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

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
    fun resolveInternationalTitle(query: SongIdentity, token: LyricsLookupCancellationToken): SongIdentity?
    fun fetch(candidate: LyricsCandidate, target: SongIdentity, token: LyricsLookupCancellationToken): ManualLyricsFetchResult
    fun findBySpotifyId(id: String, mediaSong: SongIdentity?, target: SongIdentity, token: LyricsLookupCancellationToken): SpotifyIdLyricsResult?
    fun searchWordRecordings(source:ManualWordSource,query:SongIdentity,page:Int,keyword:String,token:LyricsLookupCancellationToken):ManualWordPage
    fun loadWordRecording(recording:ManualWordRecording,token:LyricsLookupCancellationToken):ManualWordDetails
    fun loadWordOption(option:ManualWordOption,token:LyricsLookupCancellationToken):ManualWordDetails
    fun protectedRevision(target: SongIdentity): String?
    fun apply(target: SongIdentity, result: LyricsProviderResult, token: LyricsLookupCancellationToken, approvedRevision: String?): Boolean
}

internal data class SourceResults(
    val loading: Boolean = false, val candidates: List<LyricsCandidate> = emptyList(),
    val error: Throwable? = null, val searched: Boolean = true,
    val spotifyId: String? = null, val query: SongIdentity? = null,
    val wordResults: Map<ManualWordSource, WordSourceResults> = emptyMap(),
    val wordQuery: SongIdentity? = null,
    val wordDetail: WordRecordingState? = null,
    // Keep published song order across pagination, retries, and lyric-detail navigation.
    val wordCatalogue: List<LyricsCandidate> = emptyList(),
)
internal data class WordSourceResults(
    val loading: Boolean = false,
    val error: Throwable? = null,
    val recordings: List<ManualWordRecording> = emptyList(), val page: Int = 0,
    val hasMore: Boolean = false, val keyword: String = "",
)
internal data class WordOptionState(
    val loading:Boolean=false, val version:ManualWordVersion?=null,
    val error:Throwable?=null, val plainTextOnly:Boolean=false,
)
internal data class WordRecordingState(
    val recording:ManualWordRecording, val loading:Boolean=false,
    val details:ManualWordDetails=ManualWordDetails(), val error:Throwable?=null,
    val options:Map<String,WordOptionState> = emptyMap(),
)
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
    val moreConditions: Boolean = false,
    val spotifyTrackId: String? = null
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
    private val io: CoroutineDispatcher = Dispatchers.IO,
    initialSpotifyTrackId: String? = null,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val sourceOrder = (sources.toList() + PlainLyricsSearchSource.onlineSources).distinct()
    private val mutable = MutableStateFlow(LyricsSearchState(target, sources = sources,
        spotifyTrackId = SpotifyTrackId.parse(initialSpotifyTrackId)))
    val state = mutable.asStateFlow()
    private val requests = mutableMapOf<String, Pair<LyricsLookupCancellationToken, Job>>()
    private var resolvingInternational = false
    private var internationalQuery: SongIdentity? = null
    private val spotifyLyrics = mutableMapOf<String, LyricsProviderResult>()
    private val wordDetails = linkedMapOf<String,WordRecordingState>()
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
    fun switchTarget(target: SongIdentity) = switchTarget(target, null)
    fun switchTarget(target: SongIdentity, spotifyTrackId: String?) {
        if (state.value.applying) return
        cancelAll()
        wordDetails.clear()
        resolvingInternational=false
        internationalQuery=null
        activeSource=null
        resultsScroll=0
        resultsGeneration++
        pagePositions.clear()
        previewScroll=0
        spotifyLyrics.clear()
        mutable.value=LyricsSearchState(target,sources=state.value.sources,spotifyTrackId=SpotifyTrackId.parse(spotifyTrackId))
    }
    fun search() {
        val before=state.value
        if (before.applying || before.sources.isEmpty() || listOf(before.query.title,before.query.artist,before.query.album).all { it.isBlank() }) return
        cancelAll()
        wordDetails.clear()
        resolvingInternational=true
        internationalQuery=null
        resultsScroll=0
        resultsGeneration++
        pagePositions.clear()
        mutable.value=before.copy(results=before.sources.associateWith { SourceResults(loading=true, query=before.query) },submittedQuery=before.query,
            preview=null,previewUnavailableReason=null,previewCandidate=null,previewLoading=false,error=null,confirmationRevision=null,
            )
        spotifyLyrics.clear()
        before.sources.forEach { searchSource(it,before.query) }
        if (PlainLyricsSearchSource.SPICY in before.sources && before.query == before.target) {
            before.spotifyTrackId?.let { searchSpotifyTrack(it, before.target) }
        }
        launch("international-title", { access.resolveInternationalTitle(before.query,it) }, { resolved ->
            finishInternationalResolution(before.sources,resolved)
        }, {
            finishInternationalResolution(before.sources,null)
        })
    }
    fun retry(source: PlainLyricsSearchSource) {
        if (state.value.applying || source !in state.value.results) return
        if (source==PlainLyricsSearchSource.NETEASE && state.value.results[source]?.wordQuery!=null) { retryWordFailures(); return }
        val query=state.value.results[source]?.query ?: state.value.submittedQuery ?: state.value.query
        val exactId = state.value.results[source]?.spotifyId
        if (source == PlainLyricsSearchSource.SPICY) {
            cancelSpicySearch()
            spotifyLyrics.clear()
        }
        mutable.value=state.value.copy(results=state.value.results+(source to SourceResults(loading=true, spotifyId=exactId, query=query)))
        if (exactId != null) {
            searchSpotifyTrack(exactId, null)
        } else {
            searchSource(source,query)
            if (source == PlainLyricsSearchSource.SPICY) {
                resolveSpicyTitle(query)
            } else if (query==state.value.submittedQuery) {
                internationalQuery?.let { searchInternationalSource(source,it) }
            }
            if (source == PlainLyricsSearchSource.SPICY && query == state.value.target) {
                state.value.spotifyTrackId?.let { searchSpotifyTrack(it, state.value.target) }
            }
        }
    }
    /** Explicit ID queries replace Spicy only; other providers keep their results and requests. */
    fun searchSpotifyId(value: String): Boolean {
        if (state.value.applying) return false
        val id = SpotifyTrackId.parse(value) ?: return false
        cancelSpicySearch()
        cancel("preview")
        spotifyLyrics.clear()
        val source = PlainLyricsSearchSource.SPICY
        activeSource = source
        pagePositions.remove(source)
        mutable.value = state.value.copy(
            results = state.value.results + (source to SourceResults(loading=true, spotifyId=id)),
            preview=null, previewCandidate=null, previewLoading=false, previewUnavailableReason=null,
            error=null, confirmationRevision=null
        )
        searchSpotifyTrack(id, null)
        return true
    }

    fun searchSpicyByTitle() {
        if (state.value.applying) return
        cancelSpicySearch()
        spotifyLyrics.clear()
        val source = PlainLyricsSearchSource.SPICY
        val query = state.value.query
        pagePositions.remove(source)
        mutable.value = state.value.copy(
            results=state.value.results + (source to SourceResults(loading=true, query=query))
        )
        searchSource(source, query)
        resolveSpicyTitle(query)
    }

    private fun resolveSpicyTitle(query: SongIdentity) {
        val source = PlainLyricsSearchSource.SPICY
        if (query == state.value.submittedQuery) {
            internationalQuery?.let { searchInternationalSource(source,it) }
            return
        }
        launch("spicy-title", { access.resolveInternationalTitle(query,it) }, { resolved ->
            if (resolved != null) searchInternationalSource(source,resolved)
            val previous=state.value.results[source] ?: return@launch
            mutable.value=state.value.copy(results=state.value.results +
                (source to previous.copy(loading=sourcePending(source,"spicy-title"))))
        }, {
            val previous=state.value.results[source] ?: return@launch
            mutable.value=state.value.copy(results=state.value.results +
                (source to previous.copy(loading=sourcePending(source,"spicy-title"))))
        })
    }

    private fun cancelSpicySearch() {
        cancel(PlainLyricsSearchSource.SPICY.key)
        cancel("international:spicy")
        cancel("spotify-id")
        cancel("spicy-title")
    }

    private fun searchSpotifyTrack(id: String, mediaSong: SongIdentity?) {
        val source = PlainLyricsSearchSource.SPICY
        val target = state.value.target
        launch("spotify-id", { access.findBySpotifyId(id, mediaSong, target, it) }, { found ->
            if (found != null) spotifyLyrics[id] = found.lyrics
            updateSource(source, "spotify-id", listOfNotNull(found?.candidate), null, original=false)
        }, { updateSource(source, "spotify-id", emptyList(), it, original=false) })
    }

    private fun searchSource(source: PlainLyricsSearchSource, query: SongIdentity) {
        launch(source.key,{ access.search(source,query,it) }, { candidates ->
            updateSource(source, source.key, candidates, null, original=true)
        }, { error -> updateSource(source, source.key, emptyList(), error, original=true) })
    }
    private fun finishInternationalResolution(sources: Set<PlainLyricsSearchSource>, resolved: SongIdentity?) {
        resolvingInternational=false
        internationalQuery=resolved
        if (resolved != null) {
            sources.forEach {
                if (state.value.results[it]?.wordQuery==null &&
                    (it != PlainLyricsSearchSource.SPICY || state.value.results[it]?.query == state.value.submittedQuery) &&
                    (it != PlainLyricsSearchSource.NETEASE || state.value.results[it]?.query == state.value.submittedQuery)) {
                    searchInternationalSource(it,resolved)
                }
            }
        } else {
            val results=state.value.results.toMutableMap()
            sources.forEach { source ->
                results[source]?.let { results[source]=it.copy(loading=sourcePending(source)) }
            }
            mutable.value=state.value.copy(results=results)
        }
    }
    private fun searchInternationalSource(source: PlainLyricsSearchSource, query: SongIdentity) {
        if (state.value.results[source]?.wordQuery!=null) return
        if (source == PlainLyricsSearchSource.SPICY && state.value.results[source]?.spotifyId != null) return
        val key="international:${source.key}"
        launch(key,{ access.search(source,query,it) }, { candidates ->
            updateSource(source,key,candidates,null,original=false)
        }, { error -> updateSource(source,key,emptyList(),error,original=false) })
    }
    private fun updateSource(source: PlainLyricsSearchSource, completedKey: String,
        candidates: List<LyricsCandidate>, error: Throwable?, original: Boolean) {
        val previous=state.value.results[source] ?: return
        if (previous.wordQuery!=null) return
        val ranked=candidates.filter { it.source == source && it.wordVersion == null }.distinctBy { it.id }.sortedByDescending { it.score }
        val originals=previous.candidates.filter { it.wordVersion == null }
        val pinned=(ranked+originals).filter { it.spotifyIdMatch }
        val merged=if (original) (pinned+ranked+originals).distinctBy { it.id }
            else (pinned+originals+ranked).distinctBy { it.id }
        val ordered=rankMergedCandidates(merged).sortedByDescending { it.spotifyIdMatch }
        val result=previous.copy(
            loading=sourcePending(source,completedKey), candidates=ordered,
            error=if (ordered.isNotEmpty()) null else previous.error ?: error
        )
        mutable.value=state.value.copy(results=state.value.results+(source to result))
    }
    private fun rankMergedCandidates(candidates: List<LyricsCandidate>): List<LyricsCandidate> {
        val original=state.value.submittedQuery ?: return candidates
        val alternative=internationalQuery ?: return candidates
        // Provider scores describe separate queries. Compare song evidence across both titles,
        // then keep each query's existing order for candidates with equal evidence.
        return candidates.withIndex().sortedWith(
            compareBy<IndexedValue<LyricsCandidate>> {
                minOf(matchTier(it.value.song,original),matchTier(it.value.song,alternative))
            }.thenBy { it.index }
        ).map { it.value }
    }
    private fun matchTier(candidate: SongIdentity, query: SongIdentity): Int {
        val title=SongIdentity.normalizeText(candidate.title)
        val expectedTitle=SongIdentity.normalizeText(query.title)
        val exactTitle=expectedTitle.isNotEmpty() && title==expectedTitle
        val relatedTitle=expectedTitle.isNotEmpty() && listOf(" ("," - "," [","（","【").any {
            title.startsWith(expectedTitle+it)
        }
        val expectedArtist=SongIdentity.normalizeText(query.artist)
        val artist=expectedArtist.isNotEmpty() && candidate.artist.split(',', ';', '、', '，').any {
            SongIdentity.normalizeText(it)==expectedArtist
        }
        val closeDuration=query.durationMs>0 && candidate.durationMs>0 &&
            abs(candidate.durationMs-query.durationMs)<=10_000
        return when {
            exactTitle && artist && closeDuration -> 0
            exactTitle && artist -> 1
            relatedTitle && artist && closeDuration -> 2
            relatedTitle && artist -> 3
            exactTitle && closeDuration -> 4
            artist && closeDuration -> 5
            exactTitle -> 6
            relatedTitle -> 7
            else -> 8
        }
    }
    private fun sourcePending(source: PlainLyricsSearchSource, completedKey: String? = null): Boolean {
        val result=state.value.results[source]
        if(result?.wordQuery!=null) return result.wordResults.values.any { it.loading }
        // Callbacks run before launch removes their own request entry.
        return (resolvingInternational && state.value.results[source]?.spotifyId == null &&
            state.value.results[source]?.query == state.value.submittedQuery) ||
            (listOf(source.key,"international:${source.key}") +
                when(source) {
                    PlainLyricsSearchSource.SPICY -> listOf("spotify-id","spicy-title")
                    PlainLyricsSearchSource.NETEASE -> listOf("netease-title")
                    else -> emptyList()
                }).any {
                it != completedKey && it in requests
            }
    }
    fun preview(candidate: LyricsCandidate) {
        if (state.value.applying) return
        candidate.recording?.let { openWordRecording(it); return }
        if(candidate.wordOption!=null && candidate.wordVersion==null) { loadWordOption(candidate.wordOption); return }
        mutable.value=state.value.copy(confirmationRevision=null)
        val target=state.value.target
        val cached = candidate.wordVersion?.lyrics ?: if (candidate.spotifyIdMatch) spotifyLyrics[candidate.id]
            ?.takeIf { it.access?.isExpired() != true } else null
        if (cached != null) {
            cancel("preview")
            previewScroll=0
            mutable.value=state.value.copy(preview=SearchPreview(candidate,cached),
                previewCandidate=candidate,previewLoading=false,previewUnavailableReason=null,error=null)
            return
        }
        previewScroll=0
        mutable.value=state.value.copy(preview=null,previewUnavailableReason=null,previewCandidate=candidate,previewLoading=true,error=null)
        launch("preview",{ access.fetch(candidate,target,it) }, { result ->
            val fetched = (result as? ManualLyricsFetchResult.Available)?.lyrics
            val contentReason = fetched?.manualUnavailableReason()
            val lyrics = fetched?.takeIf { contentReason == null }
            val reason = (result as? ManualLyricsFetchResult.Unavailable)?.reason
                ?: contentReason
                ?: LyricsUnavailableReason.UNKNOWN.takeIf { lyrics == null }
            val loaded=lyrics?.let { SearchPreview(candidate,it) }
            mutable.value=state.value.copy(preview=loaded,previewUnavailableReason=reason,previewLoading=false)
        }, { mutable.value=state.value.copy(previewLoading=false,error=it) })
    }
    /** Like Spotify ID search, replaces one source page without changing the main query. */
    fun searchWordLyrics(query:SongIdentity):Boolean {
        val before=state.value
        if(before.applying || query.title.isBlank()) return false
        cancelNeteaseSearch()
        wordDetails.clear()
        cancel("preview")
        activeSource=PlainLyricsSearchSource.NETEASE
        pagePositions.remove(PlainLyricsSearchSource.NETEASE)
        previewScroll=0
        mutable.value=before.copy(
            results=before.results + (PlainLyricsSearchSource.NETEASE to SourceResults(query=query,wordQuery=query)),
            preview=null,previewCandidate=null,previewLoading=false,
            previewUnavailableReason=null,error=null,confirmationRevision=null)
        ManualWordSource.entries.forEach { searchWordSource(it,query) }
        return true
    }
    fun searchNeteaseByTitle() {
        if(state.value.applying) return
        cancelNeteaseSearch()
        val source=PlainLyricsSearchSource.NETEASE
        val query=state.value.query
        pagePositions.remove(source)
        mutable.value=state.value.copy(results=state.value.results +
            (source to SourceResults(loading=true,query=query)))
        searchSource(source,query)
        if(query==state.value.submittedQuery) {
            internationalQuery?.let { searchInternationalSource(source,it) }
        } else {
            launch("netease-title",{ access.resolveInternationalTitle(query,it) },{ resolved ->
                if(resolved!=null) searchInternationalSource(source,resolved)
                val previous=state.value.results[source] ?: return@launch
                mutable.value=state.value.copy(results=state.value.results +
                    (source to previous.copy(loading=sourcePending(source,"netease-title"))))
            },{
                val previous=state.value.results[source] ?: return@launch
                mutable.value=state.value.copy(results=state.value.results +
                    (source to previous.copy(loading=sourcePending(source,"netease-title"))))
            })
        }
    }
    private fun cancelNeteaseSearch() {
        listOf("netease","international:netease","netease-title","word-detail","word-download","word-presentation").forEach(::cancel)
        ManualWordSource.entries.forEach { cancel("word-search:${it.key}") }
    }
    fun retryWordFailures() {
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        if(result.wordDetail!=null) { openWordRecording(result.wordDetail.recording,true); return }
        if(result.error!=null) { publishWordPage(result.copy(error=null)); return }
        ManualWordSource.entries.filter { result.wordResults[it]?.error!=null }.forEach(::retryWordFailure)
    }
    fun retryWordFailure(provider:ManualWordSource) {
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        val query=result.wordQuery ?: return
        val before=result.wordResults[provider] ?: return
        if(!state.value.applying && !before.loading && before.error!=null) searchWordSource(provider,query,before.page+1)
    }
    fun moreWordRecordings(provider:ManualWordSource) {
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        val before=result.wordResults[provider] ?: return
        if(!state.value.applying && !before.loading && before.hasMore) searchWordSource(provider,result.wordQuery ?: return,before.page+1)
    }
    private fun searchWordSource(provider:ManualWordSource,query:SongIdentity,page:Int=1) {
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        val existing=result.wordResults[provider] ?: WordSourceResults()
        updateWordSearch(provider,existing.copy(loading=true,error=null))
        launch("word-search:${provider.key}",{ token ->
            val found=access.searchWordRecordings(provider,query,page,existing.keyword,token)
            withContext(computation) {
                found.copy(recordings=(existing.recordings+found.recordings).distinctBy { it.key })
            }
        },{ found ->
            updateWordSearch(provider,existing.copy(loading=false,
                recordings=found.recordings,
                page=found.page,hasMore=found.hasMore,keyword=found.keyword,error=found.partialError))
        },{ updateWordSearch(provider,existing.copy(loading=false,error=it)) })
    }
    private fun updateWordSearch(provider:ManualWordSource,found:WordSourceResults) {
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        publishWordPage(result.copy(wordResults=result.wordResults+(provider to found)))
    }
    private fun publishWordPage(result:SourceResults) {
        val source=PlainLyricsSearchSource.NETEASE
        cancel("word-presentation")
        mutable.value=state.value.copy(results=state.value.results+(source to result.copy(loading=true,error=null)))
        // Publish one complete batch. Partial provider callbacks update progress, not the visible list.
        if(result.wordDetail?.loading==true ||
            (result.wordDetail==null && result.wordResults.values.any { it.loading })) return
        launch("word-presentation",{
            withContext(computation) { buildWordCandidates(result) }
        },{ candidates ->
            val current=state.value.results[source] ?: return@launch
            mutable.value=state.value.copy(results=state.value.results+(source to current.copy(
                candidates=candidates,
                wordCatalogue=if(current.wordDetail==null) candidates else current.wordCatalogue,
                loading=current.wordDetail?.loading ?: current.wordResults.values.any { it.loading },
                error=current.wordDetail?.error)))
        },{ error ->
            val current=state.value.results[source] ?: return@launch
            mutable.value=state.value.copy(results=state.value.results+(source to current.copy(loading=false,error=error)))
        })
    }

    /** Pure model work: no View, adapter, or mutable session state is touched on the worker. */
    private fun buildWordCandidates(result:SourceResults):List<LyricsCandidate> {
        val detail=result.wordDetail
        return if(detail==null) {
            val seen=result.wordCatalogue.mapTo(HashSet()) { it.id }
            // Rank only unseen songs. A later, better-scored page must not move already-read rows.
            val additions=ManualWordSource.entries.flatMap { result.wordResults[it]?.recordings.orEmpty() }
                .filter { seen.add("recording:${it.key}") }
                .sortedWith(compareByDescending<ManualWordRecording> { it.related }
                    .thenBy { it.requiresConfirmation }.thenByDescending { it.score })
                .map { LyricsCandidate(PlainLyricsSearchSource.NETEASE,"recording:${it.key}",it.song,it.score,recording=it) }
            if(additions.isEmpty()) result.wordCatalogue else result.wordCatalogue+additions
        } else {
            detail.details.versions.map { wordCandidate(it) } + detail.details.options.map { option ->
                val loaded=detail.options[option.key]?.version
                LyricsCandidate(PlainLyricsSearchSource.NETEASE,"option:${option.key}",option.song,0.0,wordVersion=loaded,wordOption=option)
            }
        }
    }
    private fun wordCandidate(version:ManualWordVersion)=LyricsCandidate(PlainLyricsSearchSource.NETEASE,
        "words:${version.key}",version.song,0.0,wordVersion=version)
    fun backToWordRecordings() {
        if(state.value.applying) return
        cancel("word-detail"); cancel("word-download")
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        result.wordDetail?.let { cacheWordDetails(it.copy(loading=false,options=it.options.mapValues { (_,s)->s.copy(loading=false) })) }
        publishWordPage(result.copy(wordDetail=null))
    }
    fun openWordRecording(recording:ManualWordRecording,refresh:Boolean=false) {
        if(state.value.applying) return
        cancel("word-detail"); cancel("word-download"); cancel("preview")
        wordDetails.replaceAll { _,value->value.copy(loading=false,
            options=value.options.mapValues { (_,s)->s.copy(loading=false) }) }
        mutable.value=state.value.copy(preview=null,previewCandidate=null,previewLoading=false,
            previewUnavailableReason=null,error=null,confirmationRevision=null)
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        val cached=wordDetails[recording.key]
        if(cached!=null && !refresh) { publishWordPage(result.copy(wordDetail=cached)); return }
        val before=cached ?: WordRecordingState(recording)
        publishWordPage(result.copy(wordDetail=before.copy(loading=true,error=null)))
        launch("word-detail",{ access.loadWordRecording(recording,it) },{ found ->
            val current=state.value.results[PlainLyricsSearchSource.NETEASE]?.wordDetail ?: return@launch
            setWordDetail(current.copy(loading=false,details=found,error=found.partialError))
        },{ error ->
            val current=state.value.results[PlainLyricsSearchSource.NETEASE]?.wordDetail ?: return@launch
            setWordDetail(current.copy(loading=false,error=error))
        })
    }
    private fun cacheWordDetails(detail:WordRecordingState) {
        wordDetails.remove(detail.recording.key)
        wordDetails[detail.recording.key]=detail
        if(wordDetails.size>16) wordDetails.remove(wordDetails.keys.first())
    }
    private fun setWordDetail(detail:WordRecordingState) {
        cacheWordDetails(detail)
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        if(result.wordDetail?.recording?.key==detail.recording.key) publishWordPage(result.copy(wordDetail=detail))
    }
    private fun loadWordOption(option:ManualWordOption) {
        val result=state.value.results[PlainLyricsSearchSource.NETEASE] ?: return
        var detail=result.wordDetail ?: return
        if(detail.options[option.key]?.loading==true) return
        cancel("word-download")
        detail=detail.copy(options=detail.options.mapValues { (_,s)->s.copy(loading=false) })
        setWordDetail(detail.copy(options=detail.options+(option.key to WordOptionState(loading=true))))
        launch("word-download",{ access.loadWordOption(option,it) },{ found ->
            val current=state.value.results[PlainLyricsSearchSource.NETEASE]?.wordDetail ?: return@launch
            val version=found.versions.firstOrNull()
            setWordDetail(current.copy(options=current.options+(option.key to WordOptionState(
                version=version,error=found.partialError,plainTextOnly=found.plainTextOnly || version==null))))
            if(version!=null) preview(wordCandidate(version))
        },{ error ->
            val current=state.value.results[PlainLyricsSearchSource.NETEASE]?.wordDetail ?: return@launch
            setWordDetail(current.copy(options=current.options+(option.key to WordOptionState(error=error))))
        })
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
    private fun <T> launch(key: String, work: suspend (LyricsLookupCancellationToken)->T, done:(T)->Unit, failed:(Throwable)->Unit) {
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
