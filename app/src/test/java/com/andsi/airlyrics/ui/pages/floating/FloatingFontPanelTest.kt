package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.controller.FloatingFontImportOutcome
import com.andsi.airlyrics.app.controller.FloatingFontImporter
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.core.model.FloatingFont
import com.andsi.airlyrics.core.model.FloatingFontNetworkException
import com.andsi.airlyrics.core.model.FloatingFontSource
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.i18n.localizedFloatingFontSize
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.MainDispatcherRule
import com.andsi.airlyrics.testutil.fontCollectionFixture
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorBackground
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w360dp-h740dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class FloatingFontPanelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val scheduler get() = mainDispatcherRule.dispatcher.scheduler
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val fixtures = mutableListOf<Fixture>()
    private var originalDurationScale = 1f

    @Before fun setup() {
        listOf("floating_lyrics_style", "floating_lyrics_font", "floating_quick_control",
            "app_theme", "airlyrics_language_settings").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(app.filesDir, "floating_fonts").deleteRecursively()
        LanguageSettingsStore.setMode(app, LanguageSettingsStore.MODE_SYSTEM)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        originalDurationScale = ReflectionHelpers.callStaticMethod(ValueAnimator::class.java, "getDurationScale")
        setDurationScale(0f)
    }

    @After fun cleanup() {
        fixtures.asReversed().forEach { it.controller.close() }
        scheduler.runCurrent()
        ShadowChoreographer.setPaused(false)
        setDurationScale(originalDurationScale)
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h740dp-mdpi")
        LanguageSettingsStore.setMode(app, LanguageSettingsStore.MODE_SYSTEM)
        File(app.filesDir, "floating_fonts").deleteRecursively()
    }

    @Test fun normalTileEntryAppliesSerifAndConfirmedRemovalPersists() = runTest(mainDispatcherRule.dispatcher) {
        val f = fixture()
        val bubble = f.bubble
        f.click("floating.font.row.system_default")
        assertSame(bubble, f.bubble)
        assertNull(f.find("floating.font.delete"))
        assertEquals(f.host.getString(R.string.ui_applied), f.text("floating.font.apply"))
        f.back()

        f.click("floating.font.row.serif")
        f.click("floating.font.apply")
        assertEquals("serif", f.host.floatingStyle().fontId)
        val serif = f.tag("floating.font.row.serif")
        assertTrue(serif.isSelected)
        val row = serif as ViewGroup
        val icons = row.getChildAt(row.childCount - 1) as ViewGroup
        assertEquals(1f, icons.getChildAt(1).alpha, 0.001f)
        setDurationScale(1f)
        f.click("floating.panel.undo")
        assertEquals("system_default", f.host.floatingStyle().fontId)
        assertSame(serif, f.tag("floating.font.row.serif"))
        assertFalse(serif.isSelected)
        assertTrue(f.tag("floating.font.row.system_default").isSelected)
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        assertTrue(icons.getChildAt(0).alpha in 0.01f..0.99f)
        assertTrue(icons.getChildAt(1).alpha in 0.01f..0.99f)
        repeat(14) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        assertEquals(1f, icons.getChildAt(0).alpha, 0.001f)
        assertEquals(0f, icons.getChildAt(1).alpha, 0.001f)
        setDurationScale(0f)
        f.settle()

        f.click("floating.font.row.serif")
        f.click("floating.font.apply")
        f.click("floating.font.row.serif")
        val delete = f.tag("floating.font.delete")
        delete.performClick()
        f.click("floating.font.language.zh")
        assertEquals(f.host.getString(R.string.ui_delete), delete.contentDescription)
        delete.performClick()
        delete.performClick()
        assertNotNull(FloatingLyricsFontStore.font(f.host, "serif"))
        assertEquals(f.host.getString(R.string.ui_font_confirm_delete), delete.contentDescription)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        delete.performClick()
        await { FloatingLyricsFontStore.font(f.host, "serif") == null }
        await { f.find("floating.font.row.system_default") != null }
        assertEquals("system_default", f.host.floatingStyle().fontId)
        assertNull(f.find("floating.font.row.serif"))
        f.closePanel()
        f.openFontTile()
        assertNull(f.find("floating.font.row.serif"))
        assertTrue(f.tag("floating.font.row.system_default").isSelected)
    }

    @Test fun importsAreAddOnlyAndDeletingOnePreservesTheOther() = runTest(mainDispatcherRule.dispatcher) {
        val f = fixture()
        val first = import(f, "First handwritten font.ttf", R.font.font_preview_swei_gothic_sc)
        val second = import(f, "Second handwritten font.ttf", R.font.font_preview_wenkai)
        assertNotEquals(first.fontId, second.fontId)
        assertEquals("system_default", f.host.floatingStyle().fontId)
        assertEquals(2, FloatingLyricsFontStore.fonts(f.host).count { it.source == FloatingFontSource.IMPORTED })

        f.scope.pageRefs.refreshFonts!!.invoke(first.fontId)
        f.settle()
        assertEquals(f.host.getString(R.string.ui_apply), f.text("floating.font.apply"))
        f.click("floating.font.apply")
        assertEquals(first.fontId, f.host.floatingStyle().fontId)
        f.click("floating.font.row.${first.fontId}")
        confirmDelete(f)
        await { FloatingLyricsFontStore.font(f.host, first.fontId) == null }
        await { f.find("floating.font.row.${second.fontId}") != null }
        assertEquals("system_default", f.host.floatingStyle().fontId)
        assertNotNull(FloatingLyricsFontStore.font(f.host, second.fontId))
        f.click("floating.font.row.${second.fontId}")
        f.click("floating.font.apply")
        f.closePanel()
        f.openFontTile()
        assertTrue(f.tag("floating.font.row.${second.fontId}").isSelected)
        assertNull(f.find("floating.font.row.${first.fontId}"))
    }

    @Test fun collectionImportOpensMemberChooserAndDeletionKeepsSelectionConsistent() =
        runTest(mainDispatcherRule.dispatcher) {
            val layouts = listOf("en-rUS" to 360, "zh-rCN" to 320)
            for ((locale, width) in layouts) {
                val narrow = width == 320
                val f = fixture(locale, width, if (narrow) 2f else 1f, dark = narrow)
                val source = fontCollectionFixture(
                    f.host, File(f.host.cacheDir, "我的圆体与手写字型 Mixed typefaces.ttc"),
                    R.font.font_preview_swei_gothic_sc, R.font.font_preview_wenkai, version = 1
                )
                f.click("floating.font.import")
                val activity = shadowOf(f.controller.get())
                val request = activity.nextStartedActivityForResult
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
                assertTrue("font/collection" in request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty())
                activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))

                await { f.find("floating.font.delete")?.contentDescription ==
                    f.host.getString(R.string.ui_delete_font_collection) }
                f.settle()
                val collection = FloatingLyricsFontStore.collections(f.host).single()
                val (rounded, handwriting) = FloatingLyricsFontStore.fonts(f.host)
                    .filter { it.collectionId == collection.id }
                val collectionRow = "floating.font.row.${collection.id}"
                val roundedRow = "floating.font.row.${rounded.id}"
                val handwritingRow = "floating.font.row.${handwriting.id}"
                assertNotNull(f.find(roundedRow))
                assertNotNull(f.find(handwritingRow))
                assertNull(f.find("floating.font.size.${rounded.id}"))
                assertEquals("system_default", f.host.floatingStyle().fontId)
                assertLayout(f)
                capture(f, "ttc-$locale-members")

                f.click(handwritingRow)
                for ((language, expected) in listOf(
                    "ja" to "これは歌詞プレビューの一行です",
                    "zh" to "这是一行歌词预览",
                    "en" to "This is a lyric preview"
                )) {
                    f.click("floating.font.language.$language")
                    assertEquals(expected, f.text("floating.font.sample"))
                }
                assertLayout(f)
                capture(f, "ttc-$locale-specimen")
                f.back()
                assertNotNull(f.find(roundedRow))
                f.click(handwritingRow)
                f.click("floating.font.apply")
                assertEquals(handwriting.id, f.host.floatingStyle().fontId)
                assertTrue(f.tag(collectionRow).isSelected)
                assertNull(f.find(handwritingRow))
                assertEquals(f.host.localizedFloatingFontSize(source.length()),
                    f.text("floating.font.size.${collection.id}"))
                all(f.bubble).filterIsInstance<ScrollView>().single().fullScroll(View.FOCUS_DOWN)
                f.settle()
                capture(f, "ttc-$locale-list")
                assertLayout(f)

                f.closePanel()
                f.openFontTile()
                f.click(collectionRow)
                assertTrue(f.tag(handwritingRow).isSelected)
                f.click(handwritingRow)
                confirmDelete(f)
                await { f.find(roundedRow) != null && f.find(handwritingRow) == null }
                assertEquals("system_default", f.host.floatingStyle().fontId)
                assertNotNull(FloatingLyricsFontStore.font(f.host, rounded.id))

                if (narrow) {
                    f.click(roundedRow)
                    confirmDelete(f)
                } else {
                    val remove = f.tag("floating.font.delete")
                    remove.performClick()
                    remove.performClick()
                    assertEquals(f.host.getString(R.string.ui_confirm_delete_font_collection), remove.contentDescription)
                    assertEquals(collection, FloatingLyricsFontStore.collections(f.host).single())
                    f.click(roundedRow)
                    f.back()
                    assertEquals(f.host.getString(R.string.ui_delete_font_collection),
                        f.tag("floating.font.delete").contentDescription)
                    confirmDelete(f)
                }
                await { f.find("floating.font.row.system_default") != null && f.find(collectionRow) == null }
                assertTrue(FloatingLyricsFontStore.collections(f.host).isEmpty())
                assertTrue(f.tag("floating.font.row.system_default").isSelected)
                f.closePanel()
                source.delete()
            }
        }

    @Test fun deletingDownloadedFontReturnsItsDetailToDownloadAndApply() = runTest(mainDispatcherRule.dispatcher) {
        val f = fixture()
        assertTrue(FloatingLyricsFontStore.installDownloadedFont(
            f.host, "swei_gothic_sc", fontFixture(f, "Downloaded.ttf", R.font.font_preview_swei_gothic_sc)
        ))
        f.settle()
        f.click("floating.font.row.swei_gothic_sc")
        f.click("floating.font.apply")
        assertEquals("swei_gothic_sc", f.host.floatingStyle().fontId)
        f.click("floating.font.row.swei_gothic_sc")
        confirmDelete(f)
        await { !FloatingLyricsFontStore.isAvailable(f.host, "swei_gothic_sc") }
        await { f.text("floating.font.apply") == f.host.getString(R.string.ui_font_download_apply) }
        assertEquals("system_default", f.host.floatingStyle().fontId)
        assertNull(f.find("floating.font.delete"))
        assertSame(f.bubble, f.scope.activeBubble)
        f.closePanel()
        f.openFontTile()
        f.click("floating.font.row.swei_gothic_sc")
        assertEquals(f.host.getString(R.string.ui_font_download_apply), f.text("floating.font.apply"))
    }

    @Test fun downloadProgressNeverInventsBytesAndDuplicateClicksOrCancellationDoNotApply() =
        runTest(mainDispatcherRule.dispatcher) {
            val f = fixture()
            val operations = FakeOperations(ignoreCancellation = true)
            f.inject(operations)
            f.click("floating.font.row.swei_gothic_sc")
            f.click("floating.font.apply")
            f.tag("floating.font.apply").performClick()
            scheduler.runCurrent()
            assertEquals(1, operations.requests.size)
            assertFalse(f.tag("floating.font.apply").isEnabled)
            assertEquals(0, f.progress())
            val pending = operations.requests.single()
            pending.progress(40)
            var previous = 0
            repeat(20) {
                scheduler.advanceTimeBy(300)
                scheduler.runCurrent()
                val shown = f.progress()
                assertTrue("Progress cannot lead received bytes", shown in previous..40)
                previous = shown
            }
            pending.progress(100)
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            assertTrue(f.progress() <= 99)
            assertTrue(operations.selections.isEmpty())
            f.click("floating.font.cancel")
            pending.result.complete(Unit)
            scheduler.runCurrent()
            assertTrue("A late cancelled transfer must never apply", operations.selections.isEmpty())
            assertEquals("system_default", operations.selectedId())
        }

    @Test fun backCloseAndDetachCancelTheTransferBeforeLateCompletionCanApply() =
        runTest(mainDispatcherRule.dispatcher) {
            for (exit in listOf("back", "close", "detach")) {
                val f = fixture()
                val operations = FakeOperations(ignoreCancellation = true)
                f.inject(operations)
                f.click("floating.font.row.swei_gothic_sc")
                f.click("floating.font.apply")
                scheduler.runCurrent()
                val pending = operations.requests.single()
                when (exit) {
                    "back" -> f.back()
                    "close" -> f.closePanel()
                    else -> (f.manager.parent as ViewGroup).removeView(f.manager)
                }
                scheduler.runCurrent()
                pending.result.complete(Unit)
                scheduler.runCurrent()
                assertTrue("$exit must reject late completion", operations.selections.isEmpty())
                assertEquals("system_default", operations.selectedId())
            }
        }

    @Test fun failedTransferCanRetryAndOnlyCompletedInstallationApplies() = runTest(mainDispatcherRule.dispatcher) {
        val f = fixture()
        val operations = FakeOperations()
        f.inject(operations)
        f.click("floating.font.row.swei_gothic_sc")
        f.click("floating.font.apply")
        scheduler.runCurrent()
        operations.requests.single().result.completeExceptionally(IOException("Unexpected font download size"))
        scheduler.runCurrent()
        assertTrue(f.tag("floating.font.apply").isEnabled)
        assertEquals(f.host.getString(R.string.ui_font_download_apply), f.text("floating.font.apply"))
        assertTrue(all(f.manager).filterIsInstance<TextView>().any {
            it.visibility == View.VISIBLE && it.text == f.host.getString(R.string.ui_font_download_failed)
        })
        assertTrue(operations.selections.isEmpty())
        f.click("floating.font.apply")
        scheduler.runCurrent()
        assertEquals(2, operations.requests.size)
        operations.requests.last().result.complete(Unit)
        scheduler.runCurrent()
        f.settle()
        assertEquals(listOf("swei_gothic_sc"), operations.selections)
        assertTrue(f.tag("floating.font.row.swei_gothic_sc").isSelected)
    }

    @Test fun networkFailureShowsLocalizedStatusAndAllowsRetry() = runTest(mainDispatcherRule.dispatcher) {
        for ((locale, label) in listOf("en-rUS" to "Network error", "zh-rCN" to "网络错误")) {
            for (width in listOf(360, 320)) {
                val f = fixture(locale, width)
                val operations = FakeOperations()
                f.inject(operations)
                f.click("floating.font.row.swei_gothic_sc")
                f.click("floating.font.apply")
                operations.requests.single().result.completeExceptionally(
                    FloatingFontNetworkException(SocketTimeoutException())
                )
                f.settle()

                val status = all(f.manager).filterIsInstance<TextView>().single { it.text.toString() == label }
                val retry = f.tag("floating.font.apply")
                assertTrue("Retry must be enabled after a network error", retry.isEnabled)
                assertTrue(operations.selections.isEmpty())
                all(f.bubble).filterIsInstance<ScrollView>().single().fullScroll(View.FOCUS_DOWN)
                assertLayout(f)
                assertTrue(status.getGlobalVisibleRect(android.graphics.Rect()))
                assertTrue(retry.getGlobalVisibleRect(android.graphics.Rect()))
                capture(f, "$locale-network-error-$width")
                f.closePanel()
            }
        }
    }

    @Test fun fullFileSizesAndImportedNamesRemainReadableAcrossWidthsAndFontScales() =
        runTest(mainDispatcherRule.dispatcher) {
            data class Case(val locale: String, val width: Int, val scale: Float,
                val dark: Boolean, val name: String)
            val cases = listOf(
                Case("en-rUS", 360, 1f, false, "en-normal-imports"),
                Case("en-rUS", 320, 2f, false, "en-narrow-imports"),
                Case("zh-rCN", 360, 1f, true, "zh-normal-imports"),
                Case("zh-rTW", 320, 2f, true, "zh-narrow-imports")
            )
            for (case in cases) {
                val f = fixture(case.locale, case.width, case.scale, case.dark)
                assertEquals("16.3 MB", f.text("floating.font.size.swei_gothic_sc"))
                assertEquals("25.9 MB", f.text("floating.font.size.wenkai"))
                assertNull(f.find("floating.font.size.system_default"))
                assertNull(f.find("floating.font.size.serif"))
                assertTrue(f.tag("floating.font.row.swei_gothic_sc").contentDescription.contains("16.3 MB"))
                if (case.scale == 1f) capture(f, "${case.name}-presets")
                val firstName = "MyImportedFontWithoutAnyBreakOpportunitiesABCDEFGHIJKLMNOPQRSTUVWXYZ.ttf"
                val secondName = "我自己匯入的字體 中文字幕與日本語 Mixed-language font.otf"
                val first = import(f, firstName, R.font.font_preview_swei_gothic_sc)
                val second = import(f, secondName, R.font.font_preview_wenkai)
                f.settle()
                assertEquals(firstName, f.text("floating.font.name.${first.fontId}"))
                assertEquals(secondName, f.text("floating.font.name.${second.fontId}"))
                val firstSize = f.host.localizedFloatingFontSize(File(f.host.cacheDir, firstName).length())
                val secondSize = f.host.localizedFloatingFontSize(File(f.host.cacheDir, secondName).length())
                assertEquals(firstSize, f.text("floating.font.size.${first.fontId}"))
                assertEquals(secondSize, f.text("floating.font.size.${second.fontId}"))
                assertLayout(f)

                f.click("floating.font.row.${first.fontId}")
                f.click("floating.font.apply")
                f.closePanel()
                f.openFontTile()
                assertTrue(f.tag("floating.font.row.${first.fontId}").isSelected)
                assertEquals("74.3 KB", f.text("floating.font.size.${first.fontId}"))
                all(f.bubble).filterIsInstance<ScrollView>().single().fullScroll(View.FOCUS_DOWN)
                f.settle()
                assertLayout(f)
                capture(f, case.name)
                f.closePanel()
            }
        }

    @Test fun languageTransitionRetargetsBothTheSpecimenAndTheButtons() =
        runTest(mainDispatcherRule.dispatcher) {
            val f = fixture()
            f.click("floating.font.row.serif")

            fun specimen() = f.tag("floating.font.sample") as TextView
            fun button(code: String) = f.tag("floating.font.language.$code") as TextView
            fun tap(code: String) { assertTrue(button(code).performClick()) }
            fun advanceFrames(count: Int) {
                repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            }

            val japanese = specimen()
            val viewport = japanese.parent as ViewGroup
            val originalHeight = viewport.height
            val originalButtonTop = (button("en").parent as View).top
            val englishBackground = button("en").background
            val unselectedColor = button("en").currentTextColor
            val selectedColor = button("ja").currentTextColor

            setDurationScale(1f)
            tap("en")
            val english = specimen()
            assertEquals("This is a lyric preview", english.text.toString())
            assertTrue(button("en").isSelected)
            assertFalse(button("ja").isSelected)
            assertEquals(0f, english.alpha, .01f)
            assertTrue(english.translationY > 0f)
            advanceFrames(4)
            f.settle()
            assertTrue("Outgoing specimen alpha: ${japanese.alpha}", japanese.alpha in 0.01f..0.99f)
            assertTrue("Incoming specimen alpha: ${english.alpha}", english.alpha in 0.01f..0.99f)
            assertNotEquals(unselectedColor, button("en").currentTextColor)
            assertNotEquals(selectedColor, button("en").currentTextColor)
            assertSame(englishBackground, button("en").background)
            assertEquals(originalHeight, viewport.height)
            assertEquals(originalButtonTop, (button("en").parent as View).top)

            tap("zh")
            assertTrue(button("zh").isSelected)
            assertEquals("这是一行歌词预览", specimen().text.toString())
            assertEquals(2, all(viewport).filterIsInstance<TextView>().count { it.visibility == View.VISIBLE })
            tap("ja")
            advanceFrames(18)
            f.settle()
            assertSame(japanese, specimen())
            assertTrue(button("ja").isSelected)
            assertEquals(selectedColor, button("ja").currentTextColor)
            assertEquals(unselectedColor, button("en").currentTextColor)
            assertEquals(1f, japanese.alpha, .01f)
            assertEquals(0f, japanese.translationY, .01f)
            assertEquals(1, all(viewport).filterIsInstance<TextView>().count { it.visibility == View.VISIBLE })

            setDurationScale(0f)
            tap("en")
            assertSame(english, specimen())
            assertEquals(1f, english.alpha, .01f)
            assertEquals(0f, english.translationY, .01f)
            assertTrue(button("en").isSelected)
        }

    @Test fun nativeScreensShowListAndThreeSpecimenLanguagesAcrossWidthsLocalesAndThemes() =
        runTest(mainDispatcherRule.dispatcher) {
            data class Case(val locale: String, val width: Int, val scale: Float,
                val dark: Boolean, val language: String, val name: String,
                val fontId: String = "swei_gothic_sc")
            val cases = listOf(
                Case("en-rUS", 360, 1f, true, "ja", "en-dark-wenkai-ja", "wenkai"),
                Case("en-rUS", 360, 1f, false, "ja", "en-light-normal-ja"),
                Case("en-rUS", 360, 1f, false, "en", "en-light-normal-en"),
                Case("en-rUS", 320, 2f, false, "en", "en-light-narrow-en"),
                Case("zh-rCN", 360, 1f, true, "zh", "zh-cn-dark-normal-zh"),
                Case("zh-rTW", 320, 2f, true, "ja", "zh-tw-dark-narrow-ja")
            )
            for (case in cases) {
                val f = fixture(case.locale, case.width, case.scale, case.dark)
                assertLayout(f)
                if (case.scale == 1f) {
                    capture(f, "${case.name}-list")
                    f.click("floating.font.row.serif")
                    capture(f, "${case.name}-trash")
                    f.click("floating.font.delete")
                    capture(f, "${case.name}-confirm")
                    f.back()
                    f.click("floating.font.row.serif")
                    assertEquals(f.host.getString(R.string.ui_delete),
                        f.tag("floating.font.delete").contentDescription)
                    f.back()
                }
                f.click("floating.font.row.${case.fontId}")
                f.click("floating.font.language.${case.language}")
                val expected = when (case.language) {
                    "zh" -> "这是一行歌词预览"
                    "en" -> "This is a lyric preview"
                    else -> "これは歌詞プレビューの一行です"
                }
                assertEquals(expected, f.text("floating.font.sample"))
                assertLayout(f)
                capture(f, case.name)
                if (case.scale == 2f) {
                    all(f.bubble).filterIsInstance<ScrollView>().single().fullScroll(View.FOCUS_DOWN)
                    f.settle()
                    capture(f, "${case.name}-bottom")
                    assertTrue(f.tag("floating.font.apply").getGlobalVisibleRect(android.graphics.Rect()))
                }
                f.closePanel()
            }
        }

    private inner class Fixture(
        val controller: ActivityController<MainActivity>,
        val host: MainUiHost,
        val scope: FloatingPageScope,
        val page: View,
        val width: Int
    ) {
        val bubble get() = requireNotNull(scope.activeBubble)
        val manager get() = all(bubble).filterIsInstance<FloatingFontPanelContent>().single()
        fun find(key: String): View? = all(bubble).firstOrNull { it.getTag(R.id.interaction_anchor) == key }
        fun tag(key: String): View = requireNotNull(find(key)) { "Missing control $key" }
        fun text(key: String) = (tag(key) as TextView).text.toString()
        fun progress() = Regex("(\\d+)%").find(text("floating.font.apply"))!!.groupValues[1].toInt()
        fun click(key: String) { assertTrue(tag(key).performClick()); settle() }
        fun settle() {
            scheduler.runCurrent()
            layout(controller.get().window.decorView, width)
            layout(page, width)
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.runCurrent()
            layout(page, width)
        }
        fun back() {
            assertTrue(host.floatingPanelBackHandler!!.invoke())
            settle()
        }
        fun closePanel() {
            all(bubble).single { it.contentDescription == host.getString(R.string.ui_close) }.performClick()
            settle()
            assertNull(scope.activeBubble)
        }
        fun openFontTile() {
            val tile = all(page).single { it.getTag(R.id.interaction_anchor) == "panel:FONT" }
            assertTrue(tile.performClick())
            settle()
            assertNotNull(scope.activeBubble)
        }
        fun inject(operations: FontPanelOperations) {
            val previous = manager
            val parent = previous.parent as LinearLayout
            parent.removeView(previous)
            parent.addView(FloatingFontPanelContent(host, requireNotNull(scope.activePanelNavigation),
                onChanged = {}, operations = operations))
            settle()
        }
    }

    private fun fixture(locale: String = "en-rUS", width: Int = 360, scale: Float = 1f,
        dark: Boolean = false): Fixture {
        RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h740dp-mdpi")
        RuntimeEnvironment.setFontScale(scale)
        LanguageSettingsStore.setMode(app, when (locale) {
            "zh-rCN" -> LanguageSettingsStore.MODE_ZH_CN
            "zh-rTW" -> LanguageSettingsStore.MODE_ZH_TW
            else -> LanguageSettingsStore.MODE_EN
        })
        ThemeSettingsStore.setDark(app, dark)
        ThemeSettingsStore.setAccent(app, ThemeAccent.ORANGE)
        ThemeSettingsStore.setButtonStyle(app, ButtonStyle.SOLID)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val host = controller.get().graph.uiHost
        host.interactions.panel = null
        host.setLyricsSwitchAnimationMode(LyricsSwitchAnimationMode.NONE)
        host.setWordByWordLyricsEnabled(false)
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.runCurrent()
        val scope = FloatingPageScope(host)
        val page = scope.createView()
        controller.get().setContentView(page)
        controller.visible()
        // Advance finite frames so the window attaches before testing view lifecycle events.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        return Fixture(controller, host, scope, page, width).also {
            fixtures += it
            it.settle()
            assertTrue("The settings page must be attached to its real Activity window: parent=${page.parent}, decorAttached=${controller.get().window.decorView.isAttachedToWindow}", page.isAttachedToWindow)
            it.openFontTile()
        }
    }

    private fun import(f: Fixture, name: String, resource: Int): FloatingFontImportOutcome.Success {
        val outcome = FloatingFontImporter(f.host).import(Uri.fromFile(fontFixture(f, name, resource)))
        assertTrue("A real font resource must import", outcome is FloatingFontImportOutcome.Success)
        return outcome as FloatingFontImportOutcome.Success
    }

    private fun fontFixture(f: Fixture, name: String, resource: Int): File =
        File(f.host.cacheDir, name).also { file ->
            f.host.resources.openRawResource(resource).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }

    private fun confirmDelete(f: Fixture) {
        f.tag("floating.font.delete").performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        f.tag("floating.font.delete").performClick()
    }

    private suspend fun await(condition: () -> Boolean) {
        repeat(100) {
            scheduler.runCurrent()
            if (condition()) return
            // Real repository mutations deliberately use Dispatchers.IO.
            withContext(Dispatchers.IO) { delay(10.milliseconds) }
        }
        assertTrue("The asynchronous font operation did not complete", condition())
    }

    private fun assertLayout(f: Fixture) {
        f.settle()
        assertTrue(f.bubble.width <= f.width)
        for (view in all(f.bubble).filterIsInstance<TextView>().filter { it.isShown && it.width > 0 }) {
            val text = view.layout ?: continue
            assertTrue("Text was ellipsized: ${view.text}",
                (0 until text.lineCount).all { text.getEllipsisCount(it) == 0 })
            assertTrue("Text overflows its bounds: ${view.text}",
                (0 until text.lineCount).all {
                    text.getLineMax(it) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1
                })
        }
        for (view in all(f.bubble).filter { it.isShown && it.isClickable &&
            it.getTag(R.id.interaction_anchor)?.toString()?.startsWith("floating.font.") == true }) {
            assertTrue("Touch target too short", view.height >= f.host.dp(48))
            assertTrue("Touch target too narrow", view.width >= f.host.dp(48))
        }
    }

    private fun capture(f: Fixture, name: String) {
        val bitmap = Bitmap.createBitmap(f.page.width, f.page.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(f.host.colorBackground); f.page.draw(this) }
            File("/tmp/airlyrics-font-sizes-review-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun layout(view: View, width: Int) {
        all(view).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_LTR }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun setDurationScale(value: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, value))
    }

    private class FakeOperations(private val ignoreCancellation: Boolean = false) : FontPanelOperations {
        class Pending(val progress: (Int) -> Unit, val result: CompletableDeferred<Unit> = CompletableDeferred())
        val requests = mutableListOf<Pending>()
        val selections = mutableListOf<String>()
        private var selected = "system_default"
        private var available = false

        override fun fonts() = listOf(
            FloatingFont("system_default", FloatingLyricsFontFamily.SYSTEM_DEFAULT, null, FloatingFontSource.SYSTEM, true),
            FloatingFont("swei_gothic_sc", FloatingLyricsFontFamily.ROUNDED, null, FloatingFontSource.DOWNLOAD, available)
        )
        override fun selectedId() = selected
        override fun previewTypeface(id: String): Typeface = Typeface.DEFAULT
        override fun select(id: String): Boolean {
            if (!available) return false
            selections += id
            selected = id
            return true
        }
        override fun remove(id: String) = false
        override suspend fun download(id: String, onProgress: (Int) -> Unit) {
            val pending = Pending(onProgress).also(requests::add)
            if (ignoreCancellation) {
                try { withContext(NonCancellable) { pending.result.await() } }
                catch (_: CancellationException) { /* Simulate a transport returning after cancellation. */ }
            } else pending.result.await()
            available = true
        }
    }
}
