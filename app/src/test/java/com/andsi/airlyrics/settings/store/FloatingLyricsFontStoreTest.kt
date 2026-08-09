package com.andsi.airlyrics.settings.store

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import java.io.File
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
}
