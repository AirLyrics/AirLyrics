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
class ManualLyricsSearchTest: LyricsStorageTestBase() {
    private val target=SongIdentity("Original","Artist","Album",200000)
    private val candidate=LyricsCandidate(PlainLyricsSearchSource.NETEASE,"123",target.copy(title="Alias"),0.9)
    private fun token()=LyricsLookupCancellationToken(target.storageKey(),1)
    private fun lyrics()=LyricsProviderResult("netease","NetEase","[00:01.00]Chosen lyrics",matchedTitle="Alias")
    private fun saveLocal(source:String=LyricsStorage.SOURCE_MANUAL_IMPORT) {
        assertTrue(LyricsStorage.savePlainLyrics(context,target.title,target.artist,target.durationMs,
            "[00:01.00]Existing lyrics",target.album,source,"local"))
    }
    @Test fun previewFetchUsesExactIdAndNeverWritesLyrics() {
        val requests=mutableListOf<JSONObject>()
        val service=ManualLyricsSearch(context) { request,_ ->
            requests+=JSONObject(request)
            """{"ok":true,"id":"123","lrc":"[00:01.00]Chosen lyrics"}"""
        }
        saveLocal()
        val preview=service.fetch(candidate,target,token())
        assertEquals("Chosen lyrics",(preview as ManualLyricsFetchResult.Available).lyrics.plainLrc.substringAfter(']'))
        assertEquals(1,requests.size)
        assertEquals("fetch",requests.single().getString("operation"))
        assertEquals("123",requests.single().getJSONObject("track").getString("id"))
        assertTrue(LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs)!!.contains("Existing lyrics"))
    }
    @Test fun wrongIdIsRejectedWithoutReplacingSavedLyrics() {
        saveLocal()
        val service=ManualLyricsSearch(context) { _,_-> """{"ok":true,"id":"other","lrc":"[00:01.00]Wrong"}""" }
        assertTrue(runCatching { service.fetch(candidate,target,token()) }.isFailure)
        assertTrue(LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs)!!.contains("Existing lyrics"))
    }
    @Test fun protectedFileRequiresApprovalAndSelectionSurvivesNewReader() {
        saveLocal()
        val service=ManualLyricsSearch(context)
        assertFalse(service.apply(target,lyrics(),token(),null))
        val revision=service.protectedRevision(target)
        assertNotNull(revision)
        assertTrue(service.apply(target,lyrics(),token(),revision))
        val selected=ManualLyricsSelectionStore.read(context,target)
        assertNotNull(selected)
        assertTrue(selected!!.result.selectedManually)
        assertEquals(target.title,selected.result.matchedTitle)
        assertTrue(selected.result.plainLrc.contains("Chosen lyrics"))
        assertNull(ManualLyricsSelectionStore.read(context,candidate.song))
    }
    @Test fun selectingPlainLyricsPreservesWordFileAndAvoidsMixing() {
        val imported=LyricsStorage.importWordByWordLyricsFromUriWithResult(context,
            writeImportFile("words.lrc","[00:01.00]<00:01.00>Old <00:02.00>words"),target.title,target.artist,target.durationMs)
        assertTrue(imported is LyricsStorage.ImportLyricsResult.Saved)
        val service=ManualLyricsSearch(context)
        assertTrue(service.apply(target,lyrics(),token(),service.protectedRevision(target)))
        assertTrue(WordByWordLyricsStorageOps.hasWordByWordLyrics(context,target.title,target.artist,target.durationMs))
        assertFalse(LyricsStorage.hasWordByWordLyrics(context,target.title,target.artist,target.durationMs))
        val selected=ManualLyricsSelectionStore.read(context,target)!!.result
        assertTrue(selected.selectedManually)
        assertTrue(selected.wordByWordLines.isEmpty())
    }
    @Test fun cancelledApplyLeavesExistingFileAndSelectionUntouched() {
        saveLocal(LyricsStorage.SOURCE_DOWNLOADED)
        val service=ManualLyricsSearch(context)
        assertTrue(service.apply(target,lyrics(),token(),null))
        val previous=LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs)
        val cancelled=token().apply { cancel() }
        assertTrue(runCatching { service.apply(target,lyrics().copy(plainLrc="[00:01.00]New"),cancelled,null) }.isFailure)
        assertEquals(previous,LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs))
        assertNotNull(ManualLyricsSelectionStore.read(context,target))
    }
    @Test fun failedSelectionCommitRollsBackPreviouslyWrittenLyrics() {
        saveLocal(LyricsStorage.SOURCE_DOWNLOADED)
        val service = ManualLyricsSearch(context)
        assertTrue(service.apply(target, lyrics(), token(), null))
        val previous = LyricsStorage.readPlainLyrics(context, target.title, target.artist, target.durationMs)
        val blocker = java.io.File(context.noBackupFilesDir,
            "lyrics-selections/" + target.copy(durationMs = 0).storageKey() + ".json.new")
        assertTrue(blocker.mkdirs())
        java.io.File(blocker, "block").writeText("force AtomicFile.startWrite failure")
        try {
            val result = runCatching {
                service.apply(target, lyrics().copy(plainLrc = "[00:01.00]Replacement"), token(), null)
            }
            assertTrue(result.isFailure)
            assertEquals(previous, LyricsStorage.readPlainLyrics(context, target.title, target.artist, target.durationMs))
            assertTrue(ManualLyricsSelectionStore.read(context, target)!!.result.plainLrc.contains("Chosen lyrics"))
        } finally {
            blocker.deleteRecursively()
        }
    }


    @Test fun emptyAndUnavailableLyricsArePreviewableWithoutWrites() {
        saveLocal()
        for (response in listOf(
            """{"ok":true,"content_state":"empty"}""",
            """{"ok":false,"error_type":"NotFound"}"""
        )) {
            val service=ManualLyricsSearch(context) { _,_ -> response }
            assertTrue(service.fetch(candidate,target,token()) is ManualLyricsFetchResult.Unavailable)
        }
        assertTrue(LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs)!!.contains("Existing lyrics"))
    }
    @Test fun musixmatchMissingSubtitleAppliesEmptyLyricsToOriginalSong() {
        saveLocal()
        val selected=candidate.copy(source=PlainLyricsSearchSource.MUSIXMATCH,
            song=target.copy(title="Last Dance"))
        val service=ManualLyricsSearch(context) { request,_ ->
            val json=JSONObject(request)
            assertEquals("musixmatch",json.getString("provider"))
            assertEquals(selected.id,json.getJSONObject("track").getString("id"))
            """{"ok":true,"content_state":"empty","reason":"source_empty"}"""
        }
        val preview=service.fetch(selected,target,token()) as ManualLyricsFetchResult.Available
        assertEquals("",preview.lyrics.plainLrc)
        assertEquals("Last Dance",preview.lyrics.matchedTitle)
        assertTrue(service.apply(target,preview.lyrics,token(),service.protectedRevision(target)))
        assertEquals("",ManualLyricsSelectionStore.read(context,target)!!.result.plainLrc)
        assertNull(ManualLyricsSelectionStore.read(context,selected.song))
    }

    @Test fun requestErrorsDoNotBecomeEmptyLyrics() {
        for (type in listOf("NetworkError","NeedCredential","RateLimited","ParseError","RestrictedLyrics","HttpError")) {
            val service=ManualLyricsSearch(context) { _,_ ->
                """{"ok":false,"error_type":"$type"}"""
            }
            assertTrue(runCatching { service.fetch(candidate,target,token()) }.exceptionOrNull() is LyricsLookupException)
        }
    }
    @Test fun emptyChoiceReplacesExistingLyricsAndSurvivesReload() {
        saveLocal()
        val service=ManualLyricsSearch(context)
        val empty=lyrics().copy(plainLrc="")
        assertFalse(service.apply(target,empty,token(),null))
        assertTrue(service.apply(target,empty,token(),service.protectedRevision(target)))
        assertEquals("",LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs))
        repeat(2) {
            val selected=ManualLyricsSelectionStore.read(context,target)!!.result
            assertEquals("",selected.plainLrc)
            assertTrue(selected.selectedManually)
            assertTrue(selected.manualLocal)
            assertTrue(selected.wordByWordLines.isEmpty())
        }
    }
    @Test fun successfulEmptyResponsesAreAvailableWithoutSavingDuringPreview() {
        saveLocal()
        for (response in listOf(
            """{"ok":true,"content_state":"empty","reason":"source_empty"}""",
            """{"ok":true,"id":"123","lrc":""}""",
            """{"ok":true,"id":"123","lrc":"   "}"""
        )) {
            val service=ManualLyricsSearch(context) { _,_ -> response }
            val fetched=service.fetch(candidate,target,token()) as ManualLyricsFetchResult.Available
            assertTrue(fetched.lyrics.plainLrc.isBlank())
            assertTrue(LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs)!!.contains("Existing lyrics"))
        }
    }
    @Test fun nonEmptyUnusablePayloadCannotReplaceExistingLyrics() {
        saveLocal()
        for (content in listOf("[ti:Title]", "[00:01.00]", "untimed text")) {
            val service=ManualLyricsSearch(context) { _,_ ->
                JSONObject().put("ok",true).put("id","123").put("lrc",content).toString()
            }
            assertTrue(service.fetch(candidate,target,token()) is ManualLyricsFetchResult.Unavailable)
            assertTrue(runCatching {
                service.apply(target,lyrics().copy(plainLrc=content),token(),service.protectedRevision(target))
            }.isFailure)
            assertTrue(LyricsStorage.readPlainLyrics(context,target.title,target.artist,target.durationMs)!!.contains("Existing lyrics"))
            assertNull(ManualLyricsSelectionStore.read(context,target))
        }
    }
    @Test fun realLyricsAndTranslationOnlyContentRemainApplicable() {
        val service=ManualLyricsSearch(context)
        for (result in listOf(
            lyrics().copy(plainLrc="[00:01.00]暂无歌词\n[00:02.00]Actual lyric"),
            lyrics().copy(plainLrc="[00:01.00]纯音乐，请欣赏"),
            lyrics().copy(plainLrc="",translatedLrc="[00:01.00]Translated lyric")
        )) {
            assertTrue(service.apply(target,result,token(),null))
            assertNotNull(ManualLyricsSelectionStore.read(context,target))
        }
    }
    @Test fun emptyReasonCodesArePreservedAndUnknownCodesAreSafe() {
        for (reason in LyricsUnavailableReason.entries) {
            val service=ManualLyricsSearch(context) { _,_ ->
                """{"ok":true,"content_state":"empty","reason":"${reason.wireName}"}"""
            }
            val result=service.fetch(candidate,target,token())
            if (reason in setOf(LyricsUnavailableReason.SOURCE_EMPTY,LyricsUnavailableReason.INSTRUMENTAL)) {
                assertEquals("",(result as ManualLyricsFetchResult.Available).lyrics.plainLrc)
            } else assertEquals(ManualLyricsFetchResult.Unavailable(reason),result)
        }
        for (field in listOf("", ",\"reason\":\"future_reason\"")) {
            val service=ManualLyricsSearch(context) { _,_ -> """{"ok":true,"content_state":"empty"$field}""" }
            assertEquals(ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.UNKNOWN),service.fetch(candidate,target,token()))
        }
    }
    @Test fun malformedSpicyContentIsAnErrorButUnsupportedAndEmptyContentHaveReasons() {
        val spicy=candidate.copy(source=PlainLyricsSearchSource.SPICY)
        fun fetch(body:String)=ManualLyricsSearch(context) { _,_ ->
            """{"ok":true,"track":{"id":"123"},"response":{"Status":200,"Body":$body}}"""
        }.fetch(spicy,target,token())
        assertEquals(ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.UNSUPPORTED_FORMAT),
            fetch("""{"id":"123","Type":"Static"}"""))
        for (body in listOf(
            """{"id":"123","Type":"Line","Content":[]}""",
            """{"id":"123","Type":"Line","Content":[{"Type":"Instrumental"}]}"""
        )) assertEquals("",(fetch(body) as ManualLyricsFetchResult.Available).lyrics.plainLrc)
        for (content in listOf("{}", "[42]", "[{}]", "[{\"Type\":\"Vocal\"}]")) {
            val error=runCatching { fetch("""{"id":"123","Type":"Line","Content":$content}""") }.exceptionOrNull()
            assertEquals(LyricsLookupErrorType.ParseError,(error as LyricsLookupException).errorType)
        }
    }

}
