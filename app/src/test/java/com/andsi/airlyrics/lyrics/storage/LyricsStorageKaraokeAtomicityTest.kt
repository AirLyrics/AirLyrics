package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.KaraokeLine
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageKaraokeAtomicityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
    }

    @After
    fun tearDown() {
        resetStorage()
    }

    @Test
    fun importKaraokeLyrics_plainFallbackWriteFails_rollsBackEntireImport() {
        val identity = SongIdentity(
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS
        )
        val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
        val karaokeFileName = LyricsFileNaming.managedKaraokeFileName(identity)
        val managedDir = LyricsStoragePaths.fallbackManagedLyricsDir(context)
        val beforeIndex = LyricsIndexStore.read(context)
        val failureTriggered = AtomicBoolean(false)

        val managedLyricsIo = FailingManagedLyricsIo(
            shouldFailWrite = { fileName, _ ->
                fileName == plainFileName && failureTriggered.compareAndSet(false, true)
            }
        )
        val result = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-plain-fallback-failure.lrc",
                text = "[00:10.00]<00:10.00>atomic karaoke"
            ),
            title = TITLE,
            artist = ARTIST,
            duration = DURATION_MS,
            album = ALBUM,
            managedLyricsIo = managedLyricsIo
        )

        assertTrue("The controlled plain fallback failure must be reached", failureTriggered.get())
        assertTrue(result is LyricsStorage.ImportLyricsResult.SaveFailed)

        val stateAfterFailure = ImportState(
            karaokeFileExists = File(managedDir, karaokeFileName).exists(),
            plainFileExists = File(managedDir, plainFileName).exists(),
            indexEntries = LyricsIndexStore.read(context),
            hasKaraokeLyrics = LyricsStorage.hasKaraokeLyrics(
                context,
                TITLE,
                ARTIST,
                DURATION_MS
            ),
            localLyrics = LyricsStorage.readLocalLyrics(
                context,
                TITLE,
                ARTIST,
                DURATION_MS
            ),
            karaokeLines = LyricsStorage.readKaraokeLyrics(
                context,
                TITLE,
                ARTIST,
                DURATION_MS
            )
        )

        assertEquals(
            "SaveFailed must restore the complete pre-import state",
            ImportState(
                karaokeFileExists = false,
                plainFileExists = false,
                indexEntries = beforeIndex,
                hasKaraokeLyrics = false,
                localLyrics = null,
                karaokeLines = emptyList()
            ),
            stateAfterFailure
        )
    }

    @Test
    fun karaokeImport_failure_restoresPreexistingState() {
        val identity = SongIdentity(
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS
        )
        val oldKaraokeLines = listOf(
            KaraokeLine(
                startMs = 10_000L,
                endMs = 11_000L,
                text = "old karaoke",
                tokens = listOf(
                    com.andsi.airlyrics.lyrics.KaraokeToken(
                        text = "old karaoke",
                        startMs = 10_000L,
                        endMs = 11_000L
                    )
                )
            )
        )
        assertTrue(
            LyricsStorage.saveKaraokeLyrics(
                context = context,
                title = TITLE,
                artist = ARTIST,
                duration = DURATION_MS,
                karaokeLines = oldKaraokeLines,
                album = ALBUM,
                source = LyricsStorage.SOURCE_DOWNLOADED,
                provider = "old-karaoke",
                metadataLines = listOf("[ar:Old Artist]", "[ti:Old Title]")
            )
        )
        assertTrue(
            LyricsStorage.saveLyrics(
                context = context,
                title = TITLE,
                artist = ARTIST,
                duration = DURATION_MS,
                lyrics = "[ar:Old Artist]\n[00:10.00]old fallback",
                album = ALBUM,
                source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
                provider = "old-plain"
            )
        )

        val generatedEntry = requireNotNull(
            LyricsIndexStore.find(context, TITLE, ARTIST, DURATION_MS)
        )
        val expectedEntry = generatedEntry.copy(
            source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
            provider = "old-plain",
            karaokeProvider = "old-karaoke",
            createdAt = 101L,
            updatedAt = 202L
        )
        assertTrue(LyricsIndexStore.write(context, listOf(expectedEntry)))
        val indexFile = File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)
        val indexJson = JSONArray(indexFile.readText())
        indexJson.getJSONObject(0).put("futureMetadata", "must-survive-rollback")
        indexFile.writeText(indexJson.toString(2))
        val rawIndexBefore = indexFile.readText()
        val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
        val failureTriggered = AtomicBoolean(false)
        val before = observableState(identity)

        val managedLyricsIo = FailingManagedLyricsIo(
            shouldFailWrite = { fileName, _ ->
                fileName == plainFileName && failureTriggered.compareAndSet(false, true)
            }
        )
        val result = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-replace-fallback-failure.lrc",
                text = "[00:20.00]<00:20.00>new karaoke"
            ),
            title = TITLE,
            artist = ARTIST,
            duration = DURATION_MS,
            album = ALBUM,
            managedLyricsIo = managedLyricsIo
        )

        assertTrue(failureTriggered.get())
        assertTrue(result is LyricsStorage.ImportLyricsResult.SaveFailed)
        assertEquals(expectedEntry, before.indexEntries.single())
        assertEquals(before, observableState(identity))
        assertEquals(rawIndexBefore, indexFile.readText())
    }

    @Test
    fun karaokeImport_rollbackIoFailure_reportsDistinctRiskAndKeepsIndexAuthoritative() {
        val identity = SongIdentity(
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS
        )
        val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
        val karaokeRelativeFile = LyricsFileNaming.managedRelativePath(
            LyricsFileNaming.managedKaraokeFileName(identity)
        )
        val originalFailureTriggered = AtomicBoolean(false)
        val rollbackFailureTriggered = AtomicBoolean(false)
        val managedLyricsIo = FailingManagedLyricsIo(
            shouldFailWrite = { fileName, _ ->
                fileName == plainFileName &&
                    originalFailureTriggered.compareAndSet(false, true)
            },
            shouldFailDelete = { relativeFile ->
                relativeFile == karaokeRelativeFile &&
                    rollbackFailureTriggered.compareAndSet(false, true)
            }
        )

        val result = LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "karaoke-rollback-delete-failure.lrc",
                text = "[00:30.00]<00:30.00>orphaned karaoke"
            ),
            title = TITLE,
            artist = ARTIST,
            duration = DURATION_MS,
            album = ALBUM,
            managedLyricsIo = managedLyricsIo
        )

        assertTrue(originalFailureTriggered.get())
        assertTrue(rollbackFailureTriggered.get())
        assertEquals(
            LyricsStorage.ImportLyricsResult.RollbackFailed(
                originalFailureStep =
                    LyricsStorage.KaraokeImportFailureStep.PLAIN_FALLBACK_FILE_WRITE,
                originalFailureCause =
                    LyricsStorage.KaraokeImportFailureCause.IO_OPERATION_RETURNED_FALSE,
                failedRollbackSteps = listOf(
                    LyricsStorage.KaraokeRollbackFailureStep.RESTORE_KARAOKE_FILE
                )
            ),
            result
        )
        assertTrue(LyricsIndexStore.read(context).isEmpty())
        assertFalse(LyricsStorage.hasKaraokeLyrics(context, TITLE, ARTIST, DURATION_MS))
        assertTrue(managedLyricsIo.exists(context, karaokeRelativeFile))
        assertFalse(
            managedLyricsIo.exists(
                context,
                LyricsFileNaming.managedRelativePath(plainFileName)
            )
        )
        assertTrue(currentRawIndex().isEmpty())
        assertEquals(null, LyricsStorage.readLocalLyrics(context, TITLE, ARTIST, DURATION_MS))
        assertTrue(
            LyricsStorage.readKaraokeLyrics(context, TITLE, ARTIST, DURATION_MS)
                .isEmpty()
        )
    }

    @Test
    fun karaokeImport_plainRestoreWriteFails_reportsResidualNewPlainWithOldIndex() {
        val seeded = seedPreexistingState()
        val restoreFailureTriggered = AtomicBoolean(false)
        val managedLyricsIo = FailingManagedLyricsIo(
            shouldFailWrite = { fileName, lyrics ->
                fileName == seeded.plainFileName &&
                    lyrics == seeded.plainLyrics &&
                    restoreFailureTriggered.compareAndSet(false, true)
            }
        )
        val indexIo = ControlledLyricsIndexIo(
            writeAction = { _, _ -> false }
        )

        val result = importKaraoke(
            importName = "plain-restore-write-failure.lrc",
            importText = "[00:40.00]<00:40.00>new plain residue",
            managedLyricsIo = managedLyricsIo,
            indexIo = indexIo
        )

        assertTrue(restoreFailureTriggered.get())
        assertRollbackFailed(
            result = result,
            expectedFailedSteps = listOf(
                LyricsStorage.KaraokeRollbackFailureStep.RESTORE_PLAIN_FILE
            )
        )
        assertTrue(
            AndroidManagedLyricsIo.read(context, seeded.plainRelativeFile)
                .orEmpty()
                .contains("new plain residue")
        )
        assertEquals(
            seeded.karaokeLines,
            LyricsStorage.readKaraokeLyrics(context, TITLE, ARTIST, DURATION_MS)
        )
        assertEquals(seeded.rawIndex.toList(), currentRawIndex().toList())
        assertEquals(seeded.indexEntry, LyricsIndexStore.read(context).single())
    }

    @Test
    fun karaokeImport_rawIndexRestoreFails_keepsCompleteNewFilesForAuthoritativeNewIndex() {
        val seeded = seedPreexistingState()
        val indexWriteFailureTriggered = AtomicBoolean(false)
        val indexRestoreFailureTriggered = AtomicBoolean(false)
        val indexIo = ControlledLyricsIndexIo(
            writeAction = { operationContext, entries ->
                assertTrue(AndroidLyricsIndexIo.write(operationContext, entries))
                indexWriteFailureTriggered.set(true)
                false
            },
            restoreAction = { _, _ ->
                indexRestoreFailureTriggered.set(true)
                false
            }
        )

        val result = importKaraoke(
            importName = "raw-index-restore-failure.lrc",
            importText = "[00:50.00]<00:50.00>new index residue",
            managedLyricsIo = AndroidManagedLyricsIo,
            indexIo = indexIo
        )

        assertTrue(indexWriteFailureTriggered.get())
        assertTrue(indexRestoreFailureTriggered.get())
        assertRollbackFailed(
            result = result,
            expectedFailedSteps = listOf(
                LyricsStorage.KaraokeRollbackFailureStep.RESTORE_INDEX
            )
        )
        val residualEntry = LyricsIndexStore.read(context).single()
        assertEquals(LyricsStorage.SOURCE_KARAOKE_FALLBACK, residualEntry.source)
        assertEquals("local", residualEntry.provider)
        assertEquals("local", residualEntry.karaokeProvider)
        assertFalse(seeded.rawIndex.contentEquals(currentRawIndex()))
        assertTrue(
            LyricsStorage.readLocalLyrics(context, TITLE, ARTIST, DURATION_MS)
                .orEmpty()
                .contains("new index residue")
        )
        assertEquals(
            "new index residue",
            LyricsStorage.readKaraokeLyrics(context, TITLE, ARTIST, DURATION_MS)
                .single()
                .text
        )
        assertTrue(
            "The residual new index must point to the complete new karaoke content",
            LyricsStorage.hasKaraokeLyrics(context, TITLE, ARTIST, DURATION_MS)
        )
    }

    @Test
    fun karaokeImport_twoFileRestoresFail_reportsEveryStepOnceAndLeavesBothNewFiles() {
        val seeded = seedPreexistingState()
        val writeCount = AtomicInteger(0)
        val managedLyricsIo = FailingManagedLyricsIo(
            shouldFailWrite = { _, _ -> writeCount.incrementAndGet() > 2 }
        )
        val indexIo = ControlledLyricsIndexIo(
            writeAction = { _, _ -> false }
        )

        val result = importKaraoke(
            importName = "two-restore-failures.lrc",
            importText = "[01:00.00]<01:00.00>new dual residue",
            managedLyricsIo = managedLyricsIo,
            indexIo = indexIo
        )

        assertRollbackFailed(
            result = result,
            expectedFailedSteps = listOf(
                LyricsStorage.KaraokeRollbackFailureStep.RESTORE_PLAIN_FILE,
                LyricsStorage.KaraokeRollbackFailureStep.RESTORE_KARAOKE_FILE
            )
        )
        assertEquals(4, writeCount.get())
        assertTrue(
            LyricsStorage.readLocalLyrics(context, TITLE, ARTIST, DURATION_MS)
                .orEmpty()
                .contains("new dual residue")
        )
        assertEquals(
            "new dual residue",
            LyricsStorage.readKaraokeLyrics(context, TITLE, ARTIST, DURATION_MS)
                .single()
                .text
        )
        assertEquals(seeded.rawIndex.toList(), currentRawIndex().toList())
        assertEquals(seeded.indexEntry, LyricsIndexStore.read(context).single())
    }

    @Test
    fun karaokeImport_rootSwitchWaitsForSnapshotWritesCommitAndRollback() {
        val fallbackRoot = LyricsStoragePaths.fallbackLyricsDir(context).absolutePath
        val operations = CopyOnWriteArrayList<RootOperation>()
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val managedLyricsIo = RootRecordingManagedLyricsIo(
            operations = operations,
            firstWriteEntered = firstWriteEntered,
            releaseFirstWrite = releaseFirstWrite
        )
        val indexIo = RootRecordingIndexIo(
            operations = operations,
            failCommit = true
        )
        val actors = Executors.newFixedThreadPool(2)
        val switchStarted = CountDownLatch(1)
        val switchCompleted = CountDownLatch(1)

        val importFuture = actors.submit<LyricsStorage.ImportLyricsResult> {
            importKaraoke(
                importName = "root-stability.lrc",
                importText = "[01:10.00]<01:10.00>root stable",
                managedLyricsIo = managedLyricsIo,
                indexIo = indexIo
            )
        }

        try {
            assertTrue(firstWriteEntered.await(2, TimeUnit.SECONDS))
            val switchFuture = actors.submit {
                switchStarted.countDown()
                LyricsStorage.saveLyricsDirUri(
                    context,
                    Uri.parse("content://airlyrics.test/tree/switched-root")
                )
                switchCompleted.countDown()
            }
            assertTrue(switchStarted.await(2, TimeUnit.SECONDS))
            assertFalse(
                "Changing the storage root must wait until the active import transaction ends",
                switchCompleted.await(250, TimeUnit.MILLISECONDS)
            )

            releaseFirstWrite.countDown()
            assertTrue(
                importFuture.get(5, TimeUnit.SECONDS) is
                    LyricsStorage.ImportLyricsResult.SaveFailed
            )
            switchFuture.get(5, TimeUnit.SECONDS)
            assertTrue(switchCompleted.await(2, TimeUnit.SECONDS))

            assertTrue(operations.isNotEmpty())
            assertEquals(
                setOf(fallbackRoot),
                operations.map { operation -> operation.root }.toSet()
            )
            assertTrue(operations.any { it.name == "managed.exists" })
            assertEquals(2, operations.count { it.name == "managed.write" })
            assertEquals(2, operations.count { it.name == "managed.delete" })
            assertTrue(operations.any { it.name == "index.captureRaw" })
            assertTrue(operations.any { it.name == "index.read" })
            assertTrue(operations.any { it.name == "index.write" })
            assertTrue(operations.any { it.name == "index.restoreRaw" })
        } finally {
            releaseFirstWrite.countDown()
            runCatching { importFuture.get(5, TimeUnit.SECONDS) }
            actors.shutdownNow()
            assertTrue(actors.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun importKaraoke(
        importName: String,
        importText: String,
        managedLyricsIo: ManagedLyricsIo,
        indexIo: LyricsIndexIo
    ): LyricsStorage.ImportLyricsResult {
        return LyricsStorage.importKaraokeLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(importName, importText),
            title = TITLE,
            artist = ARTIST,
            duration = DURATION_MS,
            album = ALBUM,
            managedLyricsIo = managedLyricsIo,
            indexIo = indexIo
        )
    }

    private fun seedPreexistingState(): SeededState {
        val identity = SongIdentity(
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS
        )
        val karaokeLines = listOf(
            KaraokeLine(
                startMs = 10_000L,
                endMs = 11_000L,
                text = "old karaoke",
                tokens = listOf(
                    com.andsi.airlyrics.lyrics.KaraokeToken(
                        text = "old karaoke",
                        startMs = 10_000L,
                        endMs = 11_000L
                    )
                )
            )
        )
        val plainLyrics = "[ar:Old Artist]\n[00:10.00]old fallback"
        assertTrue(
            LyricsStorage.saveKaraokeLyrics(
                context = context,
                title = TITLE,
                artist = ARTIST,
                duration = DURATION_MS,
                karaokeLines = karaokeLines,
                album = ALBUM,
                source = LyricsStorage.SOURCE_DOWNLOADED,
                provider = "old-karaoke",
                metadataLines = listOf("[ar:Old Artist]", "[ti:Old Title]")
            )
        )
        assertTrue(
            LyricsStorage.saveLyrics(
                context = context,
                title = TITLE,
                artist = ARTIST,
                duration = DURATION_MS,
                lyrics = plainLyrics,
                album = ALBUM,
                source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
                provider = "old-plain"
            )
        )
        val entry = requireNotNull(
            LyricsIndexStore.find(context, TITLE, ARTIST, DURATION_MS)
        ).copy(
            source = LyricsStorage.SOURCE_KARAOKE_FALLBACK,
            provider = "old-plain",
            karaokeProvider = "old-karaoke",
            createdAt = 101L,
            updatedAt = 202L
        )
        assertTrue(LyricsIndexStore.write(context, listOf(entry)))
        val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
        return SeededState(
            plainFileName = plainFileName,
            plainRelativeFile = LyricsFileNaming.managedRelativePath(plainFileName),
            plainLyrics = plainLyrics,
            karaokeLines = karaokeLines,
            indexEntry = entry,
            rawIndex = currentRawIndex()
        )
    }

    private fun assertRollbackFailed(
        result: LyricsStorage.ImportLyricsResult,
        expectedFailedSteps: List<LyricsStorage.KaraokeRollbackFailureStep>
    ) {
        val rollbackFailed = result as LyricsStorage.ImportLyricsResult.RollbackFailed
        assertEquals(
            LyricsStorage.KaraokeImportFailureStep.INDEX_WRITE,
            rollbackFailed.originalFailureStep
        )
        assertEquals(
            LyricsStorage.KaraokeImportFailureCause.IO_OPERATION_RETURNED_FALSE,
            rollbackFailed.originalFailureCause
        )
        assertEquals(expectedFailedSteps, rollbackFailed.failedRollbackSteps)
        assertEquals(
            rollbackFailed.failedRollbackSteps.size,
            rollbackFailed.failedRollbackSteps.distinct().size
        )
    }

    private fun currentRawIndex(): ByteArray {
        val indexFile = File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)
        return if (indexFile.exists()) indexFile.readBytes() else byteArrayOf()
    }

    private fun observableState(identity: SongIdentity): ImportState {
        val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
        val karaokeFileName = LyricsFileNaming.managedKaraokeFileName(identity)
        val managedDir = LyricsStoragePaths.fallbackManagedLyricsDir(context)
        return ImportState(
            karaokeFileExists = File(managedDir, karaokeFileName).exists(),
            plainFileExists = File(managedDir, plainFileName).exists(),
            indexEntries = LyricsIndexStore.read(context),
            hasKaraokeLyrics = LyricsStorage.hasKaraokeLyrics(
                context,
                TITLE,
                ARTIST,
                DURATION_MS
            ),
            localLyrics = LyricsStorage.readLocalLyrics(
                context,
                TITLE,
                ARTIST,
                DURATION_MS
            ),
            karaokeLines = LyricsStorage.readKaraokeLyrics(
                context,
                TITLE,
                ARTIST,
                DURATION_MS
            )
        )
    }

    private fun writeImportFile(name: String, text: String): Uri {
        return Uri.fromFile(
            File(context.cacheDir, name).apply {
                writeText(text)
            }
        )
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    private data class ImportState(
        val karaokeFileExists: Boolean,
        val plainFileExists: Boolean,
        val indexEntries: List<LyricsIndexEntry>,
        val hasKaraokeLyrics: Boolean,
        val localLyrics: String?,
        val karaokeLines: List<KaraokeLine>
    )

    private class SeededState(
        val plainFileName: String,
        val plainRelativeFile: String,
        val plainLyrics: String,
        val karaokeLines: List<KaraokeLine>,
        val indexEntry: LyricsIndexEntry,
        val rawIndex: ByteArray
    )

    private class FailingManagedLyricsIo(
        private val shouldFailWrite: (fileName: String, lyrics: String) -> Boolean,
        private val shouldFailDelete: (relativeFile: String) -> Boolean = { false }
    ) : ManagedLyricsIo by AndroidManagedLyricsIo {
        override fun write(context: Context, fileName: String, lyrics: String): Boolean {
            return if (shouldFailWrite(fileName, lyrics)) {
                false
            } else {
                AndroidManagedLyricsIo.write(context, fileName, lyrics)
            }
        }

        override fun delete(context: Context, relativeFile: String): Boolean {
            return if (shouldFailDelete(relativeFile)) {
                false
            } else {
                AndroidManagedLyricsIo.delete(context, relativeFile)
            }
        }
    }

    private class ControlledLyricsIndexIo(
        private val writeAction: (Context, List<LyricsIndexEntry>) -> Boolean =
            AndroidLyricsIndexIo::write,
        private val restoreAction:
            (Context, LyricsIndexStore.RawSnapshot) -> Boolean =
            AndroidLyricsIndexIo::restoreRaw
    ) : LyricsIndexIo by AndroidLyricsIndexIo {
        override fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean {
            return writeAction(context, entries)
        }

        override fun restoreRaw(
            context: Context,
            snapshot: LyricsIndexStore.RawSnapshot
        ): Boolean {
            return restoreAction(context, snapshot)
        }
    }

    private data class RootOperation(
        val name: String,
        val root: String
    )

    private class RootRecordingManagedLyricsIo(
        private val operations: MutableList<RootOperation>,
        private val firstWriteEntered: CountDownLatch,
        private val releaseFirstWrite: CountDownLatch
    ) : ManagedLyricsIo {
        private val gateFirstWrite = AtomicBoolean(true)

        override fun exists(context: Context, relativeFile: String): Boolean {
            record(context, "managed.exists")
            return AndroidManagedLyricsIo.exists(context, relativeFile)
        }

        override fun read(context: Context, relativeFile: String): String? {
            record(context, "managed.read")
            return AndroidManagedLyricsIo.read(context, relativeFile)
        }

        override fun write(context: Context, fileName: String, lyrics: String): Boolean {
            record(context, "managed.write")
            if (gateFirstWrite.compareAndSet(true, false)) {
                firstWriteEntered.countDown()
                check(releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the first managed write"
                }
            }
            return AndroidManagedLyricsIo.write(context, fileName, lyrics)
        }

        override fun delete(context: Context, relativeFile: String): Boolean {
            record(context, "managed.delete")
            return AndroidManagedLyricsIo.delete(context, relativeFile)
        }

        private fun record(context: Context, name: String) {
            operations += RootOperation(name, LyricsStoragePaths.getLyricsDirRawPath(context))
        }
    }

    private class RootRecordingIndexIo(
        private val operations: MutableList<RootOperation>,
        private val failCommit: Boolean
    ) : LyricsIndexIo {
        override fun read(context: Context): List<LyricsIndexEntry> {
            record(context, "index.read")
            return AndroidLyricsIndexIo.read(context)
        }

        override fun find(
            context: Context,
            title: String,
            artist: String,
            duration: Long
        ): LyricsIndexEntry? {
            record(context, "index.find")
            return AndroidLyricsIndexIo.find(context, title, artist, duration)
        }

        override fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean {
            record(context, "index.write")
            return if (failCommit) false else AndroidLyricsIndexIo.write(context, entries)
        }

        override fun captureRaw(context: Context): LyricsIndexStore.RawSnapshot {
            record(context, "index.captureRaw")
            return AndroidLyricsIndexIo.captureRaw(context)
        }

        override fun restoreRaw(
            context: Context,
            snapshot: LyricsIndexStore.RawSnapshot
        ): Boolean {
            record(context, "index.restoreRaw")
            return AndroidLyricsIndexIo.restoreRaw(context, snapshot)
        }

        private fun record(context: Context, name: String) {
            operations += RootOperation(name, LyricsStoragePaths.getLyricsDirRawPath(context))
        }
    }

    private companion object {
        const val TITLE = "Atomic Karaoke Import"
        const val ARTIST = "AndSi"
        const val ALBUM = "Atomic Import Album"
        const val DURATION_MS = 180_000L
    }
}
