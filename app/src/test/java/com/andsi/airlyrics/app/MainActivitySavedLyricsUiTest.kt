package com.andsi.airlyrics.app

import android.content.Context
import android.media.session.MediaSessionManager
import androidx.test.core.app.ApplicationProvider
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
        clearCurrentMedia()
        resetStorage()
    }

    @Test
    fun deletionFinishingAfterRecreation_doesNotCompleteANewerRequest() {
        saveLyrics()
        val controller = launchActivity()
        val oldActivity = controller.get()
        showSavedLyricsSettings(oldActivity)
        awaitAppIo(oldActivity)
        val staleItem = LyricsStorage.listAllLyrics(oldActivity).single()
        val oldCallbackResults = mutableListOf<Boolean>()

        oldActivity.graph.deleteSavedLyrics(staleItem, oldCallbackResults::add)
        awaitBackgroundCondition { LyricsStorage.listAllLyrics(context).isEmpty() }

        controller.recreate()
        val restoredActivity = controller.get()
        val newCallbackResults = mutableListOf<Boolean>()
        restoredActivity.graph.deleteSavedLyrics(staleItem, newCallbackResults::add)
        awaitCondition { newCallbackResults.isNotEmpty() }

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

    private fun showSavedLyricsSettings(activity: MainActivity) {
        activity.graph.viewModel.selectPage(Page.SETTINGS)
        activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.SAVED_LYRICS)
        activity.graph.uiInvalidator.rebuildCurrentPage(
            animateContent = false,
            animateTabs = false
        )
    }

    private fun saveLyrics() {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Saved song",
                artist = "Saved artist",
                duration = 180_000L,
                album = "Saved album",
                plainLrc = "[00:01.00]saved line"
            )
        )
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
}
