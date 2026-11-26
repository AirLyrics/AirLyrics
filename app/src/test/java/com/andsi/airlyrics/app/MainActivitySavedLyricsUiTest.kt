package com.andsi.airlyrics.app

import com.andsi.airlyrics.testutil.resetLyricsStorage
import com.andsi.airlyrics.testutil.QueuedCallbackDispatcher
import com.andsi.airlyrics.testutil.awaitUiCondition as awaitCondition
import android.app.Application
import android.content.Context
import android.media.session.MediaSessionManager
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangedBroadcast
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
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
        resetLyricsStorage(context)
        clearCurrentMedia()
    }

    @After
    fun tearDown() {
        activityController?.close()
        clearCurrentMedia()
        resetLyricsStorage(context)
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

        val callbacks = QueuedCallbackDispatcher()
        oldActivity.graph.deleteSavedLyrics(staleItem, oldCallbackResults::add, callbacks::dispatch)
        awaitCondition { callbacks.pendingDeliveryCount() == 1 }
        val deliverOldCallback = callbacks.takeDelivery()

        controller.recreate()
        val restoredActivity = controller.get()
        val newCallbackResults = mutableListOf<Boolean>()
        val newCallbacks = QueuedCallbackDispatcher()
        restoredActivity.graph.deleteSavedLyrics(staleItem, newCallbackResults::add, newCallbacks::dispatch)
        awaitCondition { newCallbacks.pendingDeliveryCount() == 1 }
        deliverOldCallback()
        assertTrue(newCallbackResults.isEmpty())
        newCallbacks.takeDelivery()()

        assertTrue(oldActivity.isDestroyed)
        assertTrue(oldCallbackResults.isEmpty())
        assertEquals(listOf(false), newCallbackResults)
        assertFalse(LyricsStorage.listAllLyrics(restoredActivity).contains(staleItem))
        assertEquals(
            LyricsStorage.currentRevision(),
            restoredActivity.graph.state.foreground.lyricsRevision
        )
    }

    @Test
    fun deletionDeliveredBeforeRecreationCompletesTheOldRequestOnce() {
        saveLyrics()
        val controller = launchActivity()
        val activity = controller.get()
        val results = mutableListOf<Boolean>()
        activity.graph.deleteSavedLyrics(LyricsStorage.listAllLyrics(context).single(), results::add)
        awaitCondition { results.isNotEmpty() }
        assertEquals(listOf(true), results)
        controller.recreate()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(true), results)
        assertTrue(LyricsStorage.listAllLyrics(context).isEmpty())
    }

    @Test
    fun autoSaveRefreshesSavedLyricsWithoutMediaOrWindowEvents() {
        val activity = launchActivity().get()
        showSavedLyricsSettings(activity)
        ShadowLooper.idleMainLooper()
        val before = activity.graph.state
        val application = ApplicationProvider.getApplicationContext<Application>()
        fun announcedChanges() =
            shadowOf(application).broadcastIntents.mapNotNull(LyricsChangedBroadcast::readChange)
        val previousChanges = announcedChanges()
        val lyrics = LyricsProviderResult("netease", "NetEase", "[00:01.00]auto saved")
        val title = "Automatically found song"
        var providerCalls = 0
        val provider = object : PlainLyricsProvider {
            override val id = "netease"
            override val name = "NetEase"

            override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
                providerCalls++
                return Result.success(lyrics)
            }
        }
        fun lookup(save: Boolean) = LyricsRepository.findLyricsWithProviders(
            context = context,
            onlinePlainLyricsProviders = mapOf(PlainLyricsSearchSource.NETEASE to provider),
            settings = LyricsSettings(listOf(PlainLyricsSearchSource.NETEASE), autoSearchOnline = true, autoSaveLocal = save),
            title = title,
            artist = "Saved artist",
            durationMs = 180_000L
        ).getOrThrow()

        assertEquals(lyrics.plainLrc, lookup(save = false)?.plainLrc)
        ShadowLooper.idleMainLooper()
        assertTrue(LyricsStorage.listAllLyrics(context).isEmpty())
        assertEquals(before.foreground.lyricsRevision, activity.graph.state.foreground.lyricsRevision)
        assertEquals(before.lyricsChangeSequence, activity.graph.state.lyricsChangeSequence)
        assertEquals(previousChanges, announcedChanges())

        assertEquals(lyrics.plainLrc, lookup(save = true)?.plainLrc)
        ShadowLooper.idleMainLooper()
        assertEquals(title, LyricsStorage.listAllLyrics(context).single().title)
        assertEquals(
            "The saved-lyrics page must receive the committed revision without a media or window event",
            LyricsStorage.currentRevision(),
            activity.graph.state.foreground.lyricsRevision
        )
        assertEquals(before.lyricsChangeSequence + 1L, activity.graph.state.lyricsChangeSequence)
        val savedChanges = previousChanges + LyricsChange.libraryChanged()
        assertEquals(savedChanges, announcedChanges())

        assertEquals(lyrics.plainLrc, lookup(save = true)?.plainLrc)
        ShadowLooper.idleMainLooper()
        assertEquals(2, providerCalls)
        assertEquals(before.lyricsChangeSequence + 1L, activity.graph.state.lyricsChangeSequence)
        assertEquals(savedChanges, announcedChanges())
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

    private fun clearCurrentMedia() {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        shadowOf(manager).clearControllers()
        MediaSourceStore.saveSelectedPackage(context, null)
    }

}
