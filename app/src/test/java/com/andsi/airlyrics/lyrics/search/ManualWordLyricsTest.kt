package com.andsi.airlyrics.lyrics.search

import com.andsi.airlyrics.core.model.*
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManualWordLyricsTest : LyricsStorageTestBase() {
    private val target=SongIdentity("Playing","Artist","Album",200000)
    private val candidate=LyricsCandidate(PlainLyricsSearchSource.NETEASE,"123",
        target.copy(title="Chosen version"),0.9)
    private fun token()=LyricsLookupCancellationToken(target.storageKey(),1)
    private fun payload(source:String,id:String="123") = JSONObject("""
        {"ok":true,"versions":[{"provider":"$source","id":"$id",
        "track":{"id":"123","title":"Chosen version","artist":"Artist","album":"Album","duration_ms":200000},
        "lrc":"[00:01.000]你好","translated_lrc":"[00:01.000]Hello",
        "lines":[{"startMs":1000,"endMs":2000,"text":"你好","tokens":[
        {"text":"你","startMs":1000,"endMs":1400},{"text":"好","startMs":1400,"endMs":2000}]}]}]}
    """.trimIndent())

    @Test fun catalogueRequestCarriesOnlyExplicitQueryAndPagination() {
        val requests=mutableListOf<JSONObject>()
        val service=ManualLyricsSearch(context) { raw,_ ->
            requests+=JSONObject(raw)
            """{"ok":true,"recordings":[],"page":2,"has_more":false,"keyword":"Song"}"""
        }
        val edited=SongIdentity("Edited","Singer","",0)
        for(source in ManualWordSource.entries) {
            service.searchWordRecordings(source,edited,2,"Song",token())
            val request=requests.last()
            assertEquals("word_catalog",request.getString("operation"))
            assertEquals(2,request.getInt("page"))
            assertEquals("Song",request.getString("keyword"))
            assertEquals("",request.getJSONObject("track").getString("id"))
            assertEquals(0L,request.getJSONObject("track").getLong("duration_ms"))
            assertEquals("",request.getJSONObject("track").getString("album"))
        }
        assertNull(ManualLyricsSelectionStore.read(context,target))
    }
    private fun recording(source:ManualWordSource)=ManualWordRecording(source,candidate.id,candidate.song)

    @Test fun lookupUsesTheSelectedSongAndNeverWritesUntilApply() {
        val requests=mutableListOf<JSONObject>()
        val service=ManualLyricsSearch(context) { raw,_ ->
            val request=JSONObject(raw)
            requests+=request
            payload(request.getString("provider")).toString()
        }
        for(source in ManualWordSource.entries) {
            val version=service.loadWordRecording(recording(source),token()).versions.single()
            val request=requests.last()
            assertEquals("word_options",request.getString("operation"))
            assertEquals(source.key,request.getString("provider"))
            assertEquals("123",request.getJSONObject("track").getString("id"))
            assertEquals("Chosen version",request.getJSONObject("track").getString("title"))
            assertEquals(source.key,version.lyrics.plainProviderId)
            assertEquals(LyricsPrecision.WORD,version.lyrics.precision)
            assertNull(ManualLyricsSelectionStore.read(context,target))
            assertNull(LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs))
        }
    }
    @Test fun appliedWordTimelineAndActualProviderSurviveANewReaderForEitherSource() {
        for(source in ManualWordSource.entries) {
            val service=ManualLyricsSearch(context) { _,_->payload(source.key).toString() }
            val chosen=service.loadWordRecording(recording(source),token()).versions.single().lyrics
            assertTrue(service.apply(target,chosen,token(),service.protectedRevision(target)))
            val stored=ManualLyricsSelectionStore.read(context,target)!!
            assertEquals(chosen.wordByWordLines,stored.result.wordByWordLines)
            assertEquals(chosen.plainLrc,stored.result.plainLrc)
            assertEquals(chosen.translatedLrc,stored.result.translatedLrc)
            assertEquals(source.key,stored.info.plainProvider)
            assertTrue(stored.result.selectedManually)
        }
    }
    @Test fun plainChoiceAfterWordsDoesNotResurrectTheOldWordTimeline() {
        val service=ManualLyricsSearch(context) { _,_->payload("kugou").toString() }
        val words=service.loadWordRecording(recording(ManualWordSource.KUGOU),token()).versions.single().lyrics
        assertTrue(service.apply(target,words,token(),null))
        val line=LyricsProviderResult("netease","NetEase","[00:01.000]Other line")
        assertTrue(service.apply(target,line,token(),service.protectedRevision(target)))
        val stored=ManualLyricsSelectionStore.read(context,target)!!
        assertTrue(stored.result.wordByWordLines.isEmpty())
        assertEquals("netease",stored.info.plainProvider)
    }
    @Test fun emptyIsDifferentFromFailureAndMalformedTimingNeverGetsAWordLabel() {
        val empty=ManualLyricsSearch(context) { _,_->"""{"ok":true,"versions":[]}""" }
        assertTrue(empty.loadWordRecording(recording(ManualWordSource.NETEASE),token()).versions.isEmpty())
        val offline=ManualLyricsSearch(context) { _,_->"""{"ok":false,"error_type":"NetworkError"}""" }
        val failure=runCatching { offline.loadWordRecording(recording(ManualWordSource.KUGOU),token()) }.exceptionOrNull()
        assertEquals("kugou",(failure as LyricsLookupException).providerId)
        assertEquals(LyricsLookupErrorType.NetworkError,failure.errorType)
        for(bad in listOf(
            payload("netease","other"),
            payload("netease").apply { getJSONArray("versions").getJSONObject(0).getJSONArray("lines")
                .getJSONObject(0).put("tokens",org.json.JSONArray()) }
        )) {
            val service=ManualLyricsSearch(context) { _,_->bad.toString() }
            assertTrue(runCatching { service.loadWordRecording(recording(ManualWordSource.NETEASE),token()) }.isFailure)
        }
        assertNull(ManualLyricsSelectionStore.read(context,target))
    }
}
