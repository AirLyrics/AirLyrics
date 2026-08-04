package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageConcurrencyInvariantTest {
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
    fun concurrentPlainAndWordByWordImport_preservesMutualExclusion() {
        val plainUri = Uri.parse("content://airlyrics.test/plain-race.lrc")
        val blockingPlainInput = GateInputStream("[00:10.00]manual plain")
        shadowOf(context.contentResolver).registerInputStream(plainUri, blockingPlainInput)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val plainFuture = executor.submit<LyricsStorage.ImportLyricsResult> {
                LyricsStorage.importPlainLyricsFromUriWithResult(
                    context = context,
                    uri = plainUri,
                    title = TITLE,
                    artist = ARTIST,
                    duration = DURATION_MS
                )
            }

            assertTrue(
                "Plain import did not reach its controlled read boundary",
                blockingPlainInput.awaitReadStarted()
            )

            val wordByWordResult = LyricsStorage.importWordByWordLyricsFromUriWithResult(
                context = context,
                uri = writeImportFile(),
                title = TITLE,
                artist = ARTIST,
                duration = DURATION_MS
            )
            assertTrue(
                "Word-by-word import should win while the earlier plain import is paused",
                wordByWordResult is LyricsStorage.ImportLyricsResult.Saved
            )

            blockingPlainInput.releaseRead()
            val plainResult = plainFuture.get(5, TimeUnit.SECONDS)

            assertTrue(
                "The resumed plain import must observe the committed word-by-word lyrics, but was $plainResult",
                plainResult is LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
            )
            assertTrue(LyricsStorage.hasWordByWordLyrics(context, TITLE, ARTIST, DURATION_MS))
            assertEquals(
                "[00:10.00]karaoke",
                LyricsStorage.readPlainLyrics(context, TITLE, ARTIST, DURATION_MS)
            )

            val matchingEntries = LyricsIndexStore.read(context)
                .filter { it.title == TITLE && it.artist == ARTIST }
            assertEquals("Exactly one index entry must own both generated files", 1, matchingEntries.size)
            val entry = matchingEntries.single()
            assertEquals(LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK, entry.plainSource)
            assertTrue(entry.plainFile.isNotBlank())
            assertTrue(entry.wordByWordFile.isNotBlank())
            assertEquals(
                "[00:10.00]karaoke",
                LyricsFileStore.readManagedLyrics(context, entry.plainFile)
            )
            assertTrue(
                LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile)
                    ?.isNotBlank() == true
            )
        } finally {
            blockingPlainInput.releaseRead()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun writeImportFile(): Uri {
        return Uri.fromFile(
            File(context.cacheDir, "word-by-word-race.lrc").apply {
                writeText("[00:10.00]<00:10.00>karaoke")
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

    private class GateInputStream(text: String) : InputStream() {
        private val delegate = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        private val readStarted = CountDownLatch(1)
        private val readReleased = CountDownLatch(1)

        override fun read(): Int {
            awaitRelease()
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            awaitRelease()
            return delegate.read(buffer, offset, length)
        }

        override fun close() {
            delegate.close()
        }

        fun awaitReadStarted(): Boolean = readStarted.await(5, TimeUnit.SECONDS)

        fun releaseRead() {
            readReleased.countDown()
        }

        private fun awaitRelease() {
            readStarted.countDown()
            if (!readReleased.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out waiting to release the controlled lyrics read")
            }
        }
    }

    private companion object {
        const val TITLE = "Concurrent Import Song"
        const val ARTIST = "AndSi"
        const val DURATION_MS = 180_000L
    }
}
