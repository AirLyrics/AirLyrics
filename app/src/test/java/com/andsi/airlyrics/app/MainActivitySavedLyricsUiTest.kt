package com.andsi.airlyrics.app

import android.content.Context
import android.media.session.MediaSessionManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class MainActivitySavedLyricsUiTest {
    private lateinit var context: Context
    private var activityController: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
        clearCurrentMedia()
    }

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        clearCurrentMedia()
        resetStorage()
    }

    @Test
    fun viewAll_fromLyricsPage_showsSongOutsideRecentLimit() {
        saveLyrics(RECENT_LIMIT + 1)
        val activity = launchActivity().get()
        showLyricsSettings(activity)
        awaitAppIo(activity)

        val allTitles = LyricsStorage.listAllLyrics(activity).map { it.displayTitle }
        assertEquals(RECENT_LIMIT + 1, allTitles.size)
        val lyricsPageTexts = activity.visibleTexts()
        val titlesOutsideRecentLimit = allTitles.filterNot(lyricsPageTexts::contains)
        assertTrue(lyricsPageTexts.contains(activity.getString(R.string.ui_view_all)))
        assertEquals(
            "Exactly the ninth saved song should be outside the recent list",
            1,
            titlesOutsideRecentLimit.size
        )

        activity.clickText(activity.getString(R.string.ui_view_all))
        awaitAppIo(activity)

        assertEquals(SettingsSubPage.SAVED_LYRICS, activity.graph.state.settingsSubPage)
        assertTrue(activity.visibleTexts().contains(titlesOutsideRecentLimit.single()))
    }

    @Test
    fun systemBack_fromSavedLyrics_returnsToLyricsThenSettingsHome() {
        val activity = launchActivity().get()
        showSavedLyricsSettings(activity)
        awaitAppIo(activity)

        activity.onBackPressedDispatcher.onBackPressed()
        awaitAppIo(activity)

        assertEquals(Page.SETTINGS, activity.graph.state.currentPage)
        assertEquals(SettingsSubPage.LYRICS, activity.graph.state.settingsSubPage)

        activity.onBackPressedDispatcher.onBackPressed()
        awaitAppIo(activity)

        assertEquals(Page.SETTINGS, activity.graph.state.currentPage)
        assertEquals(SettingsSubPage.HOME, activity.graph.state.settingsSubPage)
    }

    @Test
    fun savedLyricsPage_activityRecreated_preservesPageAndContent() {
        saveLyrics(1)
        val controller = launchActivity()
        val oldActivity = controller.get()
        showSavedLyricsSettings(oldActivity)
        awaitAppIo(oldActivity)
        val savedTitle = LyricsStorage.listAllLyrics(oldActivity).single().displayTitle
        assertTrue(oldActivity.visibleTexts().contains(savedTitle))

        controller.recreate()
        val restoredActivity = controller.get()
        awaitAppIo(restoredActivity)

        assertTrue(oldActivity.isDestroyed)
        assertEquals(Page.SETTINGS, restoredActivity.graph.state.currentPage)
        assertEquals(SettingsSubPage.SAVED_LYRICS, restoredActivity.graph.state.settingsSubPage)
        assertTrue(restoredActivity.visibleTexts().contains(restoredActivity.getString(R.string.ui_saved_lyrics)))
        assertTrue(restoredActivity.visibleTexts().contains(savedTitle))
    }

    @Test
    fun deletionFinishingAfterRecreation_doesNotCompleteANewerRequest() {
        saveLyrics(1)
        val controller = launchActivity()
        val oldActivity = controller.get()
        showSavedLyricsSettings(oldActivity)
        awaitAppIo(oldActivity)
        val staleItem = LyricsStorage.listAllLyrics(oldActivity).single()
        val oldCallbackResults = mutableListOf<Boolean>()

        oldActivity.graph.deleteSavedLyrics(staleItem, oldCallbackResults::add)
        awaitBackgroundCondition {
            LyricsStorage.listAllLyrics(context).isEmpty()
        }

        controller.recreate()
        val restoredActivity = controller.get()
        val newCallbackResults = mutableListOf<Boolean>()
        restoredActivity.graph.deleteSavedLyrics(staleItem, newCallbackResults::add)
        awaitCondition {
            newCallbackResults.isNotEmpty()
        }

        assertTrue(oldActivity.isDestroyed)
        assertTrue(oldCallbackResults.isEmpty())
        assertEquals(listOf(false), newCallbackResults)
        assertFalse(LyricsStorage.listAllLyrics(restoredActivity).contains(staleItem))
        assertEquals(
            LyricsStorage.currentRevision(),
            restoredActivity.graph.state.foreground.lyricsRevision
        )
    }

    private fun launchActivity(): ActivityController<MainActivity> {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
    }

    private fun showLyricsSettings(activity: MainActivity) {
        showSettingsPage(activity, SettingsSubPage.LYRICS)
    }

    private fun showSavedLyricsSettings(activity: MainActivity) {
        showSettingsPage(activity, SettingsSubPage.SAVED_LYRICS)
    }

    private fun showSettingsPage(activity: MainActivity, subPage: SettingsSubPage) {
        activity.graph.viewModel.selectPage(Page.SETTINGS)
        activity.graph.viewModel.openSettingsSubPage(subPage)
        activity.graph.uiInvalidator.rebuildCurrentPage(
            animateContent = false,
            animateTabs = false
        )
    }

    private fun saveLyrics(count: Int) {
        repeat(count) { index ->
            val number = index + 1
            assertTrue(
                LyricsStorage.savePlainLyrics(
                    context = context,
                    title = "Saved song $number",
                    artist = "Saved artist $number",
                    duration = 180_000L + number,
                    album = "Saved album $number",
                    plainLrc = "[00:01.00]saved line $number"
                )
            )
        }
    }

    private fun awaitAppIo(activity: MainActivity) {
        val completed = CountDownLatch(1)
        activity.graph.runOnAppIo { completed.countDown() }
        assertTrue("Timed out waiting for app I/O", completed.await(5, TimeUnit.SECONDS))
        ShadowLooper.idleMainLooper()
    }

    private fun awaitBackgroundCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for background work", condition())
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Timed out waiting for UI work", condition())
    }

    private fun MainActivity.visibleTexts(): List<String> {
        return findViewById<View>(android.R.id.content).descendantTexts()
    }

    private fun MainActivity.clickText(text: String) {
        val textView = findViewById<View>(android.R.id.content).findTextView(text)
        assertNotNull("Missing text: $text", textView)
        val clickableView = textView!!.clickableAncestor()
        assertNotNull("No clickable view contains: $text", clickableView)
        assertTrue(clickableView!!.performClick())
        ShadowLooper.idleMainLooper()
    }

    private fun View.descendantTexts(): List<String> {
        val ownText = (this as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (this !is ViewGroup) return ownText
        return ownText + (0 until childCount).flatMap { getChildAt(it).descendantTexts() }
    }

    private fun View.findTextView(text: String): TextView? {
        if (this is TextView && this.text.toString() == text) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findTextView(text) }
    }

    private fun View.clickableAncestor(): View? {
        var candidate: View? = this
        while (candidate != null) {
            if (candidate.isClickable) return candidate
            candidate = candidate.parent as? View
        }
        return null
    }

    private fun clearCurrentMedia() {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        shadowOf(manager).clearControllers()
        MediaSourceStore.saveSelectedPackage(context, null)
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    private companion object {
        const val RECENT_LIMIT = 8
    }
}
