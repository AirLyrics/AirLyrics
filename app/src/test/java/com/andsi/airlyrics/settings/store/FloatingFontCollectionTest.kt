package com.andsi.airlyrics.settings.store

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.testutil.fontCollectionFixture
import com.andsi.airlyrics.ui.state.restoreFields
import com.andsi.airlyrics.ui.state.undoChangesTo
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingFontCollectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun contextFor(directory: File): Context = object : ContextWrapper(app) {
        override fun getFilesDir(): File = directory
    }

    @Test
    @Config(sdk = [28, 35])
    fun importsDistinctIndexedFacesAndRetainsSharedDataWhenOneIsDeleted() {
        val context = contextFor(temporary.newFolder())
        assertTrue(FloatingLyricsFontStore.selectFont(context, "system_default"))
        val defaultStyle = FloatingLyricsStyleStore.getStyle(context)
        val rounded = R.font.font_preview_swei_gothic_sc
        val handwriting = R.font.font_preview_wenkai
        val source = fontCollectionFixture(
            context, temporary.newFile("Example.TTC"), rounded, handwriting, rounded
        )
        val result = FloatingLyricsFontStore.importFont(context, Uri.fromFile(source))
        assertTrue(result is FloatingLyricsFontStore.ImportResult.Success)
        val collection = FloatingLyricsFontStore.collections(context).single()
        val fonts = FloatingLyricsFontStore.fonts(context).filter { it.collectionId == collection.id }
        assertEquals("Example.TTC", collection.displayName)
        assertEquals(source.length(), collection.sizeBytes)
        assertEquals(3, fonts.size)
        assertEquals(3, fonts.map { it.displayName }.toSet().size)
        assertTrue(fonts.all { it.sizeBytes == null })
        assertEquals(defaultStyle, FloatingLyricsStyleStore.getStyle(context))

        val stored = fontDirectory(context).listFiles().orEmpty().single { it.extension == "font" }
        assertArrayEquals(source.readBytes(), stored.readBytes())
        val roundedWidth = width(requireNotNull(ResourcesCompat.getFont(context, rounded)))
        val handwritingWidth = width(requireNotNull(ResourcesCompat.getFont(context, handwriting)))
        assertTrue(kotlin.math.abs(roundedWidth - handwritingWidth) > 1f)
        for ((index, font) in fonts.withIndex()) {
            val expected = if (index == 1) handwritingWidth else roundedWidth
            assertEquals(expected, width(FloatingLyricsFontStore.resolvePreviewTypeface(context, font.id, 400)), .01f)
            assertTrue(FloatingLyricsFontStore.selectFont(context, font.id))
            assertEquals(font.id, FloatingLyricsStyleStore.getStyle(context).fontId)
            assertEquals(expected, width(appliedTypeface(context)), .01f)
        }

        val handwritingId = fonts[1].id
        assertTrue(FloatingLyricsFontStore.selectFont(context, handwritingId))
        val undo = FloatingLyricsStyleStore.getStyle(context).undoChangesTo(defaultStyle)
        val reopened = contextFor(temporary.newFolder())
        fontDirectory(context).copyRecursively(fontDirectory(reopened))
        assertEquals(collection, FloatingLyricsFontStore.collections(reopened).single())
        assertEquals(handwritingId, FloatingLyricsStyleStore.getStyle(reopened).fontId)
        assertEquals(handwritingWidth, width(appliedTypeface(reopened)), .01f)

        assertTrue(FloatingLyricsFontStore.removeFont(reopened, handwritingId))
        assertNull(FloatingLyricsFontStore.font(reopened, handwritingId))
        assertEquals(defaultStyle, FloatingLyricsStyleStore.getStyle(reopened))
        FloatingLyricsStyleStore.setStyle(reopened, defaultStyle.restoreFields(undo))
        assertEquals(defaultStyle, FloatingLyricsStyleStore.getStyle(reopened))
        val stillShared = File(fontDirectory(reopened), stored.name)
        assertArrayEquals(source.readBytes(), stillShared.readBytes())
        assertTrue(FloatingLyricsFontStore.selectFont(reopened, fonts.last().id))
        assertEquals(roundedWidth, width(appliedTypeface(reopened)), .01f)

        assertTrue(FloatingLyricsFontStore.removeCollection(reopened, collection.id))
        assertEquals(defaultStyle, FloatingLyricsStyleStore.getStyle(reopened))
        assertTrue(FloatingLyricsFontStore.collections(reopened).isEmpty())
        assertTrue(fonts.none { FloatingLyricsFontStore.selectFont(reopened, it.id) })
        assertFalse(stillShared.exists())
    }

    @Test
    fun invalidCollectionsPreserveTheCurrentFontAndLeaveNoPendingFile() {
        val context = contextFor(temporary.newFolder())
        assertTrue(FloatingLyricsFontStore.selectFont(context, "serif"))
        fun header(faceCount: Int) = ByteBuffer.allocate(12).apply {
            putInt(0x74746366)
            putInt(0x00010000)
            putInt(faceCount)
        }.array()
        val file = temporary.newFile("Incomplete.ttc")
        file.writeBytes(header(2))
        assertEquals(FloatingLyricsFontStore.ImportResult.InvalidFont,
            FloatingLyricsFontStore.importFont(context, Uri.fromFile(file)))
        file.writeBytes(header(65))
        assertEquals(FloatingLyricsFontStore.ImportResult.TooManyFaces,
            FloatingLyricsFontStore.importFont(context, Uri.fromFile(file)))
        assertEquals("serif", FloatingLyricsStyleStore.getStyle(context).fontId)
        assertTrue(FloatingLyricsFontStore.collections(context).isEmpty())
        assertTrue(fontDirectory(context).listFiles().orEmpty().isEmpty())
    }

    private fun fontDirectory(context: Context) = File(context.filesDir, "floating_fonts")

    private fun width(face: Typeface): Float = Paint().apply {
        typeface = face
        textSize = 40f
    }.measureText("This is a lyric preview")

    private fun appliedTypeface(context: Context): Typeface {
        val style = FloatingLyricsStyleStore.getStyle(context)
        return FloatingLyricsFontStore.resolveTypeface(context, style.fontFamily, style.fontWeight, style.customFontId)
    }
}
