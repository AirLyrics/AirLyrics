package com.andsi.airlyrics.app.interaction

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocument
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocumentReader
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.LyricsReplacementOps
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.state.MainInteractionState
import com.andsi.airlyrics.testutil.resetLyricsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BatchLyricsSessionTest {
    private lateinit var context: Context
    private val scopes = mutableListOf<Fixture>()
    private val documents = mutableMapOf<Uri, BatchLyricsDocumentReader.Result>()
    private val changes = mutableListOf<LyricsChange>()
    private val access = Access()
    private var reads = 0
    private var writes = 0
    private var failTitle: String? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        resetLyricsStorage(context)
        listOf("One", "Two", "Untouched").forEach {
            assertTrue(LyricsStorage.savePlainLyrics(context, it, "Artist", 180_000, "[00:01.00]old $it"))
        }
        document("One"); document("Two")
    }
    @After fun cleanup() { scopes.forEach { it.owner.cancel(); it.scope.testScheduler.runCurrent() }; resetLyricsStorage(context) }

    @Test fun cancelPickerDoesNotReadWriteOrRetainGrants() {
        val f = fixture()
        assertTrue(f.session.begin(items(), false))
        f.session.filesSelected(emptyList())
        f.scope.testScheduler.advanceUntilIdle()
        assertNull(f.session.state.value)
        assertEquals(0, reads); assertEquals(0, writes); assertTrue(access.taken.isEmpty())
    }

    @Test fun partialSuccessMatchesStorageAndOnlyPublishesSuccessfulSong() {
        failTitle = "Two"
        val f = preview()
        f.session.confirm(); f.session.confirm()
        f.scope.testScheduler.advanceUntilIdle()
        val result = f.session.state.value!!
        assertEquals(1, result.completed)
        assertEquals(listOf(BatchLyricsProblem.SAVE), result.failures.map { it.problem })
        assertEquals(2, writes)
        assertEquals(listOf("One"), changes.map { it.target?.title })
        assertTrue(changes.all { it.kind == com.andsi.airlyrics.lyrics.LyricsChangeKind.UPDATED })
        assertEquals("[00:02.00]new One", content("One"))
        assertEquals("[00:01.00]old Two", content("Two"))
        assertEquals("[00:01.00]old Untouched", content("Untouched"))
        assertEquals(access.taken.toSet(), access.released.toSet())
    }

    @Test fun unmatchedDocumentNeverWrites() {
        val f = fixture()
        document("Unknown")
        f.session.begin(items(), false)
        f.session.filesSelected(listOf(uri("Unknown")))
        f.scope.testScheduler.advanceUntilIdle()
        assertEquals(BatchLyricsProblem.NO_MATCH, f.session.state.value!!.rows.single().problem)
        f.session.confirm(); f.scope.testScheduler.advanceUntilIdle()
        assertEquals(0, writes); assertTrue(changes.isEmpty())
    }

    @Test fun changedDocumentAfterPreviewIsRejected() {
        val f = preview()
        document("One", "changed")
        f.session.confirm(); f.scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(BatchLyricsProblem.CHANGED), f.session.state.value!!.failures.map { it.problem })
        assertEquals("[00:01.00]old One", content("One"))
        assertEquals(1, writes)
    }

    @Test fun changedDirectoryRejectsAllWrites() {
        val f = preview()
        LyricsStorage.saveLyricsDirUri(context, Uri.parse("content://changed/root"))
        f.session.confirm(); f.scope.testScheduler.advanceUntilIdle()
        assertEquals(0, writes); assertTrue(changes.isEmpty())
        assertEquals(2, f.session.state.value!!.failures.size)
    }

    @Test fun newOwnerRereadsPreviewAndReadingButNeverReplaysRunning() {
        for (stage in listOf(BatchLyricsStage.PREVIEW, BatchLyricsStage.READING, BatchLyricsStage.RUNNING)) {
            val original = preview()
            original.state.write("batchLyrics") { putString("stage", stage.name) }
            val restored = fixture(copyHandle(original.handle))
            val before = reads
            restored.session.restore(); restored.session.restore()
            restored.scope.testScheduler.advanceUntilIdle()
            if (stage == BatchLyricsStage.RUNNING) {
                assertEquals(before, reads)
                assertEquals(BatchLyricsStage.FINISHED, restored.session.state.value!!.stage)
                assertTrue(restored.session.state.value!!.interrupted)
            } else {
                assertEquals(before + 2, reads)
                assertEquals(BatchLyricsStage.PREVIEW, restored.session.state.value!!.stage)
            }
            assertEquals(0, writes)
            original.owner.cancel(); restored.owner.cancel()
        }
    }

    @Test fun busySessionRejectsDismissAndRepeatedConfirm() {
        val f = fixture()
        f.session.begin(items(), false)
        f.session.filesSelected(listOf(uri("One"), uri("Two")))
        assertEquals(BatchLyricsStage.READING, f.session.state.value!!.stage)
        f.session.dismiss()
        assertEquals(BatchLyricsStage.READING, f.session.state.value!!.stage)
        f.scope.testScheduler.advanceUntilIdle()
        f.session.confirm(); f.session.dismiss(); f.session.confirm()
        assertEquals(BatchLyricsStage.RUNNING, f.session.state.value!!.stage)
        f.scope.testScheduler.advanceUntilIdle()
        assertEquals(2, writes)
    }

    @Test fun existingGrantsAreNotReleasedAndOwnedGrantsCloseWithScope() {
        access.existing += uri("One")
        val f = preview()
        f.owner.cancel()
        f.scope.testScheduler.runCurrent()
        assertEquals(listOf(uri("Two")), access.taken)
        assertEquals(listOf(uri("Two")), access.released)
    }

    @Test fun failedPersistentGrantIsObservableAndTemporaryReadCanContinue() {
        access.denied += uri("One")
        val f = preview()
        assertFalse(f.session.state.value!!.rows.first { it.uri == uri("One") }.accessRetained)
        assertNull(f.session.state.value!!.rows.first { it.uri == uri("One") }.problem)
        f.session.dismiss()
        assertEquals(listOf(uri("Two")), access.released)
    }

    @Test fun localAndCacheIdentityResolveToOneLocalTarget() {
        val f = fixture()
        val local = items().first { it.title == "One" }
        val cache = local.copy(indexKey = "spicy-cache:one", source = LyricsStorage.SOURCE_PROVIDER_CACHE)
        f.session.begin(listOf(cache, local), false)
        f.session.filesSelected(listOf(uri("One")))
        f.scope.testScheduler.advanceUntilIdle()
        assertEquals(local, f.session.state.value!!.rows.single().target)
        f.session.confirm(); f.scope.testScheduler.advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals(1, f.session.state.value!!.completed)
    }

    @Test fun failedTemporaryReadSurfacesReadProblemWithoutWriting() {
        access.denied += uri("One")
        documents[uri("One")] = BatchLyricsDocumentReader.Result("One.lrc", failure = BatchLyricsDocumentReader.Failure.READ)
        val f = preview()
        val row = f.session.state.value!!.rows.single { it.uri == uri("One") }
        assertEquals(BatchLyricsProblem.READ, row.problem)
        assertFalse(row.accessRetained)
        assertNull(row.target)
        f.session.confirm()
        f.scope.testScheduler.advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals(listOf("Two"), changes.map { it.target?.title })
        assertEquals("[00:01.00]old One", content("One"))
    }

    private fun preview(): Fixture = fixture().also {
        it.session.begin(items(), false)
        it.session.filesSelected(listOf(uri("One"), uri("Two")))
        it.scope.testScheduler.advanceUntilIdle()
        assertEquals(BatchLyricsStage.PREVIEW, it.session.state.value!!.stage)
    }
    private fun fixture(handle: SavedStateHandle = SavedStateHandle()): Fixture {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val state = MainInteractionState(handle)
        val session = BatchLyricsSession(context, state, owner, dispatcher,
            readDocument = { _, uri -> reads++; documents.getValue(uri) },
            replaceDocument = { ctx, item, doc ->
                writes++
                if (item.title == failTitle) LyricsReplacementOps.Result.FAILED
                else LyricsStorage.replaceSavedLyrics(ctx, item, doc)
            }, publisher = LyricsChangedPublisher(changes::add), access = access)
        return Fixture(handle, state, scope, session, owner).also(scopes::add)
    }
    private fun items() = LyricsStorage.listAllLyrics(context).filter { it.title != "Untouched" }.map {
        LocalLyricsUiItem(it.name, it.modifiedTimeMillis, it.sizeBytes, it.title, it.artist, it.album,
            it.durationMs, it.indexKey, it.source, it.provider, it.hasPlainLyrics, it.hasWordByWordLyrics,
            it.canDelete, it.displayTitle)
    }
    private fun uri(title: String) = Uri.parse("content://session/$title.lrc")
    private fun document(title: String, fingerprint: String = title) {
        documents[uri(title)] = BatchLyricsDocumentReader.Result("$title.lrc",
            BatchLyricsDocument("$title.lrc", "[00:02.00]new $title", title = title, artist = "Artist"), fingerprint = fingerprint)
    }
    private fun content(title: String) = LyricsStorage.readPlainLyrics(context, title, "Artist", 180_000)
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { Bundle(handle.get<Bundle>(it)!!) })
    private data class Fixture(val handle: SavedStateHandle, val state: MainInteractionState, val scope: TestScope, val session: BatchLyricsSession, val owner: CoroutineScope)
    private class Access : BatchLyricsAccess {
        val existing = mutableSetOf<Uri>()
        val denied = mutableSetOf<Uri>()
        val taken = mutableListOf<Uri>()
        val released = mutableListOf<Uri>()
        override fun held(uri: Uri, flags: Int) = uri in existing
        override fun take(uri: Uri, flags: Int): Boolean { if (uri in denied) return false; taken += uri; return true }
        override fun release(uri: Uri, flags: Int) { released += uri }
    }
}
