package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class LyricsSourcesMorphTest {
    @After fun resetFont() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun draftSurvivesRecreationButOnlyApplyChangesSourcesWithoutSearching() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            var activity = controller.get()
            layout(activity.findViewById(android.R.id.content), 400, 900)
            val initial = setOf(PlainLyricsSearchSource.NETEASE)
            session(activity).setSources(initial)
            val sheet = show(activity, initial)
            val added = PlainLyricsSearchSource.LRCLIB
            val names = sheetViews(sheet).filterIsInstance<LyricsSourceSelectionRow>().map { it.contentDescription }
            sheetViews(sheet).filterIsInstance<LyricsSourceSelectionRow>().single {
                it.contentDescription == activity.getString(R.string.provider_lrclib)
            }.performClick()
            assertEquals(initial, session(activity).state.value.sources)
            controller.recreate()
            activity = controller.get()
            settle()
            val restored = activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
            layout(restored.requireDialog().window!!.decorView, 400, 900)
            settle()
            assertEquals(names, sheetViews(restored).filterIsInstance<LyricsSourceSelectionRow>().map { it.contentDescription })
            val results = session(activity).state.value.results
            apply(restored).performClick()
            assertEquals(initial + added, session(activity).state.value.sources)
            assertEquals(results, session(activity).state.value.results)
            assertNull(session(activity).state.value.submittedQuery)
            settle()
            assertNull(activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG))
            assertEquals(1f, activity.sourceCapsule()!!.alpha, 0.001f)
        }
    }

    @Test fun zeroSelectionDisablesApplyWithoutMovingFooterAndBackDiscardsDraft() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val activity = controller.get()
            layout(activity.findViewById(android.R.id.content), 400, 900)
            val initial = setOf(PlainLyricsSearchSource.NETEASE)
            session(activity).setSources(initial)
            val sheet = show(activity, initial)
            val before = Rect()
            apply(sheet).getGlobalVisibleRect(before)
            sheetViews(sheet).filterIsInstance<LyricsSourceSelectionRow>().first { it.isSelected }.performClick()
            layout(sheet.requireDialog().window!!.decorView, 400, 900)
            val after = Rect()
            apply(sheet).getGlobalVisibleRect(after)
            assertEquals(before, after)
            assertFalse(apply(sheet).isEnabled)
            assertTrue(sheetViews(sheet).filterIsInstance<TextView>().single {
                it.text.toString() == activity.getString(R.string.search_sources_choose_one)
            }.isShown)
            (sheet.requireDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
            settle()
            assertEquals(initial, session(activity).state.value.sources)
            assertNull(activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG))
        }
    }

    @Test fun reversalKeepsCurrentGeometryAndNeverReflowsTheSearchPage() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val activity = controller.get()
            val page = activity.findViewById<ViewGroup>(android.R.id.content)
            layout(page, 400, 900)
            val button = descendants(page).filterIsInstance<MaterialButton>().single {
                it.text.toString() == activity.getString(R.string.search_regular_search)
            }
            val searchTop = IntArray(2).also { button.getLocationOnScreen(it) }.toList()
            val sheet = show(activity, setOf(PlainLyricsSearchSource.NETEASE))
            val morph = sheetViews(sheet).filterIsInstance<LyricsSourcesMorphView>().single()
            // Drive the framework animator after attachment, independently of Robolectric's vsync.
            fun running() = ReflectionHelpers.getField<ValueAnimator>(morph, "animator")
            morph.setOpen(false)
            assertEquals(360L, running().duration)
            running().currentPlayTime = 180
            assertTrue("Expected an intermediate frame, progress=${morph.progress}", morph.progress > 0f && morph.progress < 1f)
            val bounds = android.graphics.RectF(morph.surfaceBounds)
            morph.setOpen(true)
            assertEquals(bounds, morph.surfaceBounds)
            running().currentPlayTime = 20
            val reversing = android.graphics.RectF(morph.surfaceBounds)
            morph.setOpen(false)
            assertEquals(reversing, morph.surfaceBounds)
            morph.setOpen(true)
            running().end()
            assertEquals(morph.panelBounds, morph.surfaceBounds)
            assertEquals(morph.capsuleBounds.right, morph.panelBounds.right, 0.01f)
            assertEquals(searchTop, IntArray(2).also { button.getLocationOnScreen(it) }.toList())
            assertEquals(1f, sheetViews(sheet).filterIsInstance<LyricsSourceSelectionRow>().first().scaleX, 0.001f)
            sheet.requireDialog().cancel()
            running().end()
            settle()
            assertEquals(1f, activity.sourceCapsule()!!.alpha, 0.001f)
        }
    }

    @Test fun resultsArrivingBehindThePanelWaitToScrollUntilItCloses() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val activity = controller.get()
            layout(activity.findViewById(android.R.id.content), 400, 900)
            val state = session(activity).state.value
            val source = PlainLyricsSearchSource.NETEASE
            val updates = ReflectionHelpers.getField<kotlinx.coroutines.flow.MutableStateFlow<LyricsSearchState>>(
                session(activity), "mutable")
            updates.value = state.copy(sources = setOf(source), results = mapOf(source to SourceResults(loading = true)))
            settle()
            val sheet = show(activity, setOf(source))
            ReflectionHelpers.setField(activity, "pendingSourceFocus", true)
            val focusResults = LyricsSearchActivity::class.java.getDeclaredMethod("focusSourcesIfReady")
                .apply { isAccessible = true }
            focusResults.invoke(activity)
            assertTrue(ReflectionHelpers.getField<Boolean>(activity, "pendingSourceFocus"))
            assertTrue(sheet.requireDialog().isShowing)
            sheet.requireDialog().cancel()
            settle()
            assertFalse(ReflectionHelpers.getField<Boolean>(activity, "pendingSourceFocus"))
            assertEquals(1f, activity.sourceCapsule()!!.alpha, 0.001f)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactLargeTypeAndBothThemesKeepControlsInsideThePanel() {
        RuntimeEnvironment.setFontScale(2f)
        for (dark in listOf(false, true)) {
            ThemeSettingsStore.setDark(RuntimeEnvironment.getApplication(), dark)
            Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
                val activity = controller.get()
                val page = activity.findViewById<ViewGroup>(android.R.id.content)
                layout(page, 320, 640)
                val pill = activity.sourceCapsule()!!
                assertTrue(pill.height >= 48)
                val header = pill.parent as ViewGroup
                val action = Rect(pill.left, pill.top, pill.right, pill.bottom)
                val title = header.getChildAt(1)
                assertFalse(Rect.intersects(action, Rect(title.left, title.top, title.right, title.bottom)))
                val sheet = show(activity, setOf(PlainLyricsSearchSource.NETEASE), 320, 640)
                val morph = sheetViews(sheet).filterIsInstance<LyricsSourcesMorphView>().single()
                assertTrue(morph.panelBounds.left >= 0)
                assertTrue(morph.panelBounds.bottom <= 640)
                assertTrue(apply(sheet).isShown)
                assertTrue(sheetViews(sheet).filterIsInstance<ScrollView>().single().height >= 48)
                val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
                sheet.requireDialog().window!!.decorView.draw(Canvas(bitmap))
                File(System.getProperty("java.io.tmpdir"), "airlyrics-sources-morph-large-${if (dark) "dark" else "light"}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                sheet.dismissNow()
            }
        }
        ThemeSettingsStore.setDark(RuntimeEnvironment.getApplication(), false)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun repeatedCapsuleTapsCreateOnePanelAndOutsideTapDiscardsTheDraft() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val activity = controller.get()
            val initial = setOf(PlainLyricsSearchSource.NETEASE)
            session(activity).setSources(initial)
            settle()
            val page = activity.findViewById<ViewGroup>(android.R.id.content)
            layout(page, 400, 900)
            val bitmap = Bitmap.createBitmap(400, 900, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            page.draw(canvas)
            fun capture(name: String) {
                File(System.getProperty("java.io.tmpdir"), name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            capture("airlyrics-sources-capsule-normal.png")
            activity.sourceCapsule()!!.performClick()
            activity.sourceCapsule()!!.performClick()
            assertEquals(1, activity.supportFragmentManager.fragments.filterIsInstance<LyricsSourcesSheet>().size)
            val sheet = activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
            val dialog = sheet.requireDialog()
            layout(dialog.window!!.decorView, 400, 900)
            settle()
            canvas.drawARGB((dialog.window!!.attributes.dimAmount * 255).toInt(), 0, 0, 0)
            dialog.window!!.decorView.draw(canvas)
            capture("airlyrics-sources-panel-normal.png")
            bitmap.recycle()
            sheetViews(sheet).filterIsInstance<LyricsSourceSelectionRow>().first { it.isSelected }.performClick()
            val morph = sheetViews(sheet).filterIsInstance<LyricsSourcesMorphView>().single()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(0L, 0L, action, 0f, 0f, 0)
                morph.dispatchTouchEvent(event)
                event.recycle()
            }
            settle()
            assertFalse(dialog.isShowing)
            assertEquals(initial, session(activity).state.value.sources)
            assertNull(activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG))
            assertEquals(1f, activity.sourceCapsule()!!.alpha, 0.001f)
        }
    }

    @Test fun disabledSystemAnimationsSettleBothWaysWithoutLeavingTheCapsuleHidden() {
        val scale = ValueAnimator.getDurationScale()
        try {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f))
            Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
                val activity = controller.get()
                layout(activity.findViewById(android.R.id.content), 400, 900)
                val sheet = show(activity, setOf(PlainLyricsSearchSource.NETEASE))
                val morph = sheetViews(sheet).filterIsInstance<LyricsSourcesMorphView>().single()
                assertEquals(1f, morph.progress, 0.001f)
                val dialog = sheet.requireDialog()
                dialog.cancel()
                activity.supportFragmentManager.executePendingTransactions()
                assertFalse(dialog.isShowing)
                assertEquals(1f, activity.sourceCapsule()!!.alpha, 0.001f)
            }
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale))
        }
    }

    private fun show(activity: LyricsSearchActivity, sources: Set<PlainLyricsSearchSource>, width: Int = 400, height: Int = 900) =
        LyricsSourcesSheet.create(sources).also {
            it.showNow(activity.supportFragmentManager, LyricsSourcesSheet.TAG)
            layout(it.requireDialog().window!!.decorView, width, height)
            settle()
        }
    private fun sheetViews(sheet: LyricsSourcesSheet) = descendants(sheet.requireDialog().window!!.decorView)
    private fun apply(sheet: LyricsSourcesSheet) = sheetViews(sheet).filterIsInstance<MaterialButton>().single()
    private fun session(activity: LyricsSearchActivity) = LyricsSearchActivity::class.java.getDeclaredField("session")
        .apply { isAccessible = true }.get(activity) as LyricsSearchSession
    private fun intent() = Intent(RuntimeEnvironment.getApplication(), LyricsSearchActivity::class.java)
        .putExtra("search.title", "Song").putExtra("search.artist", "Artist")
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun settle(ms: Long = 550) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
}
