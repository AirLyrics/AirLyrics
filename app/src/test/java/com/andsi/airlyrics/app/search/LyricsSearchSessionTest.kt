package com.andsi.airlyrics.app.search

import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
import com.andsi.airlyrics.lyrics.search.ManualWordSource
import com.andsi.airlyrics.lyrics.search.ManualWordVersion
import com.andsi.airlyrics.lyrics.search.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsSearchSessionTest {
    private val song = SongIdentity("Original", "Artist", "Album", 200000)
    private val source = PlainLyricsSearchSource.NETEASE
    private val candidate = LyricsCandidate(source,"42",song.copy(title="Alias"),0.9)
    private class FakeAccess: LyricsSearchAccess {
        val idQueries = mutableListOf<String>()
        var idResultMissing = false
        var previewFetches = 0
        val wordQueries = mutableListOf<Pair<SongIdentity,ManualWordSource>>()
        val pages = mutableListOf<Int>()
        var catalogue:(SongIdentity,ManualWordSource,Int,LyricsLookupCancellationToken)->ManualWordPage = { q,s,p,_ ->
            ManualWordPage(listOf(ManualWordRecording(s,"42",q)),p)
        }
        var detailLoads=0
        var downloads=0
        var details:(ManualWordRecording,LyricsLookupCancellationToken)->ManualWordDetails = { r,_ ->
            ManualWordDetails(listOf(ManualWordVersion(r.id,r.source,r.song,
                LyricsProviderResult(r.source.key,r.source.key,"[00:01.00]Line"))))
        }
        var download:(ManualWordOption,LyricsLookupCancellationToken)->ManualWordDetails = { _,_->ManualWordDetails() }
        var appliedLyrics: LyricsProviderResult? = null
        override fun searchWordRecordings(source:ManualWordSource,query:SongIdentity,page:Int,keyword:String,token:LyricsLookupCancellationToken):ManualWordPage {
            wordQueries+=query to source; pages+=page
            return catalogue(query,source,page,token)
        }
        override fun loadWordRecording(recording:ManualWordRecording,token:LyricsLookupCancellationToken):ManualWordDetails {
            detailLoads++
            return details(recording,token)
        }
        override fun loadWordOption(option:ManualWordOption,token:LyricsLookupCancellationToken):ManualWordDetails {
            downloads++
            return download(option,token)
        }
        override fun findBySpotifyId(id:String,mediaSong:SongIdentity?,target:SongIdentity,token:LyricsLookupCancellationToken):com.andsi.airlyrics.lyrics.search.SpotifyIdLyricsResult? {
            idQueries += id
            if (idResultMissing) return null
            return com.andsi.airlyrics.lyrics.search.SpotifyIdLyricsResult(
                LyricsCandidate(PlainLyricsSearchSource.SPICY,id,mediaSong ?: SongIdentity("","","",0),0.0,spotifyIdMatch=true),
                LyricsProviderResult("spicy","Spicy","[00:01.00]Exact ID lyrics"),
            )
        }
        var searched: SongIdentity? = null
        val searchQueries = mutableListOf<SongIdentity>()
        val resolvedQueries = mutableListOf<SongIdentity>()
        var internationalQuery: SongIdentity? = null
        var failResolution = false
        val searchResults = mutableMapOf<String,List<LyricsCandidate>>()
        val failingTitles = mutableSetOf<String>()
        var fetchedTarget: SongIdentity? = null
        var appliedTarget: SongIdentity? = null
        var revision: String? = null
        var failApply = false
        var emptyPreview = false
        var failPreview = false
        var previewText = "[00:01.00]Lyrics"
        val failingSources = mutableSetOf<PlainLyricsSearchSource>()
        override fun search(source:PlainLyricsSearchSource,query:SongIdentity,token:LyricsLookupCancellationToken):List<LyricsCandidate> {
            searched=query
            searchQueries += query
            if ((source in failingSources) || (query.title in failingTitles)) error("offline")
            return searchResults[query.title]?.map { it.copy(source=source) }
                ?: listOf(LyricsCandidate(source,"42",query,0.9))
        }
        override fun resolveInternationalTitle(query:SongIdentity,token:LyricsLookupCancellationToken):SongIdentity? {
            resolvedQueries += query
            if (failResolution) error("iTunes unavailable")
            return internationalQuery
        }
        override fun fetch(candidate:LyricsCandidate,target:SongIdentity,token:LyricsLookupCancellationToken):ManualLyricsFetchResult {
            previewFetches++
            if (failPreview) error("offline")
            if (emptyPreview) return ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.NO_TIMELINE)
            fetchedTarget=target
            return ManualLyricsFetchResult.Available(LyricsProviderResult(candidate.source.key,"Source",previewText))
        }
        override fun protectedRevision(target:SongIdentity)=revision
        override fun apply(target:SongIdentity,result:LyricsProviderResult,token:LyricsLookupCancellationToken,approvedRevision:String?):Boolean {
            if(failApply) error("disk failure")
            appliedTarget=target
            appliedLyrics=result
            return true
        }
    }
    private fun TestScope.withSession(sources:Set<PlainLyricsSearchSource> = setOf(source),
        target:SongIdentity = song, spotifyId:String? = null, computation:CoroutineDispatcher?=null,
        block: TestScope.(LyricsSearchSession,FakeAccess)->Unit) {
        val dispatcher=StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val access=FakeAccess()
            block(LyricsSearchSession(target,sources,access,dispatcher,spotifyId,computation=computation ?: dispatcher),access)
        } finally { Dispatchers.resetMain() }
    }
    private fun wordVersion(source:ManualWordSource, query:SongIdentity, id:String="42"):ManualWordVersion {
        val lines=listOf(WordByWordLine(1000,2000,"你好",listOf(
            WordByWordSegment("你",1000,1400),WordByWordSegment("好",1400,2000))))
        return ManualWordVersion(id,source,query,
            LyricsProviderResult(source.key,source.key,"[00:01.000]你好",
                wordByWordLines=lines,matchedTitle=query.title))
    }
    private class QueuedComputation:CoroutineDispatcher() {
        private val tasks=ArrayDeque<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) { tasks.addLast(block) }
        fun runQueued() { while(tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun completeBatchIncludesLowSimilarityRowsAndWaitsForLocalPreparation()=runTest {
        val worker=QueuedComputation()
        withSession(computation=worker) { session,access ->
            access.catalogue={ q,s,p,_ -> ManualWordPage(
                listOf(ManualWordRecording(s,"low",q,related=false)),p,hasMore=true) }
            session.searchWordLyrics(song);runCurrent()
            assertTrue(session.state.value.results.getValue(source).loading)
            assertTrue(session.state.value.results.getValue(source).candidates.isEmpty())
            worker.runQueued();runCurrent()
            val preparing=session.state.value.results.getValue(source)
            assertTrue(preparing.wordResults.values.none { it.loading })
            assertTrue(preparing.loading)
            assertTrue(preparing.candidates.isEmpty())
            worker.runQueued();runCurrent()
            val ready=session.state.value.results.getValue(source)
            assertFalse(ready.loading)
            assertEquals(ManualWordSource.entries.size,ready.candidates.size)
            assertTrue(ready.candidates.all { it.recording?.related==false })
            assertEquals(ManualWordSource.entries.map { 1 },access.pages)
            assertEquals(0,access.detailLoads)
        }
    }

    @Test fun newWordQuerySupersedesAQueuedBatch()=runTest {
        val worker=QueuedComputation()
        withSession(computation=worker) { session,_ ->
            session.searchWordLyrics(song);runCurrent()
            worker.runQueued();runCurrent()
            val replacement=song.copy(title="New query")
            session.searchWordLyrics(replacement);runCurrent()
            worker.runQueued();runCurrent()
            worker.runQueued();runCurrent()
            val ready=session.state.value.results.getValue(source)
            assertFalse(ready.loading)
            assertEquals(replacement,ready.wordQuery)
            assertEquals(ManualWordSource.entries.size,ready.candidates.size)
            assertTrue(ready.candidates.all { it.song==replacement })
        }
    }

    @Test fun regularSearchCannotBeOverwrittenByQueuedWordPreparation()=runTest {
        val worker=QueuedComputation()
        withSession(computation=worker) { session,_ ->
            session.searchWordLyrics(song);runCurrent()
            worker.runQueued();runCurrent()
            session.searchNeteaseByTitle();runCurrent()
            worker.runQueued();runCurrent()
            val ready=session.state.value.results.getValue(source)
            assertNull(ready.wordQuery)
            assertFalse(ready.loading)
            assertTrue(ready.candidates.isNotEmpty())
            assertTrue(ready.candidates.all { it.recording==null })
        }
    }

    @Test fun ordinarySearchNeverStartsWordLookup()=runTest {
        withSession { session,access ->
            session.search();runCurrent()
            val rows=session.state.value.results.getValue(source).candidates
            assertEquals(1,rows.size)
            assertNull(rows.single().wordVersion)
            session.preview(rows.single());runCurrent()
            assertTrue(access.wordQueries.isEmpty())
            assertEquals(LyricsPrecision.LINE,session.state.value.preview!!.lyrics.precision)
        }
    }
    @Test fun catalogueSearchIsLazyAndKeepsQuerySeparateFromApplyTarget()=runTest {
        withSession(setOf(source,PlainLyricsSearchSource.LRCLIB)) { session,access ->
            session.search();runCurrent()
            val other=session.state.value.results[PlainLyricsSearchSource.LRCLIB]
            val edited=SongIdentity("Edited","Singer","",0)
            session.searchWordLyrics(edited);runCurrent()
            assertEquals(ManualWordSource.entries.map { edited },access.wordQueries.map { it.first })
            assertEquals(0,access.detailLoads)
            assertEquals(0,access.downloads)
            assertEquals(song,session.state.value.target)
            assertEquals(other,session.state.value.results[PlainLyricsSearchSource.LRCLIB])
            val rows=session.state.value.results.getValue(source).candidates
            assertEquals(ManualWordSource.entries.size,rows.size)
            assertTrue(rows.all { it.wordVersion==null && it.recording!=null })
            session.preview(rows.first());runCurrent()
            assertEquals(1,access.detailLoads)
            val versions=session.state.value.results.getValue(source).candidates
            session.preview(versions.single());runCurrent()
            assertTrue(session.state.value.canApply)
            assertNull(access.appliedTarget)
            session.apply();runCurrent()
            assertEquals(song,access.appliedTarget)
        }
    }
    @Test fun pageTwoAndLowRelatedRecordingsRemainDiscoverableWithoutDownloads()=runTest {
        withSession { session,access ->
            access.catalogue={ q,s,p,_ -> ManualWordPage(
                (1..20).map { ManualWordRecording(s,"$p:$it",q,related=it!=20) },p,p==1,"fixed keyword") }
            session.searchWordLyrics(song);runCurrent()
            assertEquals(20*ManualWordSource.entries.size,session.state.value.results.getValue(source).candidates.size)
            session.moreWordRecordings(ManualWordSource.NETEASE)
            session.moreWordRecordings(ManualWordSource.NETEASE)
            runCurrent()
            assertEquals(ManualWordSource.entries.map { 1 }+2,access.pages)
            assertEquals(20*(ManualWordSource.entries.size+1),session.state.value.results.getValue(source).candidates.size)
            assertEquals(0,access.detailLoads)
        }
    }
    @Test fun laterPagesAppendWithoutReorderingAndKeepTheirOrderAfterDetailNavigation()=runTest {
        withSession { session,access ->
            access.catalogue={ q,s,p,_ ->
                val weak=ManualWordRecording(s,"weak",q,score=0.1,related=false)
                val songs=if(p==1) listOf(weak,ManualWordRecording(s,"best",q,score=0.6)) else listOf(
                    weak,ManualWordRecording(s,"new-low",q,score=0.8),
                    ManualWordRecording(s,"new-high",q,score=1.0))
                ManualWordPage(songs,p,hasMore=p==1)
            }
            session.searchWordLyrics(song);runCurrent()
            val first=session.state.value.results.getValue(source).candidates
            val expected=ManualWordSource.entries.map { "recording:${it.key}:best" }+
                ManualWordSource.entries.map { "recording:${it.key}:weak" }
            assertEquals(expected,first.map { it.id })

            // The footer requests another page from each source, even if scores exceed the first batch.
            ManualWordSource.entries.forEach(session::moreWordRecordings)
            assertTrue(session.state.value.results.getValue(source).loading)
            assertEquals(first,session.state.value.results.getValue(source).candidates)
            runCurrent()
            val more=session.state.value.results.getValue(source).candidates
            assertEquals(first,more.take(first.size))
            assertEquals(ManualWordSource.entries.map { "recording:${it.key}:new-high" }+
                ManualWordSource.entries.map { "recording:${it.key}:new-low" },
                more.drop(first.size).map { it.id })
            assertEquals(more.size,more.map { it.id }.toSet().size)
            assertEquals(0,access.detailLoads)

            session.preview(more.last());runCurrent()
            assertNotNull(session.state.value.results.getValue(source).wordDetail)
            session.backToWordRecordings();runCurrent()
            assertEquals(more,session.state.value.results.getValue(source).candidates)

            val replacement=song.copy(title="Another song")
            session.searchWordLyrics(replacement);runCurrent()
            val refreshed=session.state.value.results.getValue(source).candidates
            assertEquals(expected,refreshed.map { it.id })
            assertTrue(refreshed.all { it.song==replacement })
        }
    }

    @Test fun failedSourceRetryKeepsSuccessfulSongsAndRetriesTheFailedPage()=runTest {
        withSession { session,access ->
            access.catalogue={ q,s,p,_ ->
                if(s==ManualWordSource.NETEASE) error("offline")
                ManualWordPage(listOf(ManualWordRecording(s,"hash",q)),p)
            }
            session.searchWordLyrics(song);runCurrent()
            assertEquals(setOf("kugou","qq"),session.state.value.results.getValue(source).candidates.map { it.providerKey }.toSet())
            access.catalogue={ q,s,p,_ -> ManualWordPage(listOf(ManualWordRecording(s,"id",q)),p) }
            session.retryWordFailure(ManualWordSource.NETEASE);runCurrent()
            assertEquals(ManualWordSource.entries.map { 1 }+1,access.pages)
            assertEquals(ManualWordSource.entries.size,session.state.value.results.getValue(source).candidates.size)
            assertEquals(1,access.wordQueries.count { it.second==ManualWordSource.KUGOU })
        }
    }
    @Test fun anyFailedWordSourceLeavesTheOtherProvidersSelectable()=runTest {
        for(failed in ManualWordSource.entries) withSession { session,access ->
            access.catalogue={ q,s,p,_ ->
                if(s==failed) error("offline")
                ManualWordPage(listOf(ManualWordRecording(s,"42",q)),p)
            }
            session.searchWordLyrics(song);runCurrent()
            val result=session.state.value.results.getValue(source)
            assertEquals(ManualWordSource.entries.filter { it!=failed }.map { it.key }.toSet(),
                result.candidates.map { it.providerKey }.toSet())
            assertNotNull(result.wordResults.getValue(failed).error)
            assertEquals(0,access.detailLoads)
            session.preview(result.candidates.first());runCurrent()
            session.preview(session.state.value.results.getValue(source).candidates.single());runCurrent()
            assertTrue(session.state.value.canApply)
        }
    }
    @Test fun qqWordPreviewUsesItsOwnProviderAndIsInvalidatedByChangingTheTarget()=runTest {
        withSession { session,access ->
            access.details={ r,_ -> ManualWordDetails(listOf(wordVersion(r.source,r.song,r.id))) }
            session.searchWordLyrics(song);runCurrent()
            val qq=session.state.value.results.getValue(source).candidates.single { it.providerKey=="qq" }
            session.preview(qq);runCurrent()
            session.preview(session.state.value.results.getValue(source).candidates.single());runCurrent()
            assertEquals("qq",session.state.value.preview!!.lyrics.plainProviderId)
            assertNull(access.appliedTarget)
            session.switchTarget(song.copy(title="Next"))
            assertNull(session.state.value.preview)
            session.apply();runCurrent()
            assertNull(access.appliedTarget)
        }
    }
    @Test fun lineAndWordVersionsSurvivePartialErrorsAndReuseCachedDetails()=runTest {
        withSession { session,access ->
            access.details={ r,_ -> ManualWordDetails(listOf(
                ManualWordVersion(r.id,r.source,r.song,LyricsProviderResult(r.source.key,r.source.key,"[00:01.00]Line")),
                wordVersion(r.source,r.song,r.id)),partialError=IllegalStateException("translation failed")) }
            session.searchWordLyrics(song);runCurrent()
            val row=session.state.value.results.getValue(source).candidates.first()
            session.preview(row);runCurrent()
            val result=session.state.value.results.getValue(source)
            assertEquals(2,result.candidates.size)
            assertNotNull(result.wordDetail!!.error)
            assertEquals(2,result.candidates.map { it.id }.distinct().size)
            result.candidates.forEach { session.preview(it);runCurrent();assertTrue(session.state.value.canApply) }
            session.backToResults()
            session.backToWordRecordings()
            session.preview(row);runCurrent()
            assertEquals(1,access.detailLoads)
        }
    }
    @Test fun kugouListsEveryLyricCandidateAndDownloadsOnlyTheChosenOne()=runTest {
        withSession { session,access ->
            access.details={ r,_ -> ManualWordDetails(options=(1..12).map {
                ManualWordOption(r,it.toString(),r.song,"key","Creator $it")
            }) }
            access.download={ o,_ -> ManualWordDetails(listOf(wordVersion(o.recording.source,o.song,o.key))) }
            session.searchWordLyrics(song);runCurrent()
            val kg=session.state.value.results.getValue(source).candidates.single { it.providerKey=="kugou" }
            session.preview(kg);runCurrent()
            assertEquals(0,access.downloads)
            val last=session.state.value.results.getValue(source).candidates.last()
            assertEquals("12",last.wordOption!!.id)
            session.preview(last);runCurrent()
            assertEquals(1,access.downloads)
            assertEquals("kugou",session.state.value.preview!!.lyrics.plainProviderId)
            session.backToResults()
            session.preview(session.state.value.results.getValue(source).candidates.last());runCurrent()
            assertEquals(1,access.downloads)
        }
    }
    @Test fun lateCatalogueAndDetailsCannotOverwriteNewQueriesOrTargets()=runTest {
        withSession { session,access ->
            var old:LyricsLookupCancellationToken?=null
            access.catalogue={ q,s,p,token ->
                if(q.title==song.title) { old=token;session.searchWordLyrics(song.copy(title="New")) }
                ManualWordPage(listOf(ManualWordRecording(s,"id",q)),p)
            }
            session.searchWordLyrics(song);runCurrent()
            assertTrue(old!!.isCancellationRequested)
            assertTrue(session.state.value.results.getValue(source).candidates.all { it.song.title=="New" })
            access.details={ _,token ->
                old=token;session.switchTarget(song.copy(title="Next"));ManualWordDetails()
            }
            session.preview(session.state.value.results.getValue(source).candidates.first());runCurrent()
            assertTrue(old!!.isCancellationRequested)
            assertTrue(session.state.value.results.isEmpty())
        }
    }
    @Test fun switchingRecordingsCancelsQueuedDetailsAndReturningRestoresSongs()=runTest {
        withSession { session,access ->
            session.searchWordLyrics(song);runCurrent()
            val songs=session.state.value.results.getValue(source).candidates
            session.preview(songs.first())
            session.preview(songs.last());runCurrent()
            assertEquals(1,access.detailLoads)
            assertEquals(ManualWordSource.QQ,session.state.value.results.getValue(source).wordDetail!!.recording.source)
            session.backToWordRecordings();runCurrent()
            assertEquals(songs,session.state.value.results.getValue(source).candidates)
        }
    }
    @Test fun pendingInternationalResolutionCannotOverwriteWordCatalogue()=runTest {
        withSession { session,access ->
            access.internationalQuery=song.copy(title="Alias")
            session.search()
            session.searchWordLyrics(song);runCurrent()
            assertTrue(session.state.value.results.getValue(source).candidates.all { it.recording!=null })
            session.searchNeteaseByTitle();runCurrent()
            assertNull(session.state.value.results.getValue(source).wordQuery)
            assertTrue(session.state.value.results.getValue(source).candidates.all { it.recording==null })
        }
    }

    @Test fun failedLyricDoesNotBlockAnotherOptionAndSwitchCancelsDownload()=runTest {
        withSession { session,access ->
            access.details={ r,_ -> ManualWordDetails(options=(1..4).map { ManualWordOption(r,"$it",r.song,"key") }) }
            session.searchWordLyrics(song);runCurrent()
            session.preview(session.state.value.results.getValue(source).candidates.single { it.providerKey=="kugou" });runCurrent()
            val rows=session.state.value.results.getValue(source).candidates
            access.download={ _,_->error("bad KRC") }
            session.preview(rows.first());runCurrent()
            assertNotNull(session.state.value.results.getValue(source).wordDetail!!.options.values.single().error)
            access.download={ o,_->ManualWordDetails(listOf(wordVersion(o.recording.source,o.song,o.key))) }
            session.preview(rows.last());runCurrent()
            assertTrue(session.state.value.canApply)
            session.backToResults()
            var token:LyricsLookupCancellationToken?=null
            access.download={ _,t->token=t;session.backToWordRecordings();ManualWordDetails() }
            session.preview(rows[1]);runCurrent()
            assertTrue(token!!.isCancellationRequested)
            assertNull(session.state.value.preview)
            session.preview(session.state.value.results.getValue(source).candidates.last());runCurrent()
            assertTrue(session.state.value.results.getValue(source).wordDetail!!.options.values.none { it.loading })
        }
    }
    @Test fun sessionIdIsPinnedAlongsideNormalResultsAndPreviewReusesLyrics()=runTest {
        val id="5l94Up12BJSjhH7Mhr2ji4"
        val spicy=PlainLyricsSearchSource.SPICY
        withSession(setOf(spicy,source),spotifyId=id) { session,access ->
            session.search();runCurrent()
            val rows=session.state.value.results.getValue(spicy).candidates
            assertEquals(listOf(id,"42"),rows.map { (_, trackId) -> trackId })
            assertTrue(rows.first().spotifyIdMatch)
            assertFalse(rows.last().spotifyIdMatch)
            assertFalse(session.state.value.results.getValue(spicy).loading)
            session.preview(rows.first());runCurrent()
            assertEquals(0,access.previewFetches)
            assertTrue(session.state.value.canApply)
            assertNull(access.appliedTarget)
        }
    }

    @Test fun sameIdFromNormalSearchIsDeduplicatedAndKeepsItsBadge()=runTest {
        val id="5l94Up12BJSjhH7Mhr2ji4"
        val spicy=PlainLyricsSearchSource.SPICY
        withSession(setOf(spicy),spotifyId=id) { session,access ->
            access.searchResults[song.title]=listOf(candidate.copy(id=id))
            session.search();runCurrent()
            val rows=session.state.value.results.getValue(spicy).candidates
            assertEquals(1,rows.size)
            assertTrue(rows.single().spotifyIdMatch)
        }
    }

    @Test fun explicitIdReplacesSpicyAndPendingInternationalSearchCannotMixResults()=runTest {
        val id="5l94Up12BJSjhH7Mhr2ji4"
        val spicy=PlainLyricsSearchSource.SPICY
        withSession(setOf(spicy,source)) { session,access ->
            access.internationalQuery=song.copy(title="International")
            session.search()
            assertTrue(session.searchSpotifyId(id))
            runCurrent()
            assertEquals(listOf(id),session.state.value.results.getValue(spicy).candidates.map { it.id })
            assertEquals(id,session.state.value.results.getValue(spicy).spotifyId)
            assertFalse(session.state.value.results.getValue(spicy).loading)
            assertTrue(session.state.value.results.getValue(source).candidates.isNotEmpty())
            assertEquals(song,session.state.value.target)
            session.preview(session.state.value.results.getValue(spicy).candidates.single())
            assertEquals("",session.state.value.previewCandidate?.song?.title)
            assertNull(access.appliedTarget)
        }
    }

    @Test fun directIdWorksBeforeSongSearchAndRetriesWithoutDiscovery()=runTest {
        val id="5l94Up12BJSjhH7Mhr2ji4"
        val spicy=PlainLyricsSearchSource.SPICY
        withSession(setOf(spicy)) { session,access ->
            access.idResultMissing=true
            session.searchSpotifyId(id);runCurrent()
            assertTrue(access.searchQueries.isEmpty())
            assertTrue(access.resolvedQueries.isEmpty())
            assertTrue(session.state.value.results.getValue(spicy).candidates.isEmpty())
            access.idResultMissing=false
            session.retry(spicy);runCurrent()
            assertEquals(listOf(id,id),access.idQueries)
            assertEquals(1,session.state.value.results.getValue(spicy).candidates.size)
            assertFalse(session.state.value.results.getValue(spicy).loading)
            session.searchSpicyByTitle();runCurrent()
            assertEquals(listOf("42"),session.state.value.results.getValue(spicy).candidates.map { it.id })
            assertNull(session.state.value.results.getValue(spicy).spotifyId)
        }
    }

    @Test fun returningToSongSearchUsesEditedConditionsAndLeavesOtherSourceRetryUnchanged()=runTest {
        val spicy=PlainLyricsSearchSource.SPICY
        withSession(setOf(spicy,source)) { session,access ->
            session.search();runCurrent()
            val other=session.state.value.results.getValue(source)
            session.searchSpotifyId("5l94Up12BJSjhH7Mhr2ji4");runCurrent()
            session.editQuery("Another song","Other artist","")
            access.internationalQuery=song.copy(title="Alternative title",artist="Other artist")
            session.searchSpicyByTitle();runCurrent()
            assertNull(session.state.value.results.getValue(spicy).spotifyId)
            assertFalse(session.state.value.results.getValue(spicy).loading)
            assertTrue(access.searchQueries.any { it.title=="Another song" })
            assertTrue(access.searchQueries.any { it.title=="Alternative title" })
            assertEquals(other,session.state.value.results.getValue(source))
            session.retry(source);runCurrent()
            assertEquals(song,access.searchQueries.last())
        }
    }

    @Test fun newIdCancelsPreviousIdAndEditedQueryDoesNotReuseSessionId()=runTest {
        val id="5l94Up12BJSjhH7Mhr2ji4"
        val next="0X5X6D3kZyDEG8Im1LQaVf"
        val spicy=PlainLyricsSearchSource.SPICY
        withSession(setOf(spicy),spotifyId=id) { session,access ->
            session.searchSpotifyId(id)
            session.searchSpotifyId(next);runCurrent()
            assertEquals(listOf(next),session.state.value.results.getValue(spicy).candidates.map { it.id })
            assertFalse(session.searchSpotifyId("spotify:episode:$id"))
            session.editQuery("Another song","Artist","")
            session.search();runCurrent()
            assertEquals(listOf(next),access.idQueries)
            assertFalse(session.state.value.results.getValue(spicy).candidates.any { it.spotifyIdMatch })
        }
    }

    @Test fun editedQueryAndPreviewDoNotChangeBindingOrSave()=runTest {
        withSession { session,access ->
            session.editQuery("Translated title","Other artist","")
            session.search();runCurrent()
            assertEquals("Translated title",access.searched?.title)
            assertEquals(song,session.state.value.target)
            session.preview(candidate);runCurrent()
            assertEquals(song,access.fetchedTarget)
            assertNull(access.appliedTarget)
            session.apply();runCurrent()
            assertEquals(song,access.appliedTarget)
            assertTrue(session.state.value.saved)
        }
    }
    @Test fun failedSourceDoesNotRemoveSuccessfulCandidatesAndCanRetry()=runTest {
        withSession { session,access ->
            val failing=PlainLyricsSearchSource.LRCLIB
            access.failingSources.add(failing)
            session.setSources(setOf(source,failing));session.search();runCurrent()
            assertEquals(1,session.state.value.results[source]?.candidates?.size)
            assertNotNull(session.state.value.results[failing]?.error)
            access.failingSources.clear();session.retry(failing);runCurrent()
            assertEquals(1,session.state.value.results[failing]?.candidates?.size)
        }
    }
    @Test fun noCandidatesCannotApply()=runTest {
        withSession { session,access ->
            access.searchResults[song.title]=emptyList()
            session.search();runCurrent()
            assertTrue(session.state.value.results[source]!!.candidates.isEmpty())
            assertFalse(session.state.value.canApply)
            session.apply();runCurrent()
            assertNull(access.appliedTarget)
        }
    }
    @Test fun switchingTargetCancelsPendingPreviewAndRetainsSourceChoice()=runTest {
        withSession { session,access ->
            session.preview(candidate)
            val next=song.copy(title="Next")
            session.switchTarget(next);runCurrent()
            assertNull(session.state.value.preview)
            assertNull(access.fetchedTarget)
            assertEquals(next,session.state.value.query)
            assertEquals(setOf(source),session.state.value.sources)
        }
    }
    @Test fun restoringOriginalDetailsKeepsBindingAndSaveFailureKeepsPreview()=runTest {
        withSession { session,access ->
            session.editQuery("Edited title","Edited artist","Edited album")
            SearchQueryField.entries.forEach(session::restoreQuery);runCurrent()
            assertEquals(song,session.state.value.query)
            assertEquals(song,session.state.value.target)
            assertNull(access.searched)
            assertNull(access.appliedTarget)
            session.preview(candidate);runCurrent()
            access.failApply=true
            session.apply();runCurrent()
            assertFalse(session.state.value.saved)
            assertNotNull(session.state.value.preview)
            assertNotNull(session.state.value.error)
        }
    }
    @Test fun changedProtectedRevisionRequiresFreshConfirmation()=runTest {
        withSession { session,access ->
            session.preview(candidate);runCurrent()
            access.revision="first"
            session.apply();runCurrent()
            assertEquals("first",session.state.value.confirmationRevision)
            assertNull(access.appliedTarget)
            access.revision="edited-again"
            session.apply("first");runCurrent()
            assertEquals("edited-again",session.state.value.confirmationRevision)
            session.apply("edited-again");runCurrent()
            assertTrue(session.state.value.saved)
        }
    }
    @Test fun newerSearchWinsBeforeQueuedOldWorkRuns()=runTest {
        withSession { session,access ->
            access.internationalQuery=song.copy(title="International")
            access.searchResults["Original"]=listOf(LyricsCandidate(source,"old",song,0.9))
            access.searchResults["New query"]=listOf(LyricsCandidate(source,"new",song.copy(title="New query"),0.9))
            access.searchResults["International"]=listOf(LyricsCandidate(source,"alias",song.copy(title="International"),1.0))
            session.search()
            session.editQuery("New query","Artist","")
            session.search();runCurrent()
            assertEquals(listOf("New query"),access.resolvedQueries.map { it.title })
            assertEquals(listOf("new","alias"),session.state.value.results[source]?.candidates?.map { it.id })
        }
    }

    @Test fun internationalCandidatesMergeBySourceIdAfterOriginalRanking()=runTest {
        withSession(setOf(source,PlainLyricsSearchSource.LRCLIB)) { session,access ->
            val alternative=song.copy(title="International",artist="Other artist",album="Other album")
            access.internationalQuery=alternative
            access.searchResults[song.title]=(1..8).map { id ->
                LyricsCandidate(source,id.toString(),song.copy(title="Original $id"),1.0-id/100.0)
            }
            access.searchResults[alternative.title]=(7..12).map { id ->
                LyricsCandidate(source,id.toString(),alternative.copy(title="International $id"),2.0-id/100.0)
            }
            session.search();runCurrent()
            assertEquals(listOf(song),access.resolvedQueries)
            assertEquals(2,access.searchQueries.count { it == song })
            assertEquals(2,access.searchQueries.count { it == alternative })
            session.state.value.results.values.forEach { result ->
                assertFalse(result.loading)
                assertEquals((1..12).map(Int::toString),result.candidates.map { it.id })
                assertEquals(song.copy(title="Original 7"),result.candidates[6].song)
                assertEquals(alternative.copy(title="International 9"),result.candidates[8].song)
            }
        }
    }

    @Test fun ariaByNanatsukazeOutranksOtherSongsWithTheSameTitle()=runTest {
        val original=SongIdentity("アリア","ナナツカゼ","アリア",226000)
        val alternative=SongIdentity("Aria","Nanatsukaze","Aria - Single",226000)
        withSession(target=original) { session,access ->
            access.internationalQuery=alternative
            access.searchResults[original.title]=(1..30).map { number ->
                LyricsCandidate(source,"original-$number",original.copy(artist="Other $number"),0.99)
            }
            access.searchResults[alternative.title]=listOf(
                LyricsCandidate(source,"international-other",alternative.copy(artist="Other"),0.99),
                LyricsCandidate(source,"nanatsukaze-aria",alternative.copy(album="Aria"),0.4)
            )
            session.search();runCurrent()
            assertEquals(listOf(original,alternative),access.searchQueries)
            val candidates=session.state.value.results[source]!!.candidates
            assertEquals(32,candidates.size)
            assertEquals("nanatsukaze-aria",candidates.first().id)
            assertEquals(alternative.copy(album="Aria"),candidates.first().song)
        }
    }

    @Test fun failedInternationalResolutionKeepsOriginalCandidates()=runTest {
        withSession { session,access ->
            access.failResolution=true
            session.search();runCurrent()
            assertEquals(listOf(song),access.resolvedQueries)
            assertEquals(listOf(song),access.searchQueries)
            assertEquals(listOf("42"),session.state.value.results[source]?.candidates?.map { it.id })
            assertFalse(session.state.value.results[source]!!.loading)
            assertNull(session.state.value.results[source]?.error)
        }
    }

    @Test fun internationalCandidatesRemainAvailableWhenOriginalSearchFails()=runTest {
        withSession { session,access ->
            val alternative=song.copy(title="International")
            access.internationalQuery=alternative
            access.failingTitles.add(song.title)
            session.search();runCurrent()
            assertEquals(alternative,session.state.value.results[source]?.candidates?.single()?.song)
            assertNull(session.state.value.results[source]?.error)
        }
    }

    @Test fun unparseablePreviewStaysOpenAndCannotApply()=runTest {
        withSession { session,access ->
            access.emptyPreview=true
            session.preview(candidate);runCurrent()
            assertEquals(candidate,session.state.value.previewCandidate)
            assertNull(session.state.value.preview)
            assertFalse(session.state.value.previewLoading)
            assertNull(session.state.value.error)
            assertFalse(session.state.value.canApply)
            session.apply();runCurrent()
            assertNull(access.appliedTarget)
            session.backToResults()
            assertNull(session.state.value.previewCandidate)
        }
    }
    @Test fun unusableCandidateCannotApplyPreviousPreviewAndRetryRestoresApply()=runTest {
        withSession { session,access ->
            session.preview(candidate);runCurrent()
            assertTrue(session.state.value.canApply)
            val emptyCandidate=candidate.copy(id="empty")
            for (content in listOf("[ti:Title]", "[00:01.00]")) {
                access.previewText=content
                session.preview(emptyCandidate)
                assertFalse(session.state.value.canApply)
                session.apply();runCurrent()
                assertNull(session.state.value.preview)
                assertFalse(session.state.value.canApply)
                session.apply();runCurrent()
                assertNull(access.appliedTarget)
                assertFalse(session.state.value.saved)
            }
            access.previewText="[00:01.00]Actual lyrics"
            session.preview(emptyCandidate);runCurrent()
            assertTrue(session.state.value.canApply)
            session.apply();runCurrent()
            assertEquals(song,access.appliedTarget)
        }
    }
    @Test fun emptyTextIsAnApplicableChoiceWithoutUnavailableFeedback()=runTest {
        withSession { session,access ->
            session.preview(candidate);runCurrent()
            access.previewText=""
            session.preview(candidate.copy(id="empty"))
            assertFalse(session.state.value.canApply)
            runCurrent()
            assertTrue(session.state.value.canApply)
            assertEquals("",session.state.value.preview!!.lyrics.plainLrc)
            assertNull(session.state.value.previewUnavailableReason)
            session.apply();runCurrent()
            assertEquals(song,access.appliedTarget)
            assertTrue(session.state.value.saved)
        }
    }
    @Test fun failedPreviewStaysOpenAndCanRetry()=runTest {
        withSession { session,access ->
            access.failPreview=true
            session.preview(candidate);runCurrent()
            assertEquals(candidate,session.state.value.previewCandidate)
            assertNotNull(session.state.value.error)
            access.failPreview=false
            session.preview(candidate);runCurrent()
            assertNotNull(session.state.value.preview)
            assertNull(session.state.value.error)
        }
    }
    @Test fun identicalIdsRemainBoundToTheirOwnSource()=runTest {
        withSession { session,_ ->
            val second=PlainLyricsSearchSource.LRCLIB
            session.setSources(setOf(source,second))
            session.search();runCurrent()
            assertEquals(source,session.state.value.results[source]!!.candidates.single().source)
            assertEquals(second,session.state.value.results[second]!!.candidates.single().source)
            session.activeSource=second
            session.retry(source);runCurrent()
            assertEquals(second,session.activeSource)
            session.setSources(setOf(source))
            assertTrue(second in session.state.value.results)
            session.search();runCurrent()
            assertFalse(second in session.state.value.results)
        }
    }
    @Test fun backCancelsQueuedPreviewAndNewCandidateWins()=runTest {
        withSession { session,_ ->
            session.preview(candidate)
            session.backToResults();runCurrent()
            assertNull(session.state.value.previewCandidate)
            assertNull(session.state.value.preview)
            val next=candidate.copy(id="new")
            session.preview(candidate)
            session.preview(next);runCurrent()
            assertEquals(next,session.state.value.preview!!.candidate)
        }
    }
    @Test fun unavailableReasonClearsOnRetryBackSearchAndTargetChange()=runTest {
        withSession { session,access ->
            access.emptyPreview=true
            session.preview(candidate);runCurrent()
            assertEquals(LyricsUnavailableReason.NO_TIMELINE,session.state.value.previewUnavailableReason)
            access.emptyPreview=false
            session.preview(candidate)
            assertNull(session.state.value.previewUnavailableReason)
            runCurrent()
            assertNotNull(session.state.value.preview)
            access.emptyPreview=true
            session.preview(candidate);runCurrent()
            session.backToResults()
            assertNull(session.state.value.previewUnavailableReason)
            session.preview(candidate);runCurrent()
            session.search();runCurrent()
            assertNull(session.state.value.previewUnavailableReason)
            session.preview(candidate);runCurrent()
            session.switchTarget(song.copy(title="Next"));runCurrent()
            assertNull(session.state.value.previewUnavailableReason)
        }
    }
    @Test fun cancelledEmptyPreviewCannotReplaceANewerSuccessfulPreview()=runTest {
        withSession { session,access ->
            access.emptyPreview=true
            session.preview(candidate)
            access.emptyPreview=false
            val next=candidate.copy(id="next")
            session.preview(next);runCurrent()
            assertEquals(next,session.state.value.preview?.candidate)
            assertNull(session.state.value.previewUnavailableReason)
            access.emptyPreview=true
            session.preview(candidate)
            session.backToResults();runCurrent()
            assertNull(session.state.value.previewUnavailableReason)
        }
    }

    @Test fun configuredOrderSurvivesSelectionCompletionAndRetry()=runTest {
        val order=listOf(PlainLyricsSearchSource.SPICY,PlainLyricsSearchSource.LRCLIB,source)
        withSession(order.toSet()) { session,access ->
            assertEquals(order,session.state.value.sources.toList())
            session.setSources(order.asSequence().drop(1).toSet())
            session.setSources((order.drop(1)+order.first()).toSet())
            assertEquals(order,session.state.value.sources.toList())
            access.failingSources.add(order.first())
            session.search()
            assertEquals(order,session.state.value.results.keys.toList())
            runCurrent()
            assertEquals(order,session.state.value.results.keys.toList())
            access.failingSources.clear()
            session.retry(order.first());runCurrent()
            assertEquals(order,session.state.value.results.keys.toList())
            session.setSources((order.reversed()+PlainLyricsSearchSource.MUSIXMATCH).toSet())
            assertEquals(order+PlainLyricsSearchSource.MUSIXMATCH,session.state.value.sources.toList())
        }
    }

    @Test fun eachResetOnlyRestoresItsOwnFieldIncludingEmptyOriginals()=runTest {
        withSession { session,_ ->
            session.editQuery("New title","New artist","New album")
            session.restoreQuery(SearchQueryField.ARTIST)
            assertEquals(song.copy(title="New title",album="New album"),session.state.value.query)
            session.restoreQuery(SearchQueryField.TITLE)
            assertEquals(song.copy(album="New album"),session.state.value.query)
            session.restoreQuery(SearchQueryField.ALBUM)
            assertEquals(song,session.state.value.query)
            session.switchTarget(song.copy(album=""))
            session.editQuery("New title","New artist","New album")
            session.restoreQuery(SearchQueryField.ALBUM)
            assertEquals("",session.state.value.query.album)
            assertEquals("New title",session.state.value.query.title)
            assertEquals("New artist",session.state.value.query.artist)
        }
    }

}
