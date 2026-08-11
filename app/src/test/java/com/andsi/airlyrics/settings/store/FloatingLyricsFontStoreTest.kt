package com.andsi.airlyrics.settings.store

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    fun supportedFontFileName_acceptsTtfAndOtfOnly() {
        assertTrue(FloatingLyricsFontStore.isSupportedFontFileName("My Font.TTF"))
        assertTrue(FloatingLyricsFontStore.isSupportedFontFileName("custom.otf"))
        assertFalse(FloatingLyricsFontStore.isSupportedFontFileName("font.ttc"))
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
    fun resolveTypeface_supportsEverySystemFamilyAndNormalizedWeight() {
        FloatingLyricsFontFamily.entries
            .filter { it != FloatingLyricsFontFamily.CUSTOM }
            .forEach { family ->
                assertNotNull(
                    FloatingLyricsFontStore.resolveTypeface(
                        context,
                        family,
                        FloatingLyricsFontWeight.normalize(555)
                    )
                )
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
}
