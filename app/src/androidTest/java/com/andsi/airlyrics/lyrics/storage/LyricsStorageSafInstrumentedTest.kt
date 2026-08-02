package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.core.model.SongIdentity
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException

@RunWith(AndroidJUnit4::class)
class LyricsStorageSafInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sessions = mutableListOf<TestTreeSession>()
    private val fallbackArtifacts = linkedSetOf<File>()
    private val testSongKeys = linkedSetOf<String>()
    private val testManagedFileNames = linkedSetOf<String>()
    private lateinit var storagePreferences: SharedPreferences
    private lateinit var fallbackLyricsDir: File
    private lateinit var fallbackIndexBefore: FileSnapshot
    private lateinit var runId: String
    private var originalTreeUriWasSet = false
    private var originalTreeUri: String? = null

    @Before
    fun setUp() {
        sessions.clear()
        fallbackArtifacts.clear()
        testSongKeys.clear()
        testManagedFileNames.clear()
        runId = UUID.randomUUID().toString()
        storagePreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        originalTreeUriWasSet = storagePreferences.contains(KEY_TREE_URI)
        originalTreeUri = storagePreferences.getString(KEY_TREE_URI, null)
        fallbackLyricsDir = LyricsStoragePaths.fallbackLyricsDir(context)
        fallbackIndexBefore = snapshot(File(fallbackLyricsDir, INDEX_FILE_NAME))
    }

    @After
    fun tearDown() {
        val cleanupErrors = mutableListOf<Throwable>()
        sessions.asReversed().forEach { session ->
            cleanupSession(session, cleanupErrors)
        }
        cleanupFallbackArtifacts(cleanupErrors)
        collectCleanupError(cleanupErrors, "restore original lyrics tree setting") {
            val editor = storagePreferences.edit()
            if (originalTreeUriWasSet) {
                editor.putString(KEY_TREE_URI, originalTreeUri)
            } else {
                editor.remove(KEY_TREE_URI)
            }
            check(editor.commit()) { "Unable to restore lyrics tree setting" }
        }
        MultipleFailureException.assertEmpty(cleanupErrors)
    }

    @Test
    fun fixtureControl_rejectsTargetUid_andCleanupIsIdempotent() {
        val session = createTreeSession()
        val rejected =
            runCatching {
                context.contentResolver.call(
                    CONTROL_URI,
                    TestDocumentsControlProvider.METHOD_DELETE_SESSION,
                    session.sessionName,
                    null,
                )
            }.exceptionOrNull()
        assertTrue(
            "Expected target UID to receive SecurityException, got $rejected",
            rejected is SecurityException,
        )

        val cleanupErrors = mutableListOf<Throwable>()
        cleanupSession(session, cleanupErrors)
        cleanupSession(session, cleanupErrors)
        MultipleFailureException.assertEmpty(cleanupErrors)
    }

    @Test
    fun customDocumentTree_saveReadIndexDelete_roundTrips() {
        val session = createTreeSession()
        context.contentResolver.takePersistableUriPermission(
            session.treeUri,
            URI_READ_WRITE_FLAGS,
        )
        session.persistablePermissionTaken = true
        assertTrue(
            context.contentResolver.persistedUriPermissions.any {
                it.uri == session.treeUri && it.isReadPermission && it.isWritePermission
            },
        )
        assertTrue(LyricsStorage.validateLyricsDir(context, session.treeUri))
        LyricsStorage.saveLyricsDirUri(context, session.treeUri)
        assertEquals(session.treeUri.toString(), LyricsStorage.getLyricsDirRawPath(context))

        val identity =
            SongIdentity(
                title = "SAF Round Trip $runId",
                artist = "Instrumentation Artist $runId",
                album = "Document Tree Album",
                durationMs = 215_000L,
            )
        val lyrics = "[00:01.00]first line\n[00:02.00]second line"
        val provider = "saf-instrumentation"
        val managedFileName = LyricsFileNaming.managedPlainFileName(identity)
        val fallbackManagedFile = registerFallbackIdentity(identity)

        assertTrue(
            LyricsStorage.saveLyrics(
                context = context,
                title = identity.title,
                artist = identity.artist,
                duration = identity.durationMs,
                lyrics = lyrics,
                album = identity.album,
                source = LyricsStorage.SOURCE_DOWNLOADED,
                provider = provider,
            ),
        )

        val treeRoot = requireNotNull(DocumentFile.fromTreeUri(context, session.treeUri))
        val managedDir = requireNotNull(treeRoot.findFile(MANAGED_LYRICS_DIR))
        val managedFile = requireNotNull(managedDir.findFile(managedFileName))
        assertEquals(TestDocumentsProvider.AUTHORITY, managedFile.uri.authority)
        assertEquals(lyrics, readText(managedFile.uri))

        val indexFile = requireNotNull(treeRoot.findFile(INDEX_FILE_NAME))
        assertEquals(TestDocumentsProvider.AUTHORITY, indexFile.uri.authority)
        val rawIndex = readText(indexFile.uri)
        val indexJson = JSONArray(rawIndex)
        assertEquals(1, indexJson.length())
        val storedJson = indexJson.getJSONObject(0)
        assertEquals(identity.storageKey(), storedJson.getString("key"))
        assertEquals(
            LyricsFileNaming.managedRelativePath(managedFileName),
            storedJson.getString("file"),
        )
        assertEquals(identity.title, storedJson.getString("title"))
        assertEquals(identity.artist, storedJson.getString("artist"))
        assertEquals(identity.album, storedJson.getString("album"))
        assertEquals(identity.durationMs, storedJson.getLong("durationMs"))
        assertEquals(LyricsStorage.SOURCE_DOWNLOADED, storedJson.getString("source"))
        assertEquals(provider, storedJson.getString("provider"))

        val indexEntry =
            LyricsIndexStore.find(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            )
        assertNotNull(indexEntry)
        assertEquals(identity.storageKey(), indexEntry?.key)
        assertEquals(LyricsFileNaming.managedRelativePath(managedFileName), indexEntry?.file)
        assertEquals(LyricsStorage.SOURCE_DOWNLOADED, indexEntry?.source)
        assertEquals(provider, indexEntry?.provider)
        assertEquals(
            lyrics,
            LyricsStorage.readLocalLyrics(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )
        val freshPackageContext = context.createPackageContext(context.packageName, 0)
        assertEquals(
            session.treeUri.toString(),
            LyricsStorage.getLyricsDirRawPath(freshPackageContext),
        )
        assertEquals(
            lyrics,
            LyricsStorage.readLocalLyrics(
                freshPackageContext,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )

        val listed = LyricsStorage.listRecentLyrics(context)
        val listedEntry =
            listed.single {
                it.title == identity.title && it.artist == identity.artist
            }
        assertEquals(identity.title, listedEntry.title)
        assertEquals(identity.artist, listedEntry.artist)
        assertEquals(LyricsStorage.SOURCE_DOWNLOADED, listedEntry.source)
        assertEquals(provider, listedEntry.provider)

        assertFalse(fallbackManagedFile.exists())
        assertFallbackIndexUnchanged()

        assertTrue(
            LyricsStorage.deleteLocalLyrics(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )
        assertNull(managedDir.findFile(managedFileName))
        assertNull(
            LyricsIndexStore.find(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )
        assertNull(
            LyricsStorage.readLocalLyrics(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )
        assertFalse(
            LyricsStorage.listRecentLyrics(context).any {
                it.title == identity.title && it.artist == identity.artist
            },
        )
        assertEquals(0, JSONArray(readText(indexFile.uri)).length())
        assertFalse(fallbackManagedFile.exists())
        assertFallbackIndexUnchanged()

        assertStreamFailureIsNotReportedAsSuccess()
    }

    private fun assertStreamFailureIsNotReportedAsSuccess() {
        val deniedSession = createTreeSession(TestDocumentsProvider.DENY_WRITE_PREFIX)
        LyricsStorage.saveLyricsDirUri(context, deniedSession.treeUri)
        val identity =
            SongIdentity(
                title = "Denied SAF Write $runId",
                artist = "Instrumentation Artist $runId",
                album = "",
                durationMs = 99_000L,
            )
        val managedFileName = LyricsFileNaming.managedPlainFileName(identity)
        val fallbackManagedFile = registerFallbackIdentity(identity)

        assertFalse(
            LyricsStorage.saveLyrics(
                context = context,
                title = identity.title,
                artist = identity.artist,
                duration = identity.durationMs,
                lyrics = "[00:01.00]must not be reported as saved",
                source = LyricsStorage.SOURCE_DOWNLOADED,
                provider = "failing-test-provider",
            ),
        )
        assertNull(
            LyricsIndexStore.find(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )
        assertNull(
            LyricsStorage.readLocalLyrics(
                context,
                identity.title,
                identity.artist,
                identity.durationMs,
            ),
        )
        assertFalse(
            LyricsStorage.listRecentLyrics(context).any {
                it.title == identity.title && it.artist == identity.artist
            },
        )
        val deniedRoot = requireNotNull(DocumentFile.fromTreeUri(context, deniedSession.treeUri))
        val deniedManagedDir = requireNotNull(deniedRoot.findFile(MANAGED_LYRICS_DIR))
        val orphanFile = deniedManagedDir.findFile(managedFileName)
        val orphanListing =
            LyricsStorage.listRecentLyrics(context).singleOrNull {
                it.name == managedFileName
            }
        assertFalse(
            "Failed SAF write left physical=${
                orphanFile != null
            }, publiclyListed=${orphanListing != null} for $managedFileName",
            orphanFile != null || orphanListing != null,
        )

        assertFalse(fallbackManagedFile.exists())
        assertFallbackIndexUnchanged()
    }

    private fun createTreeSession(prefix: String = "session-"): TestTreeSession {
        val sessionName = "$prefix${UUID.randomUUID()}"
        require(SESSION_NAME_PATTERN.matches(sessionName))
        val documentId = "${TestDocumentsProvider.ROOT_DOCUMENT_ID}/$sessionName"
        val sessionDocumentUri =
            DocumentsContract.buildDocumentUri(
                TestDocumentsProvider.AUTHORITY,
                documentId,
            )
        val treeUri =
            DocumentsContract.buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY,
                documentId,
            )
        val session =
            TestTreeSession(
                sessionName = sessionName,
                documentUri = sessionDocumentUri,
                treeUri = treeUri,
            )
        sessions += session
        val controlOutput =
            callControlProvider(
                TestDocumentsControlProvider.METHOD_CREATE_SESSION,
                sessionName,
            )
        session.controlCreateSucceeded = true
        val sessionDocumentId = DocumentsContract.getDocumentId(sessionDocumentUri)
        assertEquals(
            documentId,
            sessionDocumentId,
        )
        val targetProviderClient =
            context.contentResolver.acquireUnstableContentProviderClient(session.treeUri)
        assertNotNull(controlOutput, targetProviderClient)
        targetProviderClient?.close()
        val root = DocumentFile.fromTreeUri(context, session.treeUri)
        assertNotNull(controlOutput, root)
        assertTrue(controlOutput, root?.exists() == true)
        assertTrue(controlOutput, root?.canRead() == true)
        assertTrue(controlOutput, root?.canWrite() == true)
        return session
    }

    private fun callControlProvider(method: String, argument: String): String {
        require(method in CONTROL_METHODS)
        require(SESSION_NAME_PATTERN.matches(argument))
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output =
            uiAutomation.executeShellCommand(
                "content call --uri $CONTROL_URI --method $method --arg $argument",
            )
        val rawOutput = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use {
            it.readText()
        }
        if (method != TestDocumentsControlProvider.METHOD_CREATE_SESSION) {
            val expectedResult =
                "${TestDocumentsControlProvider.RESULT_OPERATION_SUCCEEDED}=$argument"
            check(rawOutput.contains(expectedResult)) {
                "SAF control command did not confirm $method($argument): $rawOutput"
            }
        }
        return rawOutput
    }

    private fun cleanupSession(
        session: TestTreeSession,
        errors: MutableList<Throwable>,
    ) {
        if (session.persistablePermissionTaken) {
            val released =
                collectCleanupError(
                    errors,
                    "release persisted permission for ${session.sessionName}",
                ) {
                    context.contentResolver.releasePersistableUriPermission(
                        session.treeUri,
                        URI_READ_WRITE_FLAGS,
                    )
                }
            if (released) {
                session.persistablePermissionTaken = false
            }
        }

        if (session.controlCreateSucceeded) {
            collectCleanupError(
                errors,
                "revoke transient permission for ${session.sessionName}",
            ) {
                callControlProvider(
                    TestDocumentsControlProvider.METHOD_REVOKE_SESSION,
                    session.sessionName,
                )
            }
            collectCleanupError(
                errors,
                "verify transient permission revoked for ${session.sessionName}",
            ) {
                check(!hasTreeAccess(session.treeUri)) {
                    "Target still has access to ${session.treeUri}"
                }
            }
            collectCleanupError(errors, "delete ${session.sessionName}") {
                callControlProvider(
                    TestDocumentsControlProvider.METHOD_DELETE_SESSION,
                    session.sessionName,
                )
            }
        }

        collectCleanupError(
            errors,
            "verify no persisted permission for ${session.sessionName}",
        ) {
            check(
                context.contentResolver.persistedUriPermissions.none {
                    it.uri == session.treeUri
                },
            ) {
                "Persisted permission remains for ${session.treeUri}"
            }
        }
    }

    private fun hasTreeAccess(treeUri: Uri): Boolean =
        runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.exists() == true
        }.getOrDefault(false)

    private fun registerFallbackIdentity(identity: SongIdentity): File {
        val fileName = LyricsFileNaming.managedPlainFileName(identity)
        val file = File(File(fallbackLyricsDir, MANAGED_LYRICS_DIR), fileName)
        assertFalse("Unique fallback fixture collided with existing file: $file", file.exists())
        fallbackArtifacts += file
        testSongKeys += identity.storageKey()
        testManagedFileNames += fileName
        return file
    }

    private fun assertFallbackIndexUnchanged() {
        val current = snapshot(File(fallbackLyricsDir, INDEX_FILE_NAME))
        assertTrue(
            "Fallback index changed while SAF storage was selected",
            sameSnapshot(fallbackIndexBefore, current),
        )
    }

    private fun cleanupFallbackArtifacts(errors: MutableList<Throwable>) {
        fallbackArtifacts.forEach { artifact ->
            collectCleanupError(errors, "delete test fallback artifact $artifact") {
                check(!artifact.exists() || artifact.delete()) {
                    "Unable to delete test-owned fallback artifact: $artifact"
                }
            }
        }

        val indexFile = File(fallbackLyricsDir, INDEX_FILE_NAME)
        val current = snapshot(indexFile)
        if (sameSnapshot(fallbackIndexBefore, current)) return

        collectCleanupError(errors, "remove only test entries from fallback index") {
            restoreFallbackIndexIfOnlyTestEntriesChanged(indexFile, current)
        }
    }

    private fun restoreFallbackIndexIfOnlyTestEntriesChanged(
        indexFile: File,
        current: FileSnapshot,
    ) {
        if (!current.existed) {
            check(fallbackIndexBefore.existed) {
                "Fallback index disappeared without an original snapshot"
            }
            indexFile.writeBytes(requireNotNull(fallbackIndexBefore.bytes))
            return
        }

        val currentArray = JSONArray(requireNotNull(current.bytes).toString(Charsets.UTF_8))
        val remaining = JSONArray()
        var removedTestEntry = false
        repeat(currentArray.length()) { index ->
            val entry = currentArray.getJSONObject(index)
            val entryKey = entry.optString("key")
            val entryFile = entry.optString("file").substringAfterLast('/')
            if (entryKey in testSongKeys || entryFile in testManagedFileNames) {
                removedTestEntry = true
            } else {
                remaining.put(entry)
            }
        }
        check(removedTestEntry) {
            "Fallback index changed, but it contains no entry owned by this test"
        }

        val originalSemantic =
            if (fallbackIndexBefore.existed) {
                JSONArray(
                    requireNotNull(fallbackIndexBefore.bytes).toString(Charsets.UTF_8),
                ).toString()
            } else {
                JSONArray().toString()
            }
        check(remaining.toString() == originalSemantic) {
            "Fallback index also contains unrelated changes; refusing to overwrite them"
        }

        if (fallbackIndexBefore.existed) {
            indexFile.writeBytes(requireNotNull(fallbackIndexBefore.bytes))
        } else {
            check(indexFile.delete()) {
                "Unable to delete test-created fallback index"
            }
        }
        check(sameSnapshot(fallbackIndexBefore, snapshot(indexFile))) {
            "Fallback index was not restored exactly"
        }
    }

    private fun snapshot(file: File): FileSnapshot =
        if (file.exists()) {
            FileSnapshot(existed = true, bytes = file.readBytes())
        } else {
            FileSnapshot(existed = false, bytes = null)
        }

    private fun sameSnapshot(left: FileSnapshot, right: FileSnapshot): Boolean =
        left.existed == right.existed &&
            when {
                left.bytes == null -> right.bytes == null
                right.bytes == null -> false
                else -> left.bytes.contentEquals(right.bytes)
            }

    private inline fun collectCleanupError(
        errors: MutableList<Throwable>,
        label: String,
        action: () -> Unit,
    ): Boolean {
        return try {
            action()
            true
        } catch (error: Throwable) {
            errors += AssertionError("Cleanup failed: $label", error)
            false
        }
    }

    private fun readText(uri: Uri): String =
        requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use {
            it.readText()
        }

    private data class TestTreeSession(
        val sessionName: String,
        val documentUri: Uri,
        val treeUri: Uri,
        var controlCreateSucceeded: Boolean = false,
        var persistablePermissionTaken: Boolean = false,
    )

    private class FileSnapshot(
        val existed: Boolean,
        val bytes: ByteArray?,
    )

    private companion object {
        val CONTROL_URI: Uri =
            Uri.parse("content://${TestDocumentsControlProvider.AUTHORITY}")
        val SESSION_NAME_PATTERN =
            Regex(
                "^(?:session-|deny-write-)" +
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            )
        val CONTROL_METHODS =
            setOf(
                TestDocumentsControlProvider.METHOD_CREATE_SESSION,
                TestDocumentsControlProvider.METHOD_REVOKE_SESSION,
                TestDocumentsControlProvider.METHOD_DELETE_SESSION,
            )
        const val URI_READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
