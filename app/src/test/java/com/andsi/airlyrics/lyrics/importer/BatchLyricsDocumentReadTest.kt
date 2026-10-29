package com.andsi.airlyrics.lyrics.importer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class BatchLyricsDocumentReadTest {
    private lateinit var context: Context
    private lateinit var provider: DocumentProvider
    private val uri = Uri.parse("content://batch.read/fallback.lrc")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = DocumentProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = "batch.read" })
            it.file = File(context.cacheDir, "reader-fixture.lrc")
            it.file.writeText("[ti:Song]\n[00:01.00]hello")
        }
        ShadowContentResolver.registerProviderInternal("batch.read", provider)
    }
    @After fun cleanup() { provider.file.delete(); ShadowContentResolver.reset() }

    @Test fun readsContentUriAndFingerprintTracksContent() {
        val first = BatchLyricsDocumentReader.read(context, uri)
        assertNull(first.failure)
        assertEquals("selected.lrc", first.name)
        assertEquals("Song", first.document!!.title)
        assertEquals(64, first.fingerprint.length)
        assertEquals(first.fingerprint, BatchLyricsDocumentReader.read(context, uri).fingerprint)
        provider.file.writeText("[00:02.00]changed")
        val changed = BatchLyricsDocumentReader.read(context, uri)
        assertNull(changed.failure)
        assertNotEquals(first.fingerprint, changed.fingerprint)
    }
    @Test fun missingOrThrowingDisplayNameFallsBackToUri() {
        provider.name = null
        assertEquals("fallback.lrc", BatchLyricsDocumentReader.read(context, uri).name)
        provider.queryFails = true
        val read = BatchLyricsDocumentReader.read(context, uri)
        assertEquals("fallback.lrc", read.name)
        assertNull(read.failure)
    }
    @Test fun unreadableStreamIsReadFailureNotFormatFailure() {
        provider.openFails = true
        val result = BatchLyricsDocumentReader.read(context, uri)
        assertEquals(BatchLyricsDocumentReader.Failure.READ, result.failure)
        assertNull(result.document)
    }
    @Test fun oversizedInputIsRejectedWithoutChangingSource() {
        val maxBytes = 64
        java.io.RandomAccessFile(provider.file, "rw").use { it.setLength(maxBytes.toLong() + 1) }
        val length = provider.file.length()
        val result = BatchLyricsDocumentReader.read(context, uri, maxBytes)
        assertEquals(BatchLyricsDocumentReader.Failure.TOO_LARGE, result.failure)
        assertNull(result.document)
        assertEquals(length, provider.file.length())
    }
    @Test fun utf16BomReachesParserAsText() {
        provider.file.writeBytes("[00:01.00]hello".toByteArray(Charsets.UTF_16))
        val result = BatchLyricsDocumentReader.read(context, uri)
        assertNull(result.failure)
        assertTrue(result.document!!.plainLrc.contains("hello"))
    }

    class DocumentProvider : ContentProvider() {
        lateinit var file: File
        var name: String? = "selected.lrc"
        var queryFails = false
        var openFails = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (queryFails) throw IllegalStateException("metadata unavailable")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (openFails) throw FileNotFoundException("controlled read failure")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri) = "application/lrc"
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
    }
}
