package com.andsi.airlyrics.lyrics.importer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class LyricsImportValidatorTest {
    private lateinit var context: Context
    private lateinit var provider: LyricsDocumentProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = Robolectric.setupContentProvider(LyricsDocumentProvider::class.java, AUTHORITY)
    }

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
    }

    @Test
    fun supportedExtensions_areAcceptedWhenProviderMimeIsUnrelated() {
        provider.mimeType = "application/pdf"

        listOf("LYRICS.LRC", "LYRICS.TTML").forEach { displayName ->
            provider.displayName = displayName
            assertTrue(
                "$displayName should be accepted",
                LyricsImportValidator.isLikelyLyricsDocument(context, DOCUMENT_URI)
            )
        }
    }

    @Test
    fun supportedAndAmbiguousMimeTypes_areAcceptedWithoutKnownExtension() {
        provider.displayName = "lyrics.data"

        listOf(
            "application/x-lrc",
            "application/lrc",
            "text/lrc",
            "application/ttml+xml",
            "application/xml; charset=utf-8",
            "text/xml",
            "text/plain",
            "application/octet-stream"
        ).forEach { mimeType ->
            provider.mimeType = mimeType
            assertTrue(
                "$mimeType should be accepted",
                LyricsImportValidator.isLikelyLyricsDocument(context, DOCUMENT_URI)
            )
        }
    }

    @Test
    fun unrelatedExtensionAndMime_areRejected() {
        provider.displayName = "cover.pdf"
        provider.mimeType = "application/pdf"

        assertFalse(LyricsImportValidator.isLikelyLyricsDocument(context, DOCUMENT_URI))
    }

    class LyricsDocumentProvider : ContentProvider() {
        var displayName: String = "lyrics.data"
        var mimeType: String = "application/octet-stream"

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)).toList().toTypedArray()
            return MatrixCursor(columns).apply {
                val row = arrayOfNulls<Any>(columns.size)
                columns.forEachIndexed { index, column ->
                    row[index] = when (column) {
                        OpenableColumns.DISPLAY_NAME -> displayName
                        OpenableColumns.SIZE -> 1L
                        else -> null
                    }
                }
                addRow(row)
            }
        }

        override fun getType(uri: Uri): String = mimeType

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }

    private companion object {
        const val AUTHORITY = "com.andsi.airlyrics.test.lyrics-import-validator"
        val DOCUMENT_URI: Uri = Uri.parse("content://$AUTHORITY/document/lyrics")
    }
}
