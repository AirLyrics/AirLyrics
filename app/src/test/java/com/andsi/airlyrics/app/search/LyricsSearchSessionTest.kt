package com.andsi.airlyrics.app.search

import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
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
            if(source in failingSources || query.title in failingTitles) error("offline")
            return searchResults[query.title]?.map { it.copy(source=source) }
                ?: listOf(LyricsCandidate(source,"42",query,0.9))
        }
        override fun resolveInternationalTitle(query:SongIdentity,token:LyricsLookupCancellationToken):SongIdentity? {
            resolvedQueries += query
            if (failResolution) error("iTunes unavailable")
            return internationalQuery
        }
        override fun fetch(candidate:LyricsCandidate,target:SongIdentity,token:LyricsLookupCancellationToken):ManualLyricsFetchResult {
            if (failPreview) error("offline")
            if (emptyPreview) return ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.NO_TIMELINE)
            fetchedTarget=target
            return ManualLyricsFetchResult.Available(LyricsProviderResult(candidate.source.key,"Source",previewText))
        }
        override fun protectedRevision(target:SongIdentity)=revision
        override fun apply(target:SongIdentity,result:LyricsProviderResult,token:LyricsLookupCancellationToken,approvedRevision:String?):Boolean {
            if(failApply) error("disk failure")
            appliedTarget=target
            return true
        }
    }
    private fun TestScope.withSession(sources:Set<PlainLyricsSearchSource> = setOf(source),
        target:SongIdentity = song, block: TestScope.(LyricsSearchSession,FakeAccess)->Unit) {
        val dispatcher=StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val access=FakeAccess()
            block(LyricsSearchSession(target,sources,access,dispatcher),access)
        } finally { Dispatchers.resetMain() }
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
            session.setSources(order.drop(1).toSet())
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
