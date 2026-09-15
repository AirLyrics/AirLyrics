package com.andsi.airlyrics.lyrics.importer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class LyricsFormatDetectorTest {
    private lateinit var context: Context
    private lateinit var provider: MetadataProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = MetadataProvider()
        provider.attachInfo(
            context,
            ProviderInfo().apply { authority = TEST_AUTHORITY }
        )
        ShadowContentResolver.registerProviderInternal(TEST_AUTHORITY, provider)
    }

    @After
    fun tearDown() {
        ShadowContentResolver.reset()
    }

    @Test
    fun detect_usesRecognizedMetadataInPriorityOrder() {
        val cases = listOf(
            DetectionCase(
                name = "display-name extension is authoritative",
                displayName = "LYRICS.LRC",
                mimeType = "application/ttml+xml",
                path = "lyrics.ttml",
                content = "<?xml version=\"1.0\"?><tt/>",
                expected = LyricsDocumentFormat.LRC
            ),
            DetectionCase(
                name = "recognized path extension wins when display name is unclear",
                displayName = "downloaded-file",
                mimeType = "text/plain",
                path = "folder/lyrics.TTML",
                content = "[00:01.00]line",
                expected = LyricsDocumentFormat.TTML
            ),
            DetectionCase(
                name = "TTML MIME type wins when names are unclear",
                displayName = "lyrics.data",
                mimeType = "application/ttml+xml",
                content = "[00:01.00]line",
                expected = LyricsDocumentFormat.TTML
            )
        )

        cases.forEach(::assertDetected)
    }

    @Test
    fun detect_recognizesLrcMimeTypes() {
        provider.displayName = "lyrics.data"

        listOf(
            "application/x-lrc",
            "application/lrc",
            "text/lrc"
        ).forEach { mimeType ->
            provider.mimeType = mimeType
            assertEquals(
                mimeType,
                LyricsDocumentFormat.LRC,
                detect(content = "<tt/>")
            )
        }
    }

    @Test
    fun detect_treatsGenericMimeTypesAsAmbiguousAndUsesContentSignature() {
        provider.displayName = "lyrics.data"

        listOf(
            "text/plain; charset=UTF-8",
            "application/xml",
            "text/xml; charset=utf-8",
            "application/octet-stream"
        ).forEach { mimeType ->
            provider.mimeType = mimeType
            assertEquals(
                mimeType,
                LyricsDocumentFormat.TTML,
                detect(content = "<tt><body/></tt>")
            )
            assertEquals(
                mimeType,
                LyricsDocumentFormat.LRC,
                detect(content = "[00:01.00]line")
            )
        }
    }

    @Test
    fun detect_recognizesTtmlRootsAfterSupportedPrefixes() {
        val cases = listOf(
            DetectionCase(
                name = "XML declaration",
                displayName = "lyrics.data",
                mimeType = "application/octet-stream",
                content = "\uFEFF  \n\t<?xml version=\"1.0\"?><tt/>",
                expected = LyricsDocumentFormat.TTML
            ),
            DetectionCase(
                name = "BOM and whitespace",
                displayName = null,
                mimeType = null,
                content = "\n\uFEFF <tt xmlns=\"http://www.w3.org/ns/ttml\">",
                expected = LyricsDocumentFormat.TTML
            ),
            DetectionCase(
                name = "leading XML comment",
                displayName = "lyrics.data",
                mimeType = "text/plain",
                content = "\uFEFF  <!-- exported lyrics -->\n<tt><body/></tt>",
                expected = LyricsDocumentFormat.TTML
            )
        )

        cases.forEach(::assertDetected)
    }

    @Test
    fun detect_recognizesLrcContentSignatureWhenMetadataIsUnclear() {
        provider.displayName = "lyrics.data"
        provider.mimeType = "application/octet-stream"

        assertEquals(
            LyricsDocumentFormat.LRC,
            detect(content = "\uFEFF[ar:Artist]\n[00:01.00]line")
        )
    }

    @Test
    fun detect_returnsUnknownWhenMetadataAndContentAreUnrecognized() {
        provider.displayName = "lyrics.data"
        provider.mimeType = "application/octet-stream"

        listOf(
            "plain text without timing",
            "[ar:Artist]",
            "",
            "\uFEFF  \n"
        ).forEach { content ->
            assertEquals(
                content,
                LyricsDocumentFormat.UNKNOWN,
                detect(content = content)
            )
        }
    }

    @Test
    fun detect_doesNotTreatArbitraryXmlAsTtml() {
        provider.displayName = "lyrics.xml"
        provider.mimeType = "application/xml"

        assertEquals(
            LyricsDocumentFormat.UNKNOWN,
            detect(content = "<?xml version=\"1.0\"?><svg/>")
        )
    }

    @Test
    fun detect_fallsBackAfterProviderMetadataFailures() {
        provider.throwOnQuery = true
        provider.throwOnGetType = true

        assertEquals(
            LyricsDocumentFormat.TTML,
            detect(path = "lyrics.unknown", content = "  <tt/>")
        )
    }

    private fun detect(
        path: String = "lyrics.unknown",
        content: String
    ): LyricsDocumentFormat = LyricsFormatDetector.detect(
        context = context,
        uri = Uri.parse("content://$TEST_AUTHORITY/$path"),
        content = content
    )

    private fun assertDetected(case: DetectionCase) {
        provider.displayName = case.displayName
        provider.mimeType = case.mimeType
        assertEquals(
            case.name,
            case.expected,
            detect(path = case.path, content = case.content)
        )
    }

    private data class DetectionCase(
        val name: String,
        val displayName: String?,
        val mimeType: String?,
        val path: String = "lyrics.unknown",
        val content: String,
        val expected: LyricsDocumentFormat
    )

    private class MetadataProvider : ContentProvider() {
        var displayName: String? = null
        var mimeType: String? = null
        var throwOnQuery: Boolean = false
        var throwOnGetType: Boolean = false

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            if (throwOnQuery) error("query failed")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf(displayName))
            }
        }

        override fun getType(uri: Uri): String? {
            if (throwOnGetType) error("getType failed")
            return mimeType
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }

    private companion object {
        const val TEST_AUTHORITY = "com.andsi.airlyrics.test.lyrics-format-detector"
    }
}
