package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.FloatingFontNetworkException
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import com.andsi.airlyrics.core.model.FloatingFontSource
import com.andsi.airlyrics.ui.state.restoreFields
import com.andsi.airlyrics.ui.state.undoChangesTo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsFontStoreTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(context.cacheDir, "floating-font-store-test").apply {
            deleteRecursively()
            mkdirs()
        }
        File(context.filesDir, "floating_fonts").deleteRecursively()
        context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("floating_lyrics_font", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
        File(context.filesDir, "floating_fonts").deleteRecursively()
    }

    @Test
    fun supportedFontFileName_acceptsTtfOtfAndTtc() {
        assertTrue(FloatingLyricsFontStore.isSupportedFontFileName("My Font.TTF"))
        assertTrue(FloatingLyricsFontStore.isSupportedFontFileName("custom.otf"))
        assertTrue(FloatingLyricsFontStore.isSupportedFontFileName("font.TTC"))
        assertFalse(FloatingLyricsFontStore.isSupportedFontFileName("font.txt"))
    }

    @Test
    fun importFont_rejectsUnsupportedAndInvalidFiles() {
        val unsupported = File(testDirectory, "font.txt").apply { writeText("not a font") }
        assertSame(
            FloatingLyricsFontStore.ImportResult.UnsupportedFormat,
            FloatingLyricsFontStore.importFont(context, Uri.fromFile(unsupported))
        )

        val invalid = File(testDirectory, "font.ttf").apply { writeText("not a font") }
        assertSame(
            FloatingLyricsFontStore.ImportResult.InvalidFont,
            FloatingLyricsFontStore.importFont(context, Uri.fromFile(invalid))
        )
    }

    @Test
    fun repeatedImportsKeepStableIdentitiesAndDeleteOnlyTheRequestedFace() {
        val first = importFixture("First Font.ttf")
        val second = importFixture("Second Font.otf")

        assertNotEquals(first.id, second.id)
        assertEquals(listOf(first.id, second.id), importedIds())
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(FloatingLyricsFontStore.selectFont(context, first.id))
        assertEquals(first.id, FloatingLyricsStyleStore.getStyle(context).customFontId)
        assertEquals("First Font.ttf", FloatingLyricsFontStore.customFontDisplayName(context, first.id))
        assertEquals(File(testDirectory, "First Font.ttf").length(),
            FloatingLyricsFontStore.font(context, first.id)?.sizeBytes)

        assertTrue(FloatingLyricsFontStore.removeFont(context, second.id))
        assertEquals(listOf(first.id), importedIds())
        assertEquals(first.id, FloatingLyricsStyleStore.getStyle(context).fontId)

        val selected = FloatingLyricsStyleStore.getStyle(context)
        assertTrue(FloatingLyricsFontStore.removeFont(context, first.id))
        assertTrue(importedIds().isEmpty())
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        FloatingLyricsStyleStore.setStyle(context, selected)
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
    }

    @Test
    fun staleCustomSelectionWithoutIdentityCannotSelectAnUnrelatedImport() {
        importFixture("New unrelated font.ttf")
        val default = FloatingLyricsStyleStore.getStyle(context)
        FloatingLyricsStyleStore.setStyle(context, default.copy(
            fontFamily = FloatingLyricsFontFamily.CUSTOM, customFontId = null
        ))
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        val staleUndo = android.os.Bundle().apply { putString("fontFamily", "custom") }
        FloatingLyricsStyleStore.setStyle(context, default.restoreFields(staleUndo))
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
    }

    @Test
    fun legacyCustomFontIsMigratedAndCannotReappearAfterItsFinalDeletion() {
        writeValidFontFixture(storedCustomFont().apply { parentFile!!.mkdirs() })
        context.getSharedPreferences("floating_lyrics_font", Context.MODE_PRIVATE).edit()
            .putString("custom_font_name", "Legacy.ttf").commit()
        context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE).edit()
            .putString("font_family", "custom").commit()

        assertEquals("custom", FloatingLyricsStyleStore.getStyle(context).customFontId)
        assertEquals(listOf("custom"), importedIds())
        assertEquals("Legacy.ttf", FloatingLyricsFontStore.customFontDisplayName(context))
        assertTrue(FloatingLyricsFontStore.removeFont(context, "custom"))
        assertTrue(importedIds().isEmpty())
        assertFalse(storedCustomFont().exists())
        assertTrue(File(context.filesDir, "floating_fonts/catalog.json").isFile)
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(importedIds().isEmpty())
    }

    @Test
    fun onlySystemIsProtectedAndDeletedPresetCannotBeRestoredByUndo() {
        assertFalse(FloatingLyricsFontStore.removeFont(context, "system_default"))
        FloatingLyricsStyleStore.setTextSize(context, 32f)
        assertTrue(FloatingLyricsFontStore.selectFont(context, "serif"))
        val before = FloatingLyricsStyleStore.getStyle(context)
        assertTrue(FloatingLyricsFontStore.removeFont(context, "serif"))
        val after = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(32f, after.textSizeSp)
        assertEquals("system_default", after.fontId)
        assertFalse(FloatingLyricsFontStore.fonts(context).any { it.id == "serif" })

        FloatingLyricsStyleStore.setStyle(context, after.restoreFields(before.undoChangesTo(after)))
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertFalse(FloatingLyricsFontStore.selectFont(context, "serif"))
    }

    @Test
    fun retiredFontsCannotMasqueradeAsReplacementsOrReturnThroughUndo() {
        val retiredIds = listOf("casual", "rounded", "cursive")
        val retiredFiles = retiredIds.map { id ->
            File(context.filesDir, "floating_fonts/$id.font").apply {
                parentFile!!.mkdirs()
                writeValidFontFixture(this)
            }
        }
        val retained = importFixture("Keep this font.ttf")
        val preferences = context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
        for (id in retiredIds) {
            preferences.edit().putString("font_family", id).commit()
            assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
            assertEquals("system_default", preferences.getString("font_family", null))
            assertFalse(FloatingLyricsFontStore.fonts(context).any { it.id == id })
            val staleUndo = android.os.Bundle().apply { putString("fontFamily", id) }
            FloatingLyricsStyleStore.setStyle(context,
                FloatingLyricsStyleStore.getStyle(context).restoreFields(staleUndo))
            assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        }

        FloatingLyricsFontStore.removeRetiredFontFiles(context)
        assertTrue(retiredFiles.none { it.exists() })
        assertTrue(FloatingLyricsFontStore.isAvailable(context, retained.id))
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "wenkai"))
    }

    @Test
    fun fullSizeCjkFontCanBeImportedAndInstalledWithoutApplyingAndOversizedImportIsRejected() {
        val fullSize = File(testDirectory, "Full CJK.ttf").also(::writeValidFontFixture)
        // Exercise the supplied full WenKai's size using valid SFNT padding, without
        // adding a 25.9 MB binary to the test resources.
        java.io.RandomAccessFile(fullSize, "rw").use { it.setLength(25_928_940L) }
        assertTrue(FloatingLyricsFontStore.selectFont(context, "serif"))

        val imported = FloatingLyricsFontStore.importFont(context, Uri.fromFile(fullSize))
        assertTrue(imported is FloatingLyricsFontStore.ImportResult.Success)
        assertTrue(FloatingLyricsFontStore.installDownloadedFont(context, "wenkai", fullSize))
        assertTrue(FloatingLyricsFontStore.isAvailable(context, "wenkai"))
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)

        val oversized = File(testDirectory, "Oversized.ttf").also(::writeValidFontFixture)
        java.io.RandomAccessFile(oversized, "rw").use { it.setLength(32L * 1024L * 1024L + 1L) }
        assertSame(FloatingLyricsFontStore.ImportResult.TooLarge,
            FloatingLyricsFontStore.importFont(context, Uri.fromFile(oversized)))
        assertEquals(1, importedIds().size)
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(pendingFiles().isEmpty())
    }

    @Test
    fun downloadableFontReturnsToPreviewOnlyAndCanBeInstalledAgain() {
        val fixture = File(testDirectory, "download.ttf").also(::writeValidFontFixture)
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertEquals(16_312_184L, FloatingLyricsFontStore.font(context, "swei_gothic_sc")?.sizeBytes)
        assertEquals(25_928_940L, FloatingLyricsFontStore.font(context, "wenkai")?.sizeBytes)
        assertEquals(null, FloatingLyricsFontStore.font(context, "system_default")?.sizeBytes)
        assertEquals(null, FloatingLyricsFontStore.font(context, "serif")?.sizeBytes)
        assertTrue(FloatingLyricsFontStore.installDownloadedFont(context, "swei_gothic_sc", fixture))
        assertTrue(FloatingLyricsFontStore.selectFont(context, "swei_gothic_sc"))
        assertEquals("swei_gothic_sc", FloatingLyricsStyleStore.getStyle(context).fontId)

        assertTrue(FloatingLyricsFontStore.removeFont(context, "swei_gothic_sc"))
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertEquals(FloatingFontSource.DOWNLOAD, FloatingLyricsFontStore.font(context, "swei_gothic_sc")?.source)
        assertEquals(16_312_184L, FloatingLyricsFontStore.font(context, "swei_gothic_sc")?.sizeBytes)
        assertFalse(FloatingLyricsFontStore.selectFont(context, "swei_gothic_sc"))

        assertTrue(FloatingLyricsFontStore.installDownloadedFont(context, "swei_gothic_sc", fixture))
        assertTrue(FloatingLyricsFontStore.selectFont(context, "swei_gothic_sc"))
        assertEquals("swei_gothic_sc", FloatingLyricsStyleStore.getStyle(context).fontId)
    }

    @Test
    fun corruptOrUnknownMetadataCannotExposeArbitraryFilesOrReviveUnavailableFonts() {
        val metadata = File(context.filesDir, "floating_fonts/catalog.json").apply { parentFile!!.mkdirs() }
        metadata.writeText("not json")
        assertEquals(listOf("system_default", "swei_gothic_sc", "wenkai"),
            FloatingLyricsFontStore.fonts(context).map { it.id })
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "serif"))
        metadata.writeText("""{"version":99,"imports":[]}""")
        assertEquals(listOf("system_default", "swei_gothic_sc", "wenkai"),
            FloatingLyricsFontStore.fonts(context).map { it.id })

        metadata.writeText("""{"version":1,"imports":[{"id":"custom","name":"bad","file":"../catalog.json"}]}""")
        assertTrue(importedIds().isEmpty())
        assertFalse(FloatingLyricsFontStore.selectFont(context, "missing"))
    }

    private fun importFixture(name: String): FloatingLyricsFontStore.ImportResult.Success {
        val file = File(testDirectory, name).also(::writeValidFontFixture)
        val result = FloatingLyricsFontStore.importFont(context, Uri.fromFile(file))
        assertTrue(result is FloatingLyricsFontStore.ImportResult.Success)
        return result as FloatingLyricsFontStore.ImportResult.Success
    }

    private fun importedIds(): List<String> = FloatingLyricsFontStore.fonts(context)
        .filter { it.source == FloatingFontSource.IMPORTED }.map { it.id }

    @Test
    fun downloadVerifiesAndLoadsBeforeCompletingWithoutApplying() = runBlocking {
        val bytes = File(testDirectory, "download.ttf").also(::writeValidFontFixture).readBytes()
        val progress = mutableListOf<Int>()
        assertTrue(FloatingLyricsFontStore.selectFont(context, "serif"))
        FloatingLyricsFontStore.downloadFont(context, "swei_gothic_sc", { value ->
            if (value == 100) assertTrue(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
            progress += value
        }, memoryDownloader(bytes), spec(bytes))

        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> next >= previous })
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(FloatingLyricsFontStore.selectFont(context, "swei_gothic_sc"))
        assertEquals("swei_gothic_sc", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(pendingFiles().isEmpty())
    }

    @Test
    fun transportFailuresReportNetworkErrorAndClearPendingFiles() = runBlocking {
        val connectionFailure = UnknownHostException("Font source unavailable")
        val readFailure = SocketTimeoutException("Font response stalled")
        val connectionDownloader = FloatingFontDownloader { _, _ -> throw connectionFailure }
        val readDownloader = FloatingFontDownloader { _, _ ->
            val input = object : InputStream() {
                override fun read(): Int = throw readFailure
            }
            response(input, 16L)
        }
        val spec = FloatingFontDownloadSpec("swei_gothic_sc", "test", 16L, "0".repeat(64))

        for ((downloader, cause) in listOf(connectionDownloader to connectionFailure, readDownloader to readFailure)) {
            val failure = runCatching {
                FloatingLyricsFontStore.downloadFont(context, spec.id, {}, downloader, spec)
            }.exceptionOrNull()
            assertTrue(failure is FloatingFontNetworkException)
            assertSame(cause, failure?.cause)
            assertFalse(FloatingLyricsFontStore.isAvailable(context, spec.id))
            assertTrue(pendingFiles().isEmpty())
        }
    }

    @Test
    fun failedVerificationPreservesCurrentFontAndAllowsRetry() = runBlocking {
        val bytes = File(testDirectory, "download.ttf").also(::writeValidFontFixture).readBytes()
        assertTrue(FloatingLyricsFontStore.selectFont(context, "serif"))
        var failure: Exception? = null
        try {
            FloatingLyricsFontStore.downloadFont(context, "swei_gothic_sc", {},
                memoryDownloader(bytes), spec(bytes).copy(sha256 = "0".repeat(64)))
        } catch (caught: IOException) {
            failure = caught
        }
        assertTrue(failure is IOException)
        assertFalse(failure is FloatingFontNetworkException)
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(pendingFiles().isEmpty())

        FloatingLyricsFontStore.downloadFont(context, "swei_gothic_sc", {}, memoryDownloader(bytes), spec(bytes))
        assertTrue(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)
    }

    @Test
    fun cancellationClosesTransportAndLeavesSelectionAndFilesUnchanged() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val stream = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                if (!release.await(3, TimeUnit.SECONDS)) throw IOException("Test stream timed out")
                if (closed.get()) throw IOException("Closed")
                return -1
            }
            override fun close() { closed.set(true); release.countDown() }
        }
        val downloader = FloatingFontDownloader { _, register ->
            register(Closeable { stream.close() })
            response(stream, 16L)
        }
        assertTrue(FloatingLyricsFontStore.selectFont(context, "serif"))
        val job = launch(Dispatchers.Default) {
            FloatingLyricsFontStore.downloadFont(context, "swei_gothic_sc", {}, downloader,
                FloatingFontDownloadSpec("swei_gothic_sc", "test", 16L, "0".repeat(64)))
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(release.await(3, TimeUnit.SECONDS))
        assertTrue(closed.get())
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(pendingFiles().isEmpty())
    }

    @Test
    fun deletionInvalidatesAnInFlightDownloadAndDuplicateRequestsAreRejected() = runBlocking {
        val fixture = File(testDirectory, "download.ttf").also(::writeValidFontFixture)
        val bytes = fixture.readBytes()
        assertTrue(FloatingLyricsFontStore.installDownloadedFont(context, "swei_gothic_sc", fixture))
        assertTrue(FloatingLyricsFontStore.selectFont(context, "swei_gothic_sc"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                if (!release.await(3, TimeUnit.SECONDS)) throw IOException("Test stream timed out")
                return super.read(buffer, offset, length)
            }
        }
        val downloader = FloatingFontDownloader { _, register ->
            register(Closeable { release.countDown(); stream.close() })
            response(stream, bytes.size.toLong())
        }
        val request = async(Dispatchers.Default) {
            runCatching { FloatingLyricsFontStore.downloadFont(context, "swei_gothic_sc", {}, downloader, spec(bytes)) }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        var duplicate: Exception? = null
        try {
            FloatingLyricsFontStore.downloadFont(context, "swei_gothic_sc", {}, memoryDownloader(bytes), spec(bytes))
        } catch (failure: IOException) { duplicate = failure }
        assertTrue(duplicate is IOException)
        assertTrue(FloatingLyricsFontStore.removeFont(context, "swei_gothic_sc"))
        release.countDown()

        assertTrue(request.await().exceptionOrNull() is IOException)
        assertFalse(FloatingLyricsFontStore.isAvailable(context, "swei_gothic_sc"))
        assertEquals("system_default", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(pendingFiles().isEmpty())
    }

    private fun memoryDownloader(bytes: ByteArray) = FloatingFontDownloader { _, register ->
        val input = ByteArrayInputStream(bytes)
        register(Closeable { input.close() })
        response(input, bytes.size.toLong())
    }

    private fun response(stream: InputStream, length: Long) = object : FloatingFontDownloadResponse {
        override val input = stream
        override val contentLength = length
        override fun close() = input.close()
    }

    private fun spec(bytes: ByteArray) = FloatingFontDownloadSpec(
        "swei_gothic_sc", "test", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    )

    private fun pendingFiles() = File(context.filesDir, "floating_fonts").listFiles().orEmpty()
        .filter { it.name.startsWith("pending_") }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun resolveTypeface_preservesEachFontFamilyAndNormalizesRawWeights() {
        val systemBases = mapOf(
            FloatingLyricsFontFamily.SYSTEM_DEFAULT to Typeface.DEFAULT,
            FloatingLyricsFontFamily.SERIF to Typeface.SERIF
        )
        val families = FloatingLyricsFontFamily.entries.filter { it != FloatingLyricsFontFamily.CUSTOM }
        // Independent boundary examples: do not call the production normalizer here.
        val weights = mapOf(-1 to 100, 149 to 100, 150 to 200, 555 to 600, 900 to 900, 1001 to 900)
        families.forEach { family ->
            weights.forEach { (raw, expectedWeight) ->
                val actual = FloatingLyricsFontStore.resolveTypeface(context, family, raw)
                assertEquals("$family at $raw", expectedWeight, actual.weight)
                systemBases[family]?.let { base ->
                    assertEquals("$family at $raw", Typeface.create(base, expectedWeight, false), actual)
                }
                assertFalse(actual.isItalic)
            }
        }
    }

    @Test
    fun hasWeightVariationAxis_readsFvarAxisRecords() {
        val weightVariableFont = File(testDirectory, "weight-variable.ttf")
        val widthVariableFont = File(testDirectory, "width-variable.ttf")
        writeMinimalVariableFont(weightVariableFont, "wght")
        writeMinimalVariableFont(widthVariableFont, "wdth")

        assertTrue(FloatingLyricsFontStore.hasWeightVariationAxis(weightVariableFont))
        assertFalse(FloatingLyricsFontStore.hasWeightVariationAxis(widthVariableFont))
    }

    private fun storedCustomFont(): File {
        return File(context.filesDir, "floating_fonts/custom_font")
    }

    private fun writeValidFontFixture(file: File) {
        val encoded = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(VALID_FONT_FIXTURE)
        ).bufferedReader().use { it.readText() }
        file.writeBytes(Base64.getMimeDecoder().decode(encoded))
    }

    private fun writeMinimalVariableFont(file: File, axisTag: String) {
        val tableOffset = 28
        val fvarLength = 36
        val bytes = ByteBuffer.allocate(tableOffset + fvarLength)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(0x0001_0000)
                putShort(1.toShort())
                putShort(0.toShort())
                putShort(0.toShort())
                putShort(0.toShort())

                put("fvar".toByteArray(Charsets.US_ASCII))
                putInt(0)
                putInt(tableOffset)
                putInt(fvarLength)

                putShort(1.toShort())
                putShort(0.toShort())
                putShort(16.toShort())
                putShort(2.toShort())
                putShort(1.toShort())
                putShort(20.toShort())
                putShort(0.toShort())
                putShort(0.toShort())

                put(axisTag.toByteArray(Charsets.US_ASCII))
                putInt(100 shl 16)
                putInt(400 shl 16)
                putInt(900 shl 16)
                putShort(0.toShort())
                putShort(256.toShort())
            }
            .array()
        file.writeBytes(bytes)
    }

    private companion object {
        const val VALID_FONT_FIXTURE = "font-fixtures/noto-sans-lydian-regular.ttf.base64"
    }
}
